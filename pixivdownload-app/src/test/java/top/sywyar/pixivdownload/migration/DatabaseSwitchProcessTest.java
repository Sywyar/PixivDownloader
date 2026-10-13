package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.DatabaseSwitchTestEntry;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("设置页迁移任务跨进程交接")
class DatabaseSwitchProcessTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private Path source, target, config, draft;
    @BeforeEach void setup() throws Exception {
        source = directory.resolve("source.db"); target = directory.resolve("target.ledger");
        try (var connection = DatabaseProviderTestFixtures.openConnection(source); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tags(tag_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,translated_name TEXT)");
            statement.execute("INSERT INTO tags VALUES(9007199254740993,'迁移 🌸',NULL)");
        }
        config = Files.createDirectories(directory.resolve("config")).resolve("config.yaml");
        Files.writeString(config, "database.provider: database-sqlite\ndatabase.engine: sqlite\ndatabase.jdbc-url: "
                + json.writeValueAsString("jdbc:sqlite:" + source) + "\n");
        draft = directory.resolve("draft.json");
        Files.write(draft, json.writeValueAsBytes(Map.of("database.provider", "neutral-migration", "database.jdbc-url", "ledger:" + target)));
    }
    private int run(String action) throws Exception {
        return MigrationProcessHarness.run(directory, DatabaseSwitchTestEntry.class, action, action,
                draft.toString(), directory.resolve("plan.json").toString());
    }
    private String status() throws Exception { return json.readTree(directory.resolve("data/database-migrations/switch.json").toFile()).path("status").asText(); }
    @Test @DisplayName("只读检查不建目标，确认后仍保留源配置，替代进程核验成功才切换")
    void confirmsThenMigratesBeforeSwitching() throws Exception {
        byte[] before = Files.readAllBytes(config);
        assertThat(run("preview")).isZero();
        assertThat(json.readTree(directory.resolve("plan.json").toFile()).path("ready").asBoolean()).isTrue();
        assertThat(target).doesNotExist();
        assertThat(directory.resolve("data/database-migrations")).doesNotExist();
        assertThat(run("stage")).isZero();
        assertThat(status()).isEqualTo("PENDING");
        assertThat(Files.readAllBytes(config)).isEqualTo(before);
        assertThat(run("run")).isZero();
        assertThat(status()).isEqualTo("SUCCEEDED");
        assertThat(target).isRegularFile();
        assertThat(Files.readString(config)).contains("neutral-migration");
        assertThat(source).isRegularFile();
    }
    @Test @DisplayName("目标在确认后变为非空，启动拒绝迁移并继续保留源配置")
    void targetChangedAfterConfirmationKeepsSource() throws Exception {
        byte[] before = Files.readAllBytes(config);
        assertThat(run("stage")).isZero();
        Files.writeString(target, "existing target");
        assertThat(run("run")).isZero();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(Files.readAllBytes(config)).isEqualTo(before);
        assertThat(target).hasContent("existing target");
    }
    @Test @DisplayName("无库恢复明确区分连接修正，确认前保留无效配置且不迁移数据")
    void repairsInvalidConfigurationWithoutMigratingUnknownSource() throws Exception {
        Files.writeString(config, "database.provider: database-postgresql\ndatabase.engine: postgresql\ndatabase.jdbc-url: invalid\n");
        byte[] before = Files.readAllBytes(config);
        byte[] database = Files.readAllBytes(source);
        Files.write(draft, json.writeValueAsBytes(Map.of("database.provider", "database-sqlite",
                "database.jdbc-url", "jdbc:sqlite:" + source)));
        assertThat(run("preview")).isZero();
        var plan = json.readTree(directory.resolve("plan.json").toFile());
        assertThat(plan.path("ready").asBoolean()).as("preflight: %s", plan.path("failure").asText()).isTrue();
        assertThat(plan.path("stores").get(0).path("phase").asText()).isEqualTo("CONNECTION_REPAIR");
        assertThat(Files.readAllBytes(config)).isEqualTo(before);
        assertThat(Files.readAllBytes(source)).isEqualTo(database);
        assertThat(run("stage")).isZero();
        assertThat(Files.readAllBytes(config)).isEqualTo(before);
        assertThat(run("run")).isZero();
        assertThat(status()).isEqualTo("SUCCEEDED");
        assertThat(Files.readString(config)).contains("database-sqlite").doesNotContain("invalid");
        assertThat(Files.readAllBytes(source)).isEqualTo(database);
        assertThat(target).doesNotExist();
    }
    @Test @DisplayName("计划确认前配置变化则拒绝，不留下待执行任务")
    void stalePlanDoesNotSchedule() throws Exception {
        assertThat(run("stale")).isNotZero();
        assertThat(directory.resolve("data/database-migrations/switch.json")).doesNotExist();
        assertThat(target).doesNotExist();
    }
    @Test @DisplayName("重启请求失败不切换配置，失败原因可在设置页读取")
    void failedRestartKeepsSource() throws Exception {
        byte[] before = Files.readAllBytes(config);
        assertThat(run("restart-fails")).isNotZero();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(Files.readAllBytes(config)).isEqualTo(before);
        assertThat(target).doesNotExist();
    }

    @Test @DisplayName("主私库迁入真实 PostgreSQL，运行期增量与清理规则可迁回旧 SQLite，旧数据保留备份")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    void switchesMainAndPrivateStoresToPostgres() throws Exception {
        String owner = "pixiv-batch-downloader-import";
        String installation = UUID.randomUUID().toString();
        Path data = Files.createDirectories(directory.resolve("data"));
        Files.writeString(data.resolve("install_identity.txt"), installation);
        Path privateFile = Files.createDirectories(data.resolve(owner)).resolve("plugin.db");
        try (var connection = DatabaseProviderTestFixtures.openConnection(privateFile); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE imported_works(work_type TEXT NOT NULL,work_id INTEGER NOT NULL,imported_at INTEGER NOT NULL,PRIMARY KEY(work_type,work_id))");
            sql.execute("INSERT INTO imported_works VALUES('NOVEL',9007199254740993,1791600000123)");
        }
        try (var database = new top.sywyar.pixivdownload.core.db.PostgresTestDatabase()) {
            var properties = database.properties();
            Files.write(draft, json.writeValueAsBytes(Map.of("database.provider", "database-postgresql",
                    "database.jdbc-url", properties.getJdbcUrl(), "database.username", properties.getUsername(),
                    "database.password", properties.getPassword())));
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(database.pool());
            try {
                assertThat(run("preview")).isZero();
                var plan = json.readTree(directory.resolve("plan.json").toFile());
                assertThat(plan.path("ready").asBoolean()).as("preflight: %s", plan.path("failure").asText()).isTrue();
                assertThat(plan.path("stores").size()).isEqualTo(2);
                assertThat(directory.resolve("config/credentials/" + owner + ".properties")).doesNotExist();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema()", Integer.class)).isZero();
                assertThat(run("stage")).isZero();
                assertThat(run("run")).isZero();
                var job = json.readTree(data.resolve("database-migrations/switch.json").toFile());
                assertThat(job.path("status").asText()).as("migration: %s", job.path("failure").asText()).isEqualTo("SUCCEEDED");
                assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE tag_id=9007199254740993", String.class)).isEqualTo("迁移 🌸");
                assertThat(data.resolve("database-migrations/private/" + owner + "/verified.json")).isRegularFile();
                var privateSchemas = jdbc.queryForList("SELECT nspname FROM pg_namespace WHERE obj_description(oid,'pg_namespace') LIKE ?",
                        String.class, "pixivdownload/private/" + installation + "/%");
                assertThat(privateSchemas).hasSize(1);
                assertThat(jdbc.queryForObject("SELECT work_id FROM " + privateSchemas.get(0) + ".imported_works", Long.class)).isEqualTo(9007199254740993L);
                jdbc.update("UPDATE tags SET name='server update 🌸' WHERE tag_id=9007199254740993");
                jdbc.update("UPDATE " + privateSchemas.get(0) + ".imported_works SET imported_at=1791600000999");
                var endpoint = DatabaseProviderTestFixtures.resolve(properties, "unused").migrationEndpoint();
                var registry = top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry.forBuiltInPlugins();
                var logical = top.sywyar.pixivdownload.core.db.schema.CoreMigrationSchema.create(registry, true);
                try (var connection = database.pool().getConnection(); var session = endpoint.target().storageSession(connection, logical)) {
                    session.initializeCleanup(new top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.Cleanup(
                            "runtime_cleanup", "novels", "novel_id", "deleted", 1,
                            List.of(new top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.Dependent("novel_tags", "novel_id"))));
                }
                byte[] oldMain = Files.readAllBytes(source), oldPrivate = Files.readAllBytes(privateFile), activeConfig = Files.readAllBytes(config);
                Files.write(draft, json.writeValueAsBytes(Map.of("database.provider", "database-sqlite", "database.jdbc-url", "jdbc:sqlite:" + source)));
                assertThat(run("preview")).isZero();
                var reverse = json.readTree(directory.resolve("plan.json").toFile());
                assertThat(reverse.path("ready").asBoolean()).as("reverse: %s", reverse.path("failure").asText()).isTrue();
                assertThat(reverse.path("stores")).hasSize(2);
                for (var store : reverse.path("stores")) assertThat(store.path("phase").asText()).isEqualTo("REPLACE_TARGET");
                assertThat(Files.readAllBytes(source)).isEqualTo(oldMain);
                assertThat(Files.readAllBytes(privateFile)).isEqualTo(oldPrivate);
                assertThat(run("stage")).isZero();
                assertThat(Files.readAllBytes(config)).isEqualTo(activeConfig);
                assertThat(run("run")).isZero();
                job = json.readTree(data.resolve("database-migrations/switch.json").toFile());
                assertThat(job.path("status").asText()).as("reverse migration: %s", job.path("failure").asText()).isEqualTo("SUCCEEDED");
                assertThat(Files.readString(config)).contains("database-sqlite");
                try (var connection = DatabaseProviderTestFixtures.openExisting(source); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT name FROM tags WHERE tag_id=9007199254740993")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("server update 🌸");
                }
                try (var connection = DatabaseProviderTestFixtures.openExisting(privateFile); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT imported_at FROM imported_works")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1791600000999L);
                }
                var mainBackup = Path.of(java.net.URI.create(job.path("replacements").path("main").path("backup").asText()));
                var privateBackup = Path.of(java.net.URI.create(job.path("replacements").path("private:" + owner).path("backup").asText()));
                assertThat(mainBackup).isNotEqualTo(privateBackup);
                try (var connection = DatabaseProviderTestFixtures.openExisting(mainBackup); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT name FROM tags WHERE tag_id=9007199254740993")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("迁移 🌸");
                }
                try (var connection = DatabaseProviderTestFixtures.openExisting(privateBackup); var sql = connection.createStatement();
                     var rows = sql.executeQuery("SELECT imported_at FROM imported_works")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1791600000123L);
                }
                assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE tag_id=9007199254740993", String.class)).isEqualTo("server update 🌸");
            } finally {
                for (String schema : jdbc.queryForList("SELECT nspname FROM pg_namespace WHERE obj_description(oid,'pg_namespace') LIKE ?",
                        String.class, "pixivdownload/private/" + installation + "/%")) {
                    assertThat(schema).matches("pixiv_owner_[0-9a-f]{32}");
                    jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
                    jdbc.execute("DROP ROLE " + schema);
                }
            }
        }
    }

    @Test @DisplayName("源凭据失效时允许更正同一数据库连接，不迁移或初始化数据")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    void repairsCredentialsWithoutUsingBrokenSourceConnection() throws Exception {
        try (var database = new top.sywyar.pixivdownload.core.db.PostgresTestDatabase()) {
            var properties = database.properties();
            Files.writeString(config, "database.provider: database-postgresql\ndatabase.engine: postgresql\ndatabase.jdbc-url: "
                    + json.writeValueAsString(properties.getJdbcUrl()) + "\ndatabase.username: missing_recovery_user\n");
            Files.write(draft, json.writeValueAsBytes(Map.of("database.provider", "database-postgresql",
                    "database.jdbc-url", properties.getJdbcUrl(), "database.username", properties.getUsername(),
                    "database.password", properties.getPassword())));
            assertThat(run("stage")).isZero();
            var plan = json.readTree(directory.resolve("plan.json").toFile());
            assertThat(plan.path("ready").asBoolean()).as("preflight: %s", plan.path("failure").asText()).isTrue();
            assertThat(plan.path("stores").get(0).path("phase").asText()).isEqualTo("SAME_TARGET");
            assertThat(run("run")).isZero();
            assertThat(status()).isEqualTo("SUCCEEDED");
            assertThat(Files.readString(config)).doesNotContain("missing_recovery_user");
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(database.pool());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema()", Integer.class)).isZero();
        }
    }

    @Test @DisplayName("缺少私库声明时仍列出主库和全部 owner，不允许确认")
    void missingPrivateDeclarationRemainsVisible() throws Exception {
        Path unknown = Files.createDirectories(directory.resolve("data/unknown-owner")).resolve("plugin.db");
        try (var connection = DatabaseProviderTestFixtures.openConnection(unknown); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE retained(id INTEGER PRIMARY KEY)");
        }
        assertThat(run("preview")).isZero();
        var plan = json.readTree(directory.resolve("plan.json").toFile());
        assertThat(plan.path("ready").asBoolean()).isFalse();
        assertThat(plan.path("failure").asText()).isEqualTo("PRIVATE_SCHEMA_MISSING");
        assertThat(plan.path("stores")).hasSize(2);
        assertThat(plan.path("stores").get(1).path("owner").asText()).isEqualTo("private:unknown-owner");
        assertThat(target).doesNotExist();
    }

    @Test @DisplayName("已提交目标可重新核验复用，但被删除后不能视为空目标重建")
    void retryVerifiesCommittedTarget() throws Exception {
        byte[] before = Files.readAllBytes(config);
        assertThat(run("stage")).isZero();
        assertThat(run("run")).isZero();
        Files.write(config, before);
        Path journal = directory.resolve("data/database-migrations/switch.json");
        var job = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(journal.toFile());
        job.put("status", "FAILED");
        Files.write(journal, json.writeValueAsBytes(job));
        assertThat(run("preview")).isZero();
        var plan = json.readTree(directory.resolve("plan.json").toFile());
        assertThat(plan.path("ready").asBoolean()).as("preflight: %s", plan.path("failure").asText()).isTrue();
        assertThat(plan.path("stores").get(0).path("phase").asText()).isEqualTo("COMMITTED");
        Files.delete(target);
        assertThat(run("preview")).isZero();
        assertThat(json.readTree(directory.resolve("plan.json").toFile()).path("ready").asBoolean()).isFalse();
        assertThat(target).doesNotExist();
        assertThat(Files.readAllBytes(config)).isEqualTo(before);
    }

    @Test @DisplayName("配置替换后进程中断，重启能确认已提交状态")
    void recoversConfigurationCommit() throws Exception {
        assertThat(run("stage")).isZero();
        assertThat(run("run")).isZero();
        Path journal = directory.resolve("data/database-migrations/switch.json");
        var job = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(journal.toFile());
        job.put("status", "SWITCHING");
        Files.write(journal, json.writeValueAsBytes(job));
        byte[] committed = Files.readAllBytes(config);
        assertThat(run("run")).isZero();
        assertThat(status()).isEqualTo("SUCCEEDED");
        assertThat(Files.readAllBytes(config)).isEqualTo(committed);
    }
    @Test @DisplayName("已确认替换的旧目标发生变化时保留配置和新内容，重新检查后才可重试")
    void replacementRechecksTargetBeforeWriting() throws Exception {
        try (var connection = DatabaseProviderTestFixtures.openConnection(target); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE tags(tag_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,translated_name TEXT)");
            sql.execute("INSERT INTO tags VALUES(7,'old target',NULL)");
        }
        Files.write(draft, json.writeValueAsBytes(Map.of("database.provider", "database-sqlite", "database.jdbc-url", "jdbc:sqlite:" + target)));
        byte[] active = Files.readAllBytes(config);
        assertThat(run("stage")).isZero();
        var plan = json.readTree(directory.resolve("plan.json").toFile());
        assertThat(plan.path("ready").asBoolean()).isTrue();
        assertThat(plan.path("stores").get(0).path("phase").asText()).isEqualTo("REPLACE_TARGET");
        try (var connection = DatabaseProviderTestFixtures.openExisting(target); var sql = connection.createStatement()) {
            sql.execute("UPDATE tags SET name='changed target'");
        }
        assertThat(run("run")).isZero();
        var job = json.readTree(directory.resolve("data/database-migrations/switch.json").toFile());
        assertThat(job.path("status").asText()).isEqualTo("FAILED");
        assertThat(job.path("failure").asText()).isEqualTo("MIGRATION_TARGET_CHANGED");
        assertThat(Files.readAllBytes(config)).isEqualTo(active);
        try (var connection = DatabaseProviderTestFixtures.openExisting(target); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT name FROM tags")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("changed target");
        }
        assertThat(run("stage")).isZero();
        assertThat(run("run")).isZero();
        assertThat(status()).isEqualTo("SUCCEEDED");
    }

    @Test @DisplayName("执行中断后不自动重放，保留源配置和目标数据")
    void interruptedRunRequiresNewConfirmation() throws Exception {
        byte[] before = Files.readAllBytes(config);
        assertThat(run("stage")).isZero();
        Path journal = directory.resolve("data/database-migrations/switch.json");
        var job = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(journal.toFile());
        job.put("status", "RUNNING");
        Files.write(journal, json.writeValueAsBytes(job));
        Files.writeString(target, "partial target");
        assertThat(run("run")).isZero();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(Files.readAllBytes(config)).isEqualTo(before);
        assertThat(target).hasContent("partial target");
    }
}
