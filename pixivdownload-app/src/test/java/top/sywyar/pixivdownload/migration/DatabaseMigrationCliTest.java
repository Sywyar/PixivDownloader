package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.gui.SingleInstanceManager;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("停机迁移真实命令行入口")
class DatabaseMigrationCliTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("独立进程导出声明、迁移旧库并写入可核对的最终报告，重试不覆盖目标")
    @EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    void migratesAndRejectsNonemptyTargetInRealProcess() throws Exception {
        Path source = directory.resolve("old.db");
        try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openConnection(source); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE artworks(artwork_id INTEGER PRIMARY KEY,title TEXT NOT NULL,folder TEXT NOT NULL,"
                    + "count INTEGER NOT NULL,extensions TEXT NOT NULL,time INTEGER NOT NULL UNIQUE)");
            sql.execute("INSERT INTO artworks VALUES(5000000001,'旧库 🌸','{0}/作品',1,'png',1700000000123)");
        }
        Path manifest = directory.resolve("schema.json");
        assertThat(run("export", "--export-schema", manifest.toString())).isZero();
        assertThat(json.readTree(manifest.toFile()).path("sdkVersion").asText()).isEqualTo(SdkVersion.VERSION);
        try (var database = new PostgresTestDatabase()) {
            Path config = targetConfig(database);
            Path report = directory.resolve("migration.json");
            assertThat(run("migrate", "--source-config", sourceConfig(source).toString(), "--target-config", config.toString(),
                    "--report", report.toString())).isZero();
            var result = json.readTree(report.toFile());
            assertThat(result.path("status").asText()).isEqualTo("DATA_VERIFIED");
            assertThat(result.path("stores").path("main").path("snapshot").path("verification").path("tables").path("artworks").path("rows").asLong()).isOne();
            var jdbc = new JdbcTemplate(database.pool());
            assertThat(jdbc.queryForObject("SELECT title FROM artworks", String.class)).isEqualTo("旧库 🌸");
            jdbc.update("INSERT INTO tags(name) VALUES('post-migration')");
            Path retryReport = directory.resolve("retry.json");
            assertThat(run("retry", "--source-config", sourceConfig(source).toString(), "--target-config", config.toString(),
                    "--report", retryReport.toString())).isEqualTo(2);
            assertThat(json.readTree(retryReport.toFile()).path("status").asText()).isEqualTo("FAILED_TARGET_NOT_VERIFIED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Integer.class)).isOne();
            assertThat(directory.resolve("data/pixiv_download.db")).doesNotExist();
        }
    }

    @Test
    @DisplayName("真实 CLI 迁移已适配插件的私库，记录分 owner 核验结果并保留源数据")
    @EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    void migratesDeclaredPrivateStorage() throws Exception {
        String owner = "pixiv-batch-downloader-import";
        String installation = java.util.UUID.randomUUID().toString();
        Path data = Files.createDirectories(directory.resolve("data"));
        Files.writeString(data.resolve("install_identity.txt"), installation, StandardCharsets.UTF_8);
        Path source = directory.resolve("main.db");
        try (var ignored = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openConnection(source)) { }
        Path privateSource = Files.createDirectories(data.resolve(owner)).resolve("plugin.db");
        try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openConnection(privateSource);
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE imported_works(work_type TEXT NOT NULL,work_id INTEGER NOT NULL,"
                    + "imported_at INTEGER NOT NULL,PRIMARY KEY(work_type,work_id))");
            sql.execute("INSERT INTO imported_works VALUES('NOVEL',9007199254740993,1791600000123)");
        }
        try (var database = new PostgresTestDatabase()) {
            var jdbc = new JdbcTemplate(database.pool());
            try {
                Path report = directory.resolve("private-migration.json");
                assertThat(run("private", "--source-config", sourceConfig(source).toString(), "--target-config", targetConfig(database).toString(),
                        "--report", report.toString())).isZero();
                var result = json.readTree(report.toFile());
                assertThat(result.path("status").asText()).isEqualTo("DATA_VERIFIED");
                assertThat(result.path("stores").path("private:" + owner).path("snapshot").path("verification").path("tables").path("imported_works").path("rows").asLong())
                        .isOne();
                List<String> schemas = privateSchemas(jdbc, installation);
                assertThat(schemas).hasSize(1);
                assertThat(jdbc.queryForObject("SELECT work_id FROM " + schemas.get(0) + ".imported_works", Long.class))
                        .isEqualTo(9007199254740993L);
                assertThat(data.resolve("database-migrations/private/" + owner + "/verified.json")).exists();
                try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openExisting(privateSource);
                     var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT imported_at FROM imported_works")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1791600000123L);
                }
                assertThat(directory.resolve("config/config.yaml")).doesNotExist();
            } finally {
                for (String schema : privateSchemas(jdbc, installation)) {
                    assertThat(schema).matches("pixiv_owner_[0-9a-f]{32}");
                    jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
                    jdbc.execute("DROP ROLE " + schema);
                }
            }
        }
    }

    @Test
    @DisplayName("未知 ledger 方言经真实可信发现与配置入口双向迁移，宿主没有引擎适配")
    void unknownProviderRoundTripsThroughRealCli() throws Exception {
        Path source = directory.resolve("original.db");
        try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openConnection(source);
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE tags(tag_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,translated_name TEXT)");
            sql.execute("INSERT INTO tags VALUES(9007199254740993,'未知格式 🌸',NULL)");
        }
        Path third = directory.resolve("foreign.ledger"), restored = directory.resolve("restored.db");
        Path thirdConfig = directory.resolve("ledger.yaml");
        Files.writeString(thirdConfig, "database.provider: neutral-migration\ndatabase.engine: ledger-v1\ndatabase.jdbc-url: "
                + json.writeValueAsString("ledger:" + third) + "\n", StandardCharsets.UTF_8);
        Path outward = directory.resolve("outward.json");
        assertThat(run("outward", "--source-config", sourceConfig(source).toString(), "--target-config", thirdConfig.toString(),
                "--report", outward.toString())).as("%s; report: %s", Files.readString(directory.resolve("outward.log")),
                        Files.exists(outward) ? json.readTree(outward.toFile()).path("failure").asText() : "absent").isZero();
        assertThat(Files.size(third)).isPositive();
        Path restoredConfig = directory.resolve("restored.yaml");
        Files.writeString(restoredConfig, "database.provider: database-sqlite\ndatabase.jdbc-url: "
                + json.writeValueAsString("jdbc:sqlite:" + restored) + "\n", StandardCharsets.UTF_8);
        Path inward = directory.resolve("inward.json");
        assertThat(run("inward", "--source-config", thirdConfig.toString(), "--target-config", restoredConfig.toString(),
                "--report", inward.toString())).as("%s; report: %s", Files.readString(directory.resolve("inward.log")),
                        Files.exists(inward) ? json.readTree(inward.toFile()).path("failure").asText() : "absent").isZero();
        assertThat(json.readTree(inward.toFile()).path("stores").path("main").path("sourceProvider").asText())
                .isEqualTo("neutral-migration");
        try (var connection = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openExisting(restored);
             var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT tag_id,name,translated_name FROM tags")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(9007199254740993L);
            assertThat(rows.getString(2)).isEqualTo("未知格式 🌸");
            assertThat(rows.getString(3)).isNull();
        }
        Path missing = directory.resolve("missing.yaml");
        Files.writeString(missing, "database.provider: missing-provider\ndatabase.jdbc-url: "
                + json.writeValueAsString("ledger:" + third) + "\n", StandardCharsets.UTF_8);
        assertThat(run("missing-provider", "--source-config", missing.toString(), "--target-config", restoredConfig.toString(),
                "--report", directory.resolve("missing.json").toString())).isEqualTo(2);
        assertThat(json.readTree(directory.resolve("missing.json").toFile()).path("failure").asText())
                .isEqualTo("DATABASE_PROVIDER_UNAVAILABLE");
    }

    private List<String> privateSchemas(JdbcTemplate jdbc, String installation) {
        return jdbc.queryForList("SELECT nspname FROM pg_namespace WHERE obj_description(oid,'pg_namespace') LIKE ?",
                String.class, "pixivdownload/private/" + installation + "/%");
    }

    @Test
    @DisplayName("没有私有 schema 声明时先拒绝，主库和配置均不改动")
    @EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    void rejectsUnsupportedPrivateOwnerBeforeMainCopy() throws Exception {
        Path privateSource = Files.createDirectories(directory.resolve("data/unsupported-owner")).resolve("plugin.db");
        Files.writeString(privateSource, "uninterpreted retained data", StandardCharsets.UTF_8);
        Path source = directory.resolve("main.db");
        try (var ignored = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.openConnection(source)) { }
        try (var database = new PostgresTestDatabase()) {
            Path report = directory.resolve("unsupported.json");
            assertThat(run("unsupported", "--source-config", sourceConfig(source).toString(), "--target-config", targetConfig(database).toString(),
                    "--report", report.toString())).isEqualTo(2);
            assertThat(json.readTree(report.toFile()).path("failure").asText()).isEqualTo("PRIVATE_SCHEMA_MISSING");
            assertThat(new JdbcTemplate(database.pool()).queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema()", Long.class)).isZero();
            assertThat(privateSource).hasContent("uninterpreted retained data");
            assertThat(directory.resolve("config/config.yaml")).doesNotExist();
        }
    }

    @Test
    @DisplayName("已有应用实例持锁时 CLI 拒绝执行且不输出成功报告")
    void refusesHeldInstanceLock() throws Exception {
        String key = "pixivdownload.instance-dir";
        String previous = System.getProperty(key);
        System.setProperty(key, directory.resolve("instance").toString());
        try (var instance = SingleInstanceManager.acquire()) {
            assertThat(instance).isNotNull();
            Path manifest = directory.resolve("blocked.json");
            assertThat(run("locked", "--export-schema", manifest.toString())).isEqualTo(2);
            assertThat(manifest).doesNotExist();
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    private Path sourceConfig(Path source) throws Exception {
        Path config = directory.resolve("source.yaml");
        Files.writeString(config, "database.provider: database-sqlite\ndatabase.jdbc-url: "
                + json.writeValueAsString("jdbc:sqlite:" + source.toAbsolutePath()) + "\n", StandardCharsets.UTF_8);
        return config;
    }

    private Path targetConfig(PostgresTestDatabase database) throws Exception {
        var properties = database.properties();
        Path config = directory.resolve("target.yaml");
        Files.writeString(config, "database.provider: database-postgresql\ndatabase.engine: postgresql\ndatabase.jdbc-url: "
                + json.writeValueAsString(properties.getJdbcUrl()) + "\ndatabase.username: "
                + json.writeValueAsString(properties.getUsername()) + "\ndatabase.password: "
                + json.writeValueAsString(properties.getPassword()) + "\n", StandardCharsets.UTF_8);
        return config;
    }

    private int run(String label, String... arguments) throws Exception {
        return MigrationProcessHarness.run(directory, DatabaseMigration.class, label, arguments);
    }
}
