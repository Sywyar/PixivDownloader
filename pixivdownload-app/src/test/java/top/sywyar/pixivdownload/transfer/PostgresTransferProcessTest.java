package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.migration.MigrationProcessHarness;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("环境归档在真实 PostgreSQL 主私库间跨进程恢复")
@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
class PostgresTransferProcessTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private static final String OWNER = "pixiv-batch-downloader-import";
    private static final long ID = 9007199254740993L;

    @Test @DisplayName("SQLite 归档迁入 PostgreSQL 后再导出，恢复到另一 PostgreSQL 及已有 SQLite 并保留备份")
    void roundTripsMainAndPrivateStores() throws Exception {
        Path source = sqlite("source", "归档 🌸", 123), restored = sqlite("restored", "old", 999);
        Path archive = root.resolve("sqlite.pdbackup"), exported = root.resolve("postgres.pdbackup");
        run(source, "export", archive);
        try (var first = new PostgresTestDatabase(); var second = new PostgresTestDatabase()) {
            Path pg = postgres("postgres", first), pgCopy = postgres("postgres-copy", second);
            var jdbc = new JdbcTemplate(first.pool());
            var secondJdbc = new JdbcTemplate(second.pool());
            try {
                byte[] config = Files.readAllBytes(pg.resolve("config/config.yaml"));
                run(pg, "preview", archive);
                JsonNode preview = json.readTree(pg.resolve("preview.json").toFile());
                assertThat(preview.path("databaseFailure").asText()).isEmpty();
                assertThat(preview.path("databases")).hasSize(2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema()", Integer.class)).isZero();
                assertThat(pg.resolve("data/install_identity.txt")).doesNotExist();
                assertThat(pg.resolve("config/credentials")).doesNotExist();
                assertThat(Files.readAllBytes(pg.resolve("config/config.yaml"))).isEqualTo(config);
                restore(pg, archive);
                assertPostgres(jdbc, pg);
                assertThat(Files.readString(pg.resolve("data/install_identity.txt")))
                        .isNotEqualTo(Files.readString(source.resolve("data/install_identity.txt")));
                run(pg, "export", exported);
                restore(pgCopy, exported);
                assertPostgres(secondJdbc, pgCopy);
                assertThat(Files.readString(pgCopy.resolve("data/install_identity.txt")))
                        .isNotEqualTo(Files.readString(pg.resolve("data/install_identity.txt")));
                String restoredIdentity = Files.readString(restored.resolve("data/install_identity.txt"));
                restore(restored, exported);
                assertThat(Files.readString(restored.resolve("data/install_identity.txt"))).isEqualTo(restoredIdentity);
                assertSqlite(restored.resolve("main.db"), "SELECT name FROM tags", "归档 🌸");
                assertSqlite(restored.resolve("data/" + OWNER + "/plugin.db"), "SELECT imported_at FROM imported_works", "123");
                Path backup = restored.resolve("data/environment-transfers").resolve(job(restored).path("id").asText()).resolve("database-backups");
                assertSqlite(backup.resolve("host.backup"), "SELECT name FROM tags", "old");
                assertSqlite(backup.resolve(OWNER + ".backup"), "SELECT imported_at FROM imported_works", "999");
                assertPostgres(jdbc, pg);
                assertSqlite(source.resolve("main.db"), "SELECT name FROM tags", "归档 🌸");
                try (var connection = DatabaseProviderTestFixtures.openExisting(restored.resolve("main.db")); var sql = connection.createStatement()) {
                    sql.execute("INSERT INTO tags(name) VALUES('after restore')");
                    try (var row = sql.executeQuery("SELECT tag_id FROM tags WHERE name='after restore'")) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getLong(1)).isGreaterThan(ID);
                    }
                }
            } finally {
                cleanupPrivate(jdbc, pg);
                cleanupPrivate(secondJdbc, pgCopy);
            }
        }
    }

    @Test @DisplayName("不支持替换的非空 PostgreSQL 在预览时拒绝，原数据与配置不变")
    void rejectsNonemptyTarget() throws Exception {
        Path source = sqlite("source", "source", 123), archive = root.resolve("archive.pdbackup");
        run(source, "export", archive);
        try (var database = new PostgresTestDatabase()) {
            Path target = postgres("target", database);
            var jdbc = new JdbcTemplate(database.pool());
            jdbc.execute("CREATE TABLE retained(id bigint PRIMARY KEY, value text)");
            jdbc.update("INSERT INTO retained VALUES(1, 'keep me')");
            byte[] before = Files.readAllBytes(target.resolve("config/config.yaml"));
            run(target, "preview", archive);
            assertThat(json.readTree(target.resolve("preview.json").toFile()).path("databaseFailure").asText()).isNotEmpty();
            assertThat(jdbc.queryForObject("SELECT value FROM retained", String.class)).isEqualTo("keep me");
            assertThat(Files.readAllBytes(target.resolve("config/config.yaml"))).isEqualTo(before);
            assertThat(target.resolve("data/environment-transfers/pending.json")).doesNotExist();
            assertThat(target.resolve("config/credentials")).doesNotExist();
        }
    }

    @Test @DisplayName("确认后 PostgreSQL 空目标被写入时拒绝导入，不覆盖并发数据")
    void rejectsTargetChangedAfterConfirmation() throws Exception {
        Path source = sqlite("source", "source", 123), archive = root.resolve("archive.pdbackup");
        run(source, "export", archive);
        try (var database = new PostgresTestDatabase()) {
            Path target = postgres("target", database);
            var jdbc = new JdbcTemplate(database.pool());
            try {
                run(target, "prepare", archive);
                byte[] config = Files.readAllBytes(target.resolve("config/config.yaml"));
                jdbc.execute("CREATE TABLE concurrent_write(id bigint PRIMARY KEY)");
                jdbc.update("INSERT INTO concurrent_write VALUES(73)");
                run(target, "run", archive);
                assertThat(job(target).path("status").asText()).isEqualTo("FAILED");
                assertThat(jdbc.queryForObject("SELECT id FROM concurrent_write", Long.class)).isEqualTo(73);
                assertThat(Files.readAllBytes(target.resolve("config/config.yaml"))).isEqualTo(config);
                assertThat(privateSchemas(jdbc, target)).isEmpty();
            } finally { cleanupPrivate(jdbc, target); }
        }
    }

    private void assertPostgres(JdbcTemplate jdbc, Path directory) throws Exception {
        assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE tag_id=" + ID, String.class)).isEqualTo("归档 🌸");
        assertThat(jdbc.queryForObject("SELECT translated_name FROM tags WHERE tag_id=" + ID, String.class)).isNull();
        List<String> schemas = privateSchemas(jdbc, directory);
        assertThat(schemas).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT work_id FROM " + schemas.get(0) + ".imported_works", Long.class)).isEqualTo(ID);
        assertThat(jdbc.queryForObject("SELECT imported_at FROM " + schemas.get(0) + ".imported_works", Long.class)).isEqualTo(123);
    }

    private List<String> privateSchemas(JdbcTemplate jdbc, Path directory) throws Exception {
        Path identity = directory.resolve("data/install_identity.txt");
        if (!Files.exists(identity)) return List.of();
        return jdbc.queryForList("SELECT nspname FROM pg_namespace WHERE obj_description(oid,'pg_namespace') LIKE ?",
                String.class, "pixivdownload/private/" + Files.readString(identity).trim() + "/%");
    }

    private void cleanupPrivate(JdbcTemplate jdbc, Path directory) throws Exception {
        for (String schema : privateSchemas(jdbc, directory)) {
            assertThat(schema).matches("pixiv_owner_[0-9a-f]{32}");
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            jdbc.execute("DROP ROLE " + schema);
        }
    }

    private Path postgres(String name, PostgresTestDatabase database) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        var properties = database.properties();
        Files.writeString(Files.createDirectory(directory.resolve("config")).resolve("config.yaml"),
                "database.provider: database-postgresql\ndatabase.engine: postgresql\ndatabase.jdbc-url: "
                        + json.writeValueAsString(properties.getJdbcUrl()) + "\ndatabase.username: "
                        + json.writeValueAsString(properties.getUsername()) + "\ndatabase.password: "
                        + json.writeValueAsString(properties.getPassword()) + "\nserver.port: 8222\n");
        return directory;
    }

    private Path sqlite(String name, String tag, long imported) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        Path main = directory.resolve("main.db");
        try (var connection = DatabaseProviderTestFixtures.openConnection(main); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE tags(tag_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,translated_name TEXT)");
            try (var insert = connection.prepareStatement("INSERT INTO tags VALUES(?,?,NULL)")) {
                insert.setLong(1, ID); insert.setString(2, tag); insert.executeUpdate();
            }
        }
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("install_identity.txt"), UUID.randomUUID().toString());
        Path file = Files.createDirectory(data.resolve(OWNER)).resolve("plugin.db");
        try (var connection = DatabaseProviderTestFixtures.openConnection(file); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE imported_works(work_type TEXT NOT NULL,work_id INTEGER NOT NULL,imported_at INTEGER NOT NULL,PRIMARY KEY(work_type,work_id))");
            sql.execute("INSERT INTO imported_works VALUES('NOVEL'," + ID + "," + imported + ")");
        }
        Files.writeString(Files.createDirectory(directory.resolve("config")).resolve("config.yaml"),
                "database.provider: database-sqlite\ndatabase.engine: sqlite\ndatabase.jdbc-url: "
                        + json.writeValueAsString("jdbc:sqlite:" + main) + "\nserver.port: 8111\n");
        return directory;
    }

    private void assertSqlite(Path file, String query, String expected) throws Exception {
        try (var connection = DatabaseProviderTestFixtures.openExisting(file); var sql = connection.createStatement(); var row = sql.executeQuery(query)) {
            assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo(expected);
        }
    }

    private void restore(Path directory, Path archive) throws Exception {
        run(directory, "prepare", archive);
        run(directory, "run", archive);
        assertThat(job(directory).path("status").asText()).as("database restore status").isEqualTo("SUCCEEDED");
    }

    private JsonNode job(Path directory) throws Exception {
        return json.readTree(directory.resolve("data/environment-transfers/pending.json").toFile());
    }

    private void run(Path directory, String action, Path archive) throws Exception {
        int result = MigrationProcessHarness.run(directory, DatabaseTransferTestEntry.class, action, action,
                archive.toString(), directory.resolve("preview.json").toString());
        assertThat(result).as(Files.readString(directory.resolve(action + ".log"))).isZero();
    }
}
