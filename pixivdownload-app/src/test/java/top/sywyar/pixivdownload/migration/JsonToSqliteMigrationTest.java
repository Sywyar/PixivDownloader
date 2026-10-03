package top.sywyar.pixivdownload.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("旧 JSON 离线迁移")
class JsonToSqliteMigrationTest {

    @Test
    @DisplayName("离线迁移写入指定 SQLite，重跑保留已有作品与统计")
    void importsIntoRequestedDatabaseWithoutOverwritingOnRetry(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("existing.db");
        Path downloads = Files.createDirectories(tempDir.resolve("downloads"));
        Files.writeString(downloads.resolve(JsonToSqliteMigration.DOWNLOAD_HISTORY_FILE), """
                {"downloaded":{"5000000000":{"title":"原始标题","folder":"/works/one/",
                "count":2,"extensions":"jpg","time":1700000000,"moved":true,
                "moveFolder":"/moved/one/","moveTime":1700000001}}}
                """, StandardCharsets.UTF_8);
        Files.writeString(downloads.resolve(JsonToSqliteMigration.STATISTICS_FILE),
                "{\"totalArtworks\":1,\"totalImages\":2,\"totalMoved\":1}", StandardCharsets.UTF_8);
        var options = new JsonToSqliteMigration.Options(database.toString(), downloads.toString());

        assertThat(JsonToSqliteMigration.countCandidates(options)).isEqualTo(1);
        assertThat(JsonToSqliteMigration.run(options, null).migrated()).isEqualTo(1);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE artworks SET title = '保留修改'");
            statement.executeUpdate("UPDATE statistics SET total_images = 9");
        }
        var retry = JsonToSqliteMigration.run(options, null);
        assertThat(retry.migrated()).isZero();
        assertThat(retry.skipped()).isEqualTo(1);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT * FROM artworks")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("artwork_id")).isEqualTo(5000000000L);
                assertThat(row.getString("title")).isEqualTo("保留修改");
                assertThat(row.getString("folder")).isEqualTo("/works/one");
                assertThat(row.getLong("time")).isEqualTo(1700000000000L);
                assertThat(row.getInt("moved")).isEqualTo(1);
                assertThat(row.getString("move_folder")).isEqualTo("/moved/one");
                assertThat(row.getLong("move_time")).isEqualTo(1700000001000L);
                assertThat(row.next()).isFalse();
            }
            try (var row = statement.executeQuery("SELECT total_images FROM statistics")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(9);
            }
            try (var row = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("wal");
            }
        }
    }
}
