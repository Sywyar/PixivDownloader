package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DisplayName("桌面主数据库草稿与运行目标")
class DatabaseSettingsServiceTest {
    @TempDir Path directory;

    private TestDesktopConfigFile file() { return new TestDesktopConfigFile(directory.resolve("config.yaml")); }

    private DatabaseSettingsService service(DatabaseTarget active) {
        return new DatabaseSettingsService(file(), () -> DatabaseTarget.configurationEnvironment(
                "--spring.config.location=" + directory.resolve("config.yaml").toUri()), () -> Optional.ofNullable(active), DatabaseProviderTestFixtures::providers);
    }

    private Map<String, String> postgres() {
        return Map.of("database.engine", "postgresql", "database.jdbc-url", "jdbc:postgresql://127.0.0.1:1/fixture",
                "database.username", "fixture", "database.password", "test secret");
    }

    @Test
    @DisplayName("未核验旧私库阻止保存但仍能检查草稿连接，原配置保留")
    void pendingPrivateMigrationDoesNotBlockDraftCheck() throws Exception {
        String key = top.sywyar.pixivdownload.config.RuntimeFiles.DATA_DIR_PROPERTY;
        String previous = System.getProperty(key);
        try {
            Path data = Files.createDirectories(directory.resolve("data"));
            System.setProperty(key, data.toString());
            Files.writeString(Files.createDirectories(data.resolve("fixture-owner")).resolve("plugin.db"), "existing");
            file().writeAll(Map.of("database.provider", "fixture-file", "database.engine", "sqlite"));
            byte[] before = Files.readAllBytes(directory.resolve("config.yaml"));
            var changes = new java.util.HashMap<>(postgres());
            changes.put("database.provider", "fixture-server");
            assertThat(service(null).check(changes).outcome()).isEqualTo(DesktopDatabaseSettings.Outcome.FAILED);
            assertThatThrownBy(() -> service(null).prepare(changes)).isInstanceOf(java.io.IOException.class);
            assertThat(Files.readAllBytes(directory.resolve("config.yaml"))).isEqualTo(before);
            assertThat(data.resolve("fixture-owner/plugin.db")).hasContent("existing");
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @Test
    @DisplayName("保存精确插件 owner，已选插件缺失时不按方言改投其它实现")
    void pinsSavedOwnerAndRejectsMissingProvider() throws Exception {
        file().writeAll(postgres());
        var prepared = service(null).prepare(Map.of("database.provider", "fixture-file"));
        assertThat(prepared).containsEntry("database.provider", "fixture-file").containsEntry("database.engine", "sqlite")
                .containsEntry("database.password", "").containsEntry("database.username", "");
        file().writeAll(prepared);
        file().writeAll(Map.of("database.provider", "removed-fixture"));
        var settings = service(null);
        assertThat(settings.state().nextTarget()).isEmpty();
        assertThatThrownBy(() -> settings.check(Map.of())).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> settings.prepare(Map.of("database.engine", "sqlite")))
                .isInstanceOf(java.io.IOException.class);
        assertThat(file().readAll(java.util.Set.of("database.provider")))
                .containsEntry("database.provider", "removed-fixture");
    }

    @Test
    @DisplayName("未创建 SQLite 只报告尚未创建，不创建目录或文件")
    void doesNotCreateMissingSqlite() throws Exception {
        Path database = directory.resolve("missing/fixture.db");
        file().writeAll(Map.of("database.engine", "sqlite", "database.jdbc-url", "jdbc:sqlite:" + database));
        byte[] before = Files.readAllBytes(directory.resolve("config.yaml"));
        var result = service(null).check(Map.of());
        assertThat(result.outcome()).isEqualTo(DesktopDatabaseSettings.Outcome.NOT_CREATED);
        assertThat(database.getParent()).doesNotExist();
        assertThat(Files.readAllBytes(directory.resolve("config.yaml"))).isEqualTo(before);
    }

    @Test
    @DisplayName("检查真实 SQLite 不建业务表、不修改 journal mode 或已有数据")
    void checksExistingSqliteWithoutChangingIt() throws Exception {
        Path database = directory.resolve("fixture.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE fixture(value TEXT)");
            statement.execute("INSERT INTO fixture VALUES ('保留 🌸')");
        }
        file().writeAll(Map.of("database.engine", "sqlite", "database.jdbc-url", "jdbc:sqlite:" + database));
        byte[] before = Files.readAllBytes(database);
        assertThat(service(null).check(Map.of()).outcome()).isEqualTo(DesktopDatabaseSettings.Outcome.CONNECTED);
        assertThat(Files.readAllBytes(database)).isEqualTo(before);
        assertThat(directory.resolve("fixture.db-wal")).doesNotExist();
    }

    @Test
    @DisplayName("密码键缺席保留原值，显式清除与替换通过同一配置事务")
    void preservesAndClearsPassword() throws Exception {
        file().writeAll(postgres());
        var settings = service(null);
        file().writeAll(settings.prepare(Map.of("database.username", "changed")));
        assertThat(file().readAll(postgres().keySet())).containsEntry("database.password", "test secret");
        file().writeAll(settings.prepare(Map.of("database.password", "")));
        assertThat(file().readAll(postgres().keySet())).containsEntry("database.password", "");
        file().writeAll(settings.prepare(Map.of("database.password", " leading and trailing ")));
        assertThat(file().readAll(postgres().keySet())).containsEntry("database.password", " leading and trailing ");
    }

    @Test
    @DisplayName("非法草稿拒绝且保留原配置，诊断不包含密码")
    void rejectsInvalidWithoutWriting() throws Exception {
        file().writeAll(postgres());
        byte[] before = Files.readAllBytes(directory.resolve("config.yaml"));
        for (Map<String, String> updates : java.util.List.of(
                Map.of("database.engine", "mysql"),
                Map.of("database.username", ""),
                Map.of("database.jdbc-url", "jdbc:postgresql://localhost/db?password=hidden-password"))) {
            assertThatThrownBy(() -> service(null).prepare(updates)).isInstanceOf(java.io.IOException.class)
                    .hasMessageNotContaining("hidden-password");
        }
        assertThat(Files.readAllBytes(directory.resolve("config.yaml"))).isEqualTo(before);
    }

    @Test
    @DisplayName("切回 SQLite 清除隐藏的远程字段，草稿检查仍不保存")
    void switchingBackClearsRemoteConfiguration() throws Exception {
        file().writeAll(postgres());
        var prepared = service(null).prepare(Map.of("database.engine", "sqlite"));
        assertThat(prepared).containsEntry("database.jdbc-url", "").containsEntry("database.username", "")
                .containsEntry("database.password", "");
        assertThat(file().readAll(postgres().keySet())).containsEntry("database.engine", "postgresql");
        String sqlite = "jdbc:sqlite:" + directory.resolve("selected.db");
        assertThat(service(null).prepare(Map.of("database.engine", "sqlite", "database.jdbc-url", sqlite)))
                .containsEntry("database.jdbc-url", sqlite);
        file().writeAll(Map.of("database.engine", "sqlite", "database.jdbc-url", sqlite,
                "database.username", "", "database.password", ""));
        var unsavedRemoteDraft = service(null).prepare(Map.of("database.jdbc-url", "jdbc:postgresql://fixture/db",
                "database.username", "draft", "database.password", "draft-secret"));
        assertThat(unsavedRemoteDraft).containsEntry("database.jdbc-url", "").containsEntry("database.username", "")
                .containsEntry("database.password", "");
    }

    @Test
    @DisplayName("无响应服务器使用草稿中更短的超时，并拒绝同时重复检查")
    void boundsWaitingAndDuplicateChecks() throws Exception {
        file().writeAll(postgres());
        var settings = service(null);
        try (var server = new java.net.ServerSocket(0)) {
            server.setSoTimeout(3000);
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                long started = System.nanoTime();
                var pending = executor.submit(() -> settings.check(Map.of("database.jdbc-url",
                        "jdbc:postgresql://127.0.0.1:" + server.getLocalPort() + "/fixture?socketTimeout=1&connectTimeout=1")));
                try (var ignored = server.accept()) {
                    assertThat(settings.check(Map.of()).outcome()).isEqualTo(DesktopDatabaseSettings.Outcome.BUSY);
                    assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS).outcome())
                            .isEqualTo(DesktopDatabaseSettings.Outcome.FAILED);
                    assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
                }
            } finally { executor.shutdownNow(); }
        }
    }

    @Test
    @DisplayName("保存和后端停止不混淆活动目标，Boot 启动参数优先级保留")
    void distinguishesActiveSavedAndOverriddenTargets() throws Exception {
        var active = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(directory.resolve("active.db"));
        file().writeAll(postgres());
        var state = service(active).state();
        assertThat(state.activeTarget()).isEqualTo(active.description());
        assertThat(state.nextTarget()).contains("postgresql:");
        assertThat(state.restartRequired()).isTrue();
        assertThat(state.overriddenKeys()).isEmpty();
        var overridden = new DatabaseSettingsService(file(), () -> DatabaseTarget.configurationEnvironment(
                "--spring.config.location=" + directory.resolve("config.yaml").toUri(),
                "--database.jdbc-url=jdbc:postgresql://override.invalid/other",
                "--database.password=override-secret"), () -> Optional.of(active), DatabaseProviderTestFixtures::providers);
        assertThat(overridden.state().overriddenKeys()).contains("database.jdbc-url", "database.password");
        assertThat(overridden.state().toString()).contains("override.invalid").doesNotContain("override-secret");
        assertThat(service(null).state().activeTarget()).isEmpty();
        assertThat(directory.resolve("active.db")).doesNotExist();
    }

    @Test
    @DisplayName("不可用目标对象保留状态展示，但不阻断连接恢复")
    void unavailableTargetIsNotAnActiveDatabase() throws Exception {
        var active = DatabaseTarget.unavailable();
        var settings = service(active);
        file().writeAll(Map.of("database.provider", "fixture-server", "database.jdbc-url", "invalid"));
        assertThat(settings.hasActiveTarget()).isFalse();
        assertThat(settings.state().activeTarget()).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(settings.state().restartRequired()).isFalse();
        file().writeAll(postgres());
        assertThat(settings.state().nextTarget()).contains("postgresql:");
        assertThat(settings.state().restartRequired()).isFalse();
        assertThat(service(DatabaseProviderTestFixtures.sqlite(directory.resolve("active.db"))).hasActiveTarget()).isTrue();
    }

    @Test
    @DisplayName("不可达目标失败而不回落 SQLite")
    void failsUnreachableTarget() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            int port = socket.getLocalPort();
            socket.close();
            file().writeAll(postgres());
            var result = service(null).check(Map.of("database.jdbc-url", "jdbc:postgresql://127.0.0.1:" + port + "/fixture"));
            assertThat(result.outcome()).isEqualTo(DesktopDatabaseSettings.Outcome.FAILED);
            assertThat(result.sqlState()).startsWith("08");
            assertThat(file().readAll(postgres().keySet())).isEqualTo(postgres());
        }
    }

    @Test
    @DisplayName("环境变量按 Boot 松散绑定覆盖连接字段，密码覆盖只暴露键名")
    void reportsEnvironmentOverridesWithoutSecrets() throws Exception {
        file().writeAll(postgres());
        var environment = DatabaseTarget.configurationEnvironment(
                "--spring.config.location=" + directory.resolve("config.yaml").toUri());
        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "fixtureEnvironment", Map.of("DATABASE_JDBC_URL", "jdbc:postgresql://environment.invalid/fixture",
                        "DATABASE_PASSWORD", "environment-secret")));
        var settings = new DatabaseSettingsService(file(), () -> environment, Optional::empty, DatabaseProviderTestFixtures::providers);
        var state = settings.state();
        assertThat(state.nextTarget()).contains("environment.invalid");
        assertThat(state.overriddenKeys()).contains("database.jdbc-url", "database.password");
        assertThat(state.toString()).doesNotContain("environment-secret");
        assertThat(file().readAll(postgres().keySet())).isEqualTo(postgres());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
    @DisplayName("真实 PostgreSQL 检查释放连接，不建表、不修改运行池，无效身份拒绝")
    void checksPostgresWithoutInitializingOrReplacingPool() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            var properties = database.properties();
            file().writeAll(Map.of("database.engine", "postgresql", "database.jdbc-url", properties.getJdbcUrl(),
                    "database.username", properties.getUsername(), "database.password", properties.getPassword()));
            var target = top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.resolve(properties, "unused");
            var settings = service(target);
            int total = database.pool().getHikariPoolMXBean().getTotalConnections();
            assertThat(settings.check(Map.of()).outcome()).isEqualTo(DesktopDatabaseSettings.Outcome.CONNECTED);
            var failed = settings.check(Map.of("database.password", "invalid-fixture-password", "database.username",
                    "missing_" + java.util.UUID.randomUUID().toString().replace("-", "")));
            assertThat(failed.outcome()).isEqualTo(DesktopDatabaseSettings.Outcome.FAILED);
            assertThat(failed.sqlState()).startsWith("28");
            assertThat(failed.toString()).doesNotContain("invalid-fixture-password");
            if (!properties.getPassword().isEmpty()) assertThat(failed.toString()).doesNotContain(properties.getPassword());
            assertThat(database.pool().getHikariPoolMXBean().getTotalConnections()).isEqualTo(total);
            try (var connection = database.pool().getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
                target.requireConnectionTarget(connection);
            }
            assertThat(settings.state().restartRequired()).isFalse();
        }
    }
}
