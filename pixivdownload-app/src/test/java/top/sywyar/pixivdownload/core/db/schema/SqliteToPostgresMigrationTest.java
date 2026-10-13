package top.sywyar.pixivdownload.core.db.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.api.schema.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("真实 SQLite 到 PostgreSQL 停机迁移")
class SqliteToPostgresMigrationTest {
    @TempDir Path directory;
    private final DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();

    private Connection source(Path file) throws Exception {
        var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openConnection(file);
        top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.offline(connection, registry.contributions(), registry.mergedSchema())
                .applySchema(registry.contributions(), registry.mergedSchema());
        return connection;
    }

    private top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSnapshot migrate(Path source, String backup, PostgresTestDatabase destination,
                                                     Set<String> rebuild) throws Exception {
        return top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.migrate(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(source), directory.resolve(backup),
                top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(destination.properties(), "unused"), registry, true, rebuild);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"UTF-8", "UTF-16le", "UTF-16be"})
    @DisplayName("文本主键按中性行序从各 SQLite 编码迁入 PostgreSQL，再回迁 SQLite")
    void textKeysRoundTripAcrossProviders(String encoding) throws Exception {
        var source = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("text-source.db")).migrationEndpoint();
        var schema = new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema(1, List.of(
                new TableSpec("facts", List.of(new ColumnSpec("id", "TEXT", true, null, 1),
                        new ColumnSpec("body", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
        var keys = List.of("A", "\u0100", "\uE000", "\uD83C\uDF38");
        try (var connection = source.target().openConnection(top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_WRITE);
             var sql = connection.createStatement()) {
            sql.execute("PRAGMA encoding='" + encoding + "'");
            sql.execute("CREATE TABLE facts(id TEXT NOT NULL PRIMARY KEY,body TEXT)");
            try (var write = connection.prepareStatement("INSERT INTO facts VALUES(?,?)")) {
                for (String key : keys) { write.setString(1, key); write.setString(2, "正文 " + key); write.executeUpdate(); }
            }
        }
        try (var database = new PostgresTestDatabase()) {
            var pg = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(database.properties(), "unused").migrationEndpoint();
            var restored = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("text-restored.db")).migrationEndpoint();
            var endpoints = List.of(source, pg, restored);
            for (int i = 0; i < 2; i++) {
                var from = endpoints.get(i); var to = endpoints.get(i + 1);
                try (var exported = from.provider().exportDatabase(from.target(),
                        new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationRequest(schema, directory.resolve("text-backup-" + i), Set.of()), (table, rows) -> {});
                     var imported = to.provider().importDatabase(to.target(), schema, (table, rows) -> {})) {
                    try (var rows = exported.rows("facts")) { imported.write("facts", rows); }
                    assertThat(imported.verify(exported.snapshot())).isEqualTo(exported.snapshot().verification());
                    imported.commit();
                    to.provider().verifyImported(to.target(), exported.snapshot(), (table, rows) -> {});
                }
            }
        }
    }

    @Test
    @DisplayName("完整源结构可独立迁出且关闭会话后源仍可写")
    void exportsCompleteSourceThroughProvider() throws Exception {
        Path file = directory.resolve("complete.db");
        var endpoint = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(file).migrationEndpoint();
        try (var input = source(file); var statement = input.createStatement()) {
            statement.execute("INSERT INTO tags(name) VALUES('source row')");
            try (var exported = endpoint.provider().exportDatabase(endpoint.target(),
                    new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationRequest(
                            CoreMigrationSchema.create(registry, true), directory.resolve("complete-backup.db"), Set.of()),
                    (table, rows) -> {})) {
                assertThat(exported.snapshot().verification().tables().get("tags").rows()).isOne();
            }
            statement.execute("INSERT INTO tags(name) VALUES('after release')");
        }
    }

    @Test
    @DisplayName("一致备份包含 WAL，保留插件数据及未完成媒体页并校正后续生成键")
    void copiesCommittedWalUnicodeNullSoftDeletionAndRetainedPluginRowsThenRestartsAndWrites() throws Exception {
        registry.register("retained-fixture", new SchemaContribution(List.of(new TableSpec("retained_values",
                List.of(new ColumnSpec("id", "INTEGER", false, null, 1),
                        new ColumnSpec("body", "TEXT", false, null, 0),
                        new ColumnSpec("payload", "BLOB", false, null, 0)), List.of(), null)), List.of(), List.of()));
        Path source = directory.resolve("source.db");
        try (var input = source(source); var writer = input.createStatement(); var destination = new PostgresTestDatabase()) {
            writer.execute("PRAGMA wal_autocheckpoint=0");
            writer.execute("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time,deleted,metadata_json)"
                    + " VALUES(5000000001,'作品 🌸','{0}/作品',2,'png',1700000000123,1,'{\"author\":\"作者\"}')");
            writer.execute("INSERT INTO tags(tag_id,name,translated_name) VALUES(9000000001,'タグ',NULL)");
            writer.execute("INSERT INTO artwork_tags VALUES(5000000001,9000000001)");
            writer.execute("INSERT INTO retained_values VALUES(8000000001,'保留插件数据',X'00FF0100')");
            writer.execute("INSERT INTO artwork_media(artwork_id,page,original_extension,extensions,original_retained)"
                    + " VALUES(6000000001,0,'png','png',1)");
            assertThat(Files.size(Path.of(source + "-wal"))).isPositive();
            var result = migrate(source, "backup.db", destination, Set.of());
            assertThat(result.verification().tables()).hasSize(registry.mergedSchema().tables().size());
            assertThat(result.verification().tables().get("artworks").rows()).isEqualTo(1);
            assertThat(result.verification().referenceWarnings()).containsEntry("artwork_media:artwork_id:artworks:artwork_id", 1L);
            var jdbc = new JdbcTemplate(destination.pool());
            assertThat(jdbc.queryForObject("SELECT title FROM artworks", String.class)).isEqualTo("作品 🌸");
            assertThat(jdbc.queryForObject("SELECT time FROM artworks", Long.class)).isEqualTo(1700000000123L);
            assertThat(jdbc.queryForObject("SELECT deleted FROM artworks", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT translated_name FROM tags", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT payload FROM retained_values", byte[].class)).containsExactly(0, (byte)255, 1, 0);
            jdbc.update("INSERT INTO tags(name) VALUES('迁移后写入')");
            assertThat(jdbc.queryForObject("SELECT tag_id FROM tags WHERE name='迁移后写入'", Long.class)).isGreaterThan(9000000001L);
            top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(), TestI18nBeans.appMessages(), event -> {}).initialize();
            try (var backup = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openExisting(result.backup()); var read = backup.createStatement();
                 var rows = read.executeQuery("SELECT title FROM artworks")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("作品 🌸");
            }
            // 原始备份没有被规范化副本补写默认文件名池；源库也未改写。
            try (var rows = writer.executeQuery("SELECT count(*) FROM file_name_templates")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("迁移保留删除后的自增高水位，空表不复用旧身份且孤儿关联保持孤立")
    void preservesDeletedAutoincrementHighWaterMarks() throws Exception {
        Path source = directory.resolve("sequence.db");
        try (var input = source(source); var statement = input.createStatement();
             var destination = new PostgresTestDatabase()) {
            statement.execute("INSERT INTO tags(name) VALUES('retained'),('deleted')");
            statement.execute("INSERT INTO artwork_tags(artwork_id,tag_id) VALUES(5000000001,2)");
            statement.execute("DELETE FROM tags WHERE tag_id=2");
            statement.execute("INSERT INTO collections(id,name,created_time) VALUES(7000000001,'deleted',1700000000123)");
            statement.execute("DELETE FROM collections");
            statement.execute("INSERT INTO file_author_names(id,name) VALUES(1,'updated identity')");
            statement.execute("UPDATE file_author_names SET id=9000000001");
            // 显式身份没有 AUTOINCREMENT 的表仍须按现存最大值继续生成。
            statement.execute("INSERT INTO authors(author_id,name,updated_time) VALUES(8000000001,'retained',1700000000123)");
            var result = migrate(source, "sequence-backup.db", destination, Set.of());
            assertThat(result.verification().referenceWarnings()).containsEntry("artwork_tags:tag_id:tags:tag_id", 1L);
            try (var backup = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openExisting(result.backup()); var read = backup.createStatement();
                 var rows = read.executeQuery("SELECT seq FROM sqlite_sequence WHERE name='tags'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(2);
            }
            var jdbc = new JdbcTemplate(destination.pool());
            top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(jdbc, registry.contributions(), registry.mergedSchema(),
                    TestI18nBeans.appMessages(), event -> {}).initialize();
            assertThat(jdbc.queryForObject("INSERT INTO tags(name) VALUES('next') RETURNING tag_id", Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("INSERT INTO collections(name,created_time) VALUES('next',1700000000124) RETURNING id",
                    Long.class)).isEqualTo(7_000_000_002L);
            assertThat(jdbc.queryForObject("INSERT INTO authors(name,updated_time) VALUES('next',1700000000124) RETURNING author_id",
                    Long.class)).isEqualTo(8_000_000_002L);
            assertThat(jdbc.queryForObject("INSERT INTO file_author_names(name) VALUES('next') RETURNING id",
                    Long.class)).isEqualTo(9_000_000_002L);
            assertThat(jdbc.queryForObject("INSERT INTO path_prefixes(path) VALUES('/new') RETURNING id",
                    Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM artwork_tags a JOIN tags t ON a.tag_id=t.tag_id",
                    Long.class)).isZero();
            try (var rows = statement.executeQuery("INSERT INTO tags(name) VALUES('next') RETURNING tag_id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(3);
            }
            try (var rows = statement.executeQuery(
                    "INSERT INTO collections(name,created_time) VALUES('next',1700000000124) RETURNING id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(7_000_000_002L);
            }
        }
    }

    @Test
    @DisplayName("非空目标和未知源表在写入前拒绝并保留原数据")
    void rejectsNonemptyTargetAndUnknownTablesWithoutChangingEitherDatabase() throws Exception {
        Path source = directory.resolve("source.db");
        try (var input = source(source); var statement = input.createStatement(); var destination = new PostgresTestDatabase()) {
            var jdbc = new JdbcTemplate(destination.pool());
            jdbc.execute("CREATE TABLE keep_me(value TEXT)");
            jdbc.update("INSERT INTO keep_me VALUES('existing')");
            assertThatThrownBy(() -> migrate(source, "blocked.db", destination, Set.of())).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT value FROM keep_me", String.class)).isEqualTo("existing");
            assertThat(Files.exists(directory.resolve("blocked.db"))).isFalse();
            jdbc.execute("DROP TABLE keep_me");
            jdbc.execute("CREATE TYPE keep_type AS ENUM ('retained')");
            assertThatThrownBy(() -> migrate(source, "type-blocked.db", destination, Set.of()))
                    .isInstanceOf(IllegalStateException.class);
            jdbc.execute("DROP TYPE keep_type");
            statement.execute("CREATE TABLE unknown_history(id INTEGER PRIMARY KEY, value TEXT)");
            assertThatThrownBy(() -> migrate(source, "unknown.db", destination, Set.of())).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname=current_schema()", Integer.class)).isZero();
            assertThat(Files.exists(directory.resolve("unknown.db"))).isFalse();
        }
    }

    @Test
    @DisplayName("SQLite 动态类型不兼容时回滚目标 DDL 与数据，修复源后可重试")
    void invalidDynamicSqliteValueRollsBackTargetDdlAndRowsAndAllowsRetry() throws Exception {
        Path source = directory.resolve("source.db");
        try (var input = source(source); var statement = input.createStatement(); var destination = new PostgresTestDatabase()) {
            statement.execute("INSERT INTO tags(tag_id,name) VALUES(7,'preserved')");
            statement.execute("INSERT INTO artworks(artwork_id,title,folder,count,extensions,time)"
                    + " VALUES(1,'private row','{0}/one','not-an-integer','png',1700000000123)");
            assertThatThrownBy(() -> migrate(source, "failed-backup.db", destination, Set.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private row")
                    .hasMessageNotContaining("not-an-integer");
            var jdbc = new JdbcTemplate(destination.pool());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname=current_schema()", Integer.class)).isZero();
            assertThat(Files.isRegularFile(directory.resolve("failed-backup.db"))).isTrue();
            statement.execute("UPDATE artworks SET count=1");
            migrate(source, "retry-backup.db", destination, Set.of());
            assertThat(jdbc.queryForObject("SELECT name FROM tags", String.class)).isEqualTo("preserved");
        }
    }

    @Test
    @DisplayName("旧 schema 只在工作副本升级，可再生对象必须显式确认")
    void oldSchemaIsUpgradedOnlyInWorkingCopyAndRegenerableTablesRequireExplicitAcknowledgement() throws Exception {
        Path source = directory.resolve("legacy.db");
        try (var input = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openConnection(source); var statement = input.createStatement();
             var destination = new PostgresTestDatabase()) {
            statement.execute("CREATE TABLE artworks(artwork_id INTEGER PRIMARY KEY,title TEXT NOT NULL,folder TEXT NOT NULL,"
                    + "count INTEGER NOT NULL,extensions TEXT NOT NULL,time INTEGER NOT NULL UNIQUE)");
            statement.execute("INSERT INTO artworks VALUES(1,'old','{0}/old',1,'jpg',1700000000123)");
            statement.execute("CREATE VIRTUAL TABLE fixture_search USING fts5(content)");
            statement.execute("INSERT INTO fixture_search VALUES('rebuildable')");
            assertThatThrownBy(() -> migrate(source, "rejected.db", destination, Set.of())).isInstanceOf(IllegalStateException.class);
            var result = migrate(source, "legacy-backup.db", destination, Set.of("fixture_search"));
            assertThat(result.rebuildObjects()).containsExactly("fixture_search");
            var jdbc = new JdbcTemplate(destination.pool());
            assertThat(jdbc.queryForObject("SELECT deleted FROM artworks", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM file_name_templates WHERE id=1", Integer.class)).isEqualTo(1);
            try (var columns = statement.executeQuery("SELECT count(*) FROM pragma_table_info('artworks')")) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt(1)).isEqualTo(6);
            }
        }
    }

    @Test
    @DisplayName("源写者或目标迁移锁占用时拒绝，不产生备份和半成品")
    void refusesActiveSourceWriterAndConcurrentTargetMigration() throws Exception {
        Path source = directory.resolve("busy.db");
        try (var input = source(source); var statement = input.createStatement();
             var destination = new PostgresTestDatabase(); var targetLock = destination.pool().getConnection()) {
            statement.execute("BEGIN IMMEDIATE");
            assertThatThrownBy(() -> migrate(source, "source-busy.db", destination, Set.of()))
                    .isInstanceOf(IllegalStateException.class);
            statement.execute("ROLLBACK");
            assertThat(directory.resolve("source-busy.db")).doesNotExist();
            targetLock.setAutoCommit(false);
            try (var lock = targetLock.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(hashtextextended(current_database() || '.' || current_schema(),0))");
                assertThatThrownBy(() -> migrate(source, "target-busy.db", destination, Set.of()))
                        .isInstanceOf(IllegalStateException.class);
            } finally { targetLock.rollback(); }
            assertThat(directory.resolve("target-busy.db")).doesNotExist();
            assertThat(new JdbcTemplate(destination.pool()).queryForObject(
                    "SELECT count(*) FROM pg_tables WHERE schemaname=current_schema()", Integer.class)).isZero();
        }
    }

    @Test
    @DisplayName("迁移统一清除文本 NUL，源库和原始备份保持不变")
    void normalizesTextWithoutChangingSourceOrBackup() throws Exception {
        Path source = directory.resolve("invalid-text.db");
        try (var input = source(source); var statement = input.createStatement(); var destination = new PostgresTestDatabase()) {
            statement.execute("INSERT INTO authors VALUES(8,'earlier row',1700000000123)");
            try (var insert = input.prepareStatement("INSERT INTO tags(name) VALUES(?)")) {
                insert.setString(1, "简介\0保留\r\n\t🌸\0");
                insert.executeUpdate();
            }
            var snapshot = migrate(source, "text-backup.db", destination, Set.of());
            assertThat(new JdbcTemplate(destination.pool()).queryForObject("SELECT name FROM tags", String.class))
                    .isEqualTo("简介保留\r\n\t🌸");
            for (Path preserved : List.of(source, snapshot.backup())) {
                try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openExisting(preserved);
                     var query = connection.createStatement(); var rows = query.executeQuery("SELECT name FROM tags")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("简介\0保留\r\n\t🌸\0");
                }
            }
            assertThat(new JdbcTemplate(destination.pool()).queryForObject("SELECT name FROM authors", String.class))
                    .isEqualTo("earlier row");
        }
    }

    @Test
    @DisplayName("清洗造成唯一键冲突时拒绝迁移并回滚，不合并或丢弃记录")
    void normalizationCollisionPreservesBothRows() throws Exception {
        Path source = directory.resolve("collision.db");
        try (var input = source(source); var destination = new PostgresTestDatabase();
             var insert = input.prepareStatement("INSERT INTO tags(name) VALUES(?)")) {
            for (String name : List.of("private\0value", "privatevalue")) {
                insert.setString(1, name); insert.executeUpdate();
            }
            assertThatThrownBy(() -> migrate(source, "collision-backup.db", destination, Set.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("privatevalue").hasNoCause();
            assertThat(new JdbcTemplate(destination.pool()).queryForObject(
                    "SELECT count(*) FROM pg_tables WHERE schemaname=current_schema()", Integer.class)).isZero();
            for (Path preserved : List.of(source, directory.resolve("collision-backup.db"))) {
                try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openExisting(preserved);
                     var query = connection.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM tags")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2);
                }
            }
        }
    }

    @Test
    @DisplayName("复制期间真实连接被终止后回滚全部目标结构，保留备份并支持重新迁移")
    void lostPostgresConnectionRollsBackAndRetryUsesNewBackup() throws Exception {
        Path source = directory.resolve("disconnect.db");
        try (var input = source(source); var statement = input.createStatement(); var destination = new PostgresTestDatabase()) {
            statement.execute("INSERT INTO authors VALUES(1,'迁移前历史',1700000000123)");
            statement.execute("WITH RECURSIVE rows(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM rows WHERE n<30000)"
                    + " INSERT INTO tags(tag_id,name) SELECT n,'内容-'||n||'-'||hex(zeroblob(512)) FROM rows");
            var properties = destination.properties();
            String application = "migration-fault-" + java.util.UUID.randomUUID();
            properties.setJdbcUrl(properties.getJdbcUrl() + "&ApplicationName=" + application);
            var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(properties, "unused");
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.migrate(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(source), directory.resolve("disconnect-backup.db"),
                    target, registry, true, Set.of()));
            var jdbc = new JdbcTemplate(destination.pool());
            boolean terminated = false;
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                while (!future.isDone() && System.nanoTime() < deadline) {
                    var pids = jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE application_name=?"
                            + " AND query LIKE 'INSERT INTO \"tags\"%'", Integer.class, application);
                    if (!pids.isEmpty()) {
                        assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pids.get(0))).isTrue();
                        terminated = true;
                        break;
                    }
                    Thread.sleep(10);
                }
                assertThat(terminated).as("必须实际终止正在复制 tags 的 PostgreSQL 后端").isTrue();
                assertThatThrownBy(() -> future.get(30, java.util.concurrent.TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            assertThat(directory.resolve("disconnect-backup.db")).isRegularFile();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname=current_schema()", Integer.class)).isZero();
            migrate(source, "disconnect-retry.db", destination, Set.of());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Long.class)).isEqualTo(30000);
            assertThat(jdbc.queryForObject("SELECT name FROM authors", String.class)).isEqualTo("迁移前历史");
        }
    }
}
