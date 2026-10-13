package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.migration.JsonToSqliteMigration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
class PostgresOfflineToolsTest {
    @TempDir Path directory;

    @Test
    @DisplayName("PostgreSQL 回填使用配置目标且单作品失败全部回滚")
    void backfillUsesConfiguredTargetAndRollsBackTheWholeArtworkOnFailure() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(database.properties(), "unused");
            try (var connection = target.openConnection()) { top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.prepareOffline(connection); }
            var jdbc = new JdbcTemplate(database.pool());
            jdbc.update("INSERT INTO artworks(artwork_id, title, folder, count, extensions, time)"
                    + " VALUES(5000000001, '旧作品', '{0}/旧作品', 1, 'png', 1700000000123)");
            // 模拟历史结构缺列，停机工具必须补齐同样的 BIGINT 类型。
            jdbc.execute("ALTER TABLE artworks DROP COLUMN description");
            var unreachable = ArtworksBackFillUnreachableStore.load(directory.resolve("missing.json"), new ObjectMapper());
            try (var tool = ArtworksBackFillDatabase.open(target)) {
                assertThat(tool.countCandidates(0, unreachable)).isEqualTo(1);
                var candidate = tool.findCandidates(0, unreachable).candidates().get(0);
                var result = ArtworksBackFillPixivClient.LookupResult.found(
                        6000000001L, "作者 🌸", 1, true, "中文描述", java.util.List.of(), 0, 0, null);
                jdbc.execute("ALTER TABLE authors ADD CONSTRAINT reject_fixture CHECK (author_id < 0)");
                assertThatThrownBy(() -> tool.applyUpdates(candidate, result, true, true, true, true, false, false))
                        .isInstanceOf(SQLException.class);
                assertThat(jdbc.queryForObject("SELECT author_id FROM artworks", Long.class)).isNull();
                assertThat(jdbc.queryForObject("SELECT description FROM artworks", String.class)).isNull();
                jdbc.execute("ALTER TABLE authors DROP CONSTRAINT reject_fixture");
                tool.applyUpdates(candidate, result, true, true, true, true, false, false);
                assertThat(jdbc.queryForObject("SELECT author_id FROM artworks", Long.class)).isEqualTo(6000000001L);
                assertThat(jdbc.queryForObject("SELECT name FROM authors", String.class)).isEqualTo("作者 🌸");
            }
            try (var tool = ArtworksBackFillDatabase.open(database.pool(), target)) {
                assertThat(tool.countCandidates(1, unreachable)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("旧 JSON 导入 PostgreSQL 可重复运行且不创建 SQLite")
    void legacyJsonImportsIntoPostgresWithoutCreatingSqliteAndCanBeRepeated() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(database.properties(), "unused");
            Files.writeString(directory.resolve("download_history.json"), """
                    {"downloaded":{"5000000001":{"title":"旧资料 🌸","folder":"{0}/旧资料","count":2,
                    "extensions":"png","time":1700000000123,"moved":true,"moveFolder":"{1}/迁移", "moveTime":1700000000456}}}
                    """, StandardCharsets.UTF_8);
            var options = new JsonToSqliteMigration.Options(target.displayName(), directory.toString(), target);
            assertThat(JsonToSqliteMigration.run(options, ignored -> {}).migrated()).isEqualTo(1);
            assertThat(JsonToSqliteMigration.run(options, ignored -> {}).skipped()).isEqualTo(1);
            var jdbc = new JdbcTemplate(database.pool());
            assertThat(jdbc.queryForObject("SELECT title FROM artworks", String.class)).isEqualTo("旧资料 🌸");
            assertThat(jdbc.queryForObject("SELECT move_time FROM artworks", Long.class)).isEqualTo(1700000000456L);
            assertThat(jdbc.queryForObject("SELECT move_folder FROM artworks", String.class)).isEqualTo("{1}/迁移");
            assertThat(Files.exists(directory.resolve("pixiv_download.db"))).isFalse();
        }
    }

    @Test
    @DisplayName("活动池导入失败不留下前半批记录，拒绝写入不匹配的 schema")
    void pooledJsonImportRollsBackAndRejectsMismatchedTarget() throws Exception {
        try (var database = new PostgresTestDatabase(); var other = new PostgresTestDatabase()) {
            var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(database.properties(), "unused");
            try (var connection = target.openConnection()) { top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.prepareOffline(connection); }
            Files.writeString(directory.resolve("download_history.json"), """
                    {"downloaded":{"1":{"title":"valid","folder":"{0}/one","count":1,"extensions":"png","time":1700000000001},
                    "2":{"title":"invalid","folder":"{0}/two","count":1,"time":1700000000002}}}
                    """, StandardCharsets.UTF_8);
            var options = new JsonToSqliteMigration.Options(target.displayName(), directory.toString(), target);
            assertThatThrownBy(() -> JsonToSqliteMigration.run(options, ignored -> {}, database.pool()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("STORAGE_OPERATION_INVALID");
            assertThat(new JdbcTemplate(database.pool()).queryForObject("SELECT count(*) FROM artworks", Long.class)).isZero();
            assertThatThrownBy(() -> JsonToSqliteMigration.run(options, ignored -> {}, other.pool()))
                    .isInstanceOf(SQLException.class);
            assertThat(new JdbcTemplate(other.pool()).queryForObject(
                    "SELECT count(*) FROM pg_tables WHERE schemaname=current_schema()", Long.class)).isZero();
        }
    }
}
