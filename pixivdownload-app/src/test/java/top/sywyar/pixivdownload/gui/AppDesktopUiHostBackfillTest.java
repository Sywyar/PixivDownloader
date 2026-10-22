package top.sywyar.pixivdownload.gui;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("桌面宿主作品回填数据源")
class AppDesktopUiHostBackfillTest {

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false, false", "true, false", "false, true", "true, true"})
    @DisplayName("目录工具目标校验失败归还宿主连接，清理致命错误仍传播")
    void closesBorrowedConnectionOnFatalTargetFailure(boolean update, boolean cleanupFatal) throws Exception {
        var target = mock(DatabaseTarget.class);
        var connection = mock(Connection.class);
        var pool = mock(DataSource.class);
        var selected = Path.of("database-target");
        Throwable failure = cleanupFatal ? new java.sql.SQLException("controlled target failure")
                : new OutOfMemoryError("controlled target failure");
        var cleanup = new OutOfMemoryError("controlled close failure");
        if (cleanupFatal) org.mockito.Mockito.doThrow(cleanup).when(connection).close();
        when(target.providerOwner()).thenReturn("fixture-provider");
        when(target.displayName()).thenReturn(selected.toString());
        when(pool.getConnection()).thenReturn(connection);
        org.mockito.Mockito.doThrow(failure).when(target).requireConnectionTarget(connection);
        var host = new AppDesktopUiHost(0, mock(DesktopUiHost.ConfigFile.class), () -> pool, null, target);
        try (var backend = org.mockito.Mockito.mockStatic(BackendLifecycleManager.class)) {
            backend.when(BackendLifecycleManager::isRunning).thenReturn(true);
            backend.when(() -> BackendLifecycleManager.requiredBean(DatabaseTarget.class)).thenReturn(target);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                if (update) host.updateArtworkFolder(selected, 1, false, "new-folder");
                else host.checkArtworkFolders(selected);
            }).isSameAs(cleanupFatal ? cleanup : failure);
        }
        if (cleanupFatal) assertThat(cleanup.getSuppressed()).containsExactly(failure);
        verify(connection).close();
    }

    @Test
    @DisplayName("启动构造器把可信插件会话绑定到迁移设置服务")
    void startupConstructorBindsMigration(@TempDir Path directory) throws Exception {
        var properties = java.util.List.of("pixivdownload.config-dir", "pixivdownload.state-dir", "pixivdownload.data-dir");
        var previous = new java.util.HashMap<String, String>();
        try {
            for (String property : properties) {
                previous.put(property, System.getProperty(property));
                System.setProperty(property, directory.resolve(property).toString());
            }
            var config = top.sywyar.pixivdownload.config.RuntimeFiles.resolveConfigYamlPath();
            java.nio.file.Files.writeString(config, "database.provider: database-sqlite\n", java.nio.charset.StandardCharsets.UTF_8);
            var session = mock(top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession.class);
            when(session.databaseProviders()).thenAnswer(ignored -> top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.providers());
            var host = new AppDesktopUiHost(0, new String[0], session, null);
            var plan = host.databaseSettings().orElseThrow().previewMigration(
                    java.util.Map.of("database.provider", "unavailable-provider"));
            assertThat(plan.ready()).isFalse();
            assertThat(plan.failure()).isNotBlank();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
        }
    }

    @Test
    @DisplayName("无效主库配置不阻断桌面修正入口，工具不能退回 SQLite")
    void keepsConfigurationRecoveryAvailable(@TempDir Path tempDir) throws Exception {
        var file = new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(tempDir.resolve("config.yaml")).asConfigFile();
        file.writeAll(java.util.Map.of("database.engine", "postgresql", "database.jdbc-url", "invalid"));
        String previous = System.getProperty("spring.config.location");
        try {
            System.setProperty("spring.config.location", tempDir.resolve("config.yaml").toUri().toString());
            var session = mock(top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession.class);
            when(session.databaseProviders()).thenAnswer(ignored -> top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.providers());
            var host = new AppDesktopUiHost(0, file, () -> mock(DataSource.class), session, null);
            assertThat(host.defaultBackfillOptions().dbPath()).isEmpty();
            assertThat(host.supportsDatabaseFileSelection()).isFalse();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> host.resolveDatabasePath(tempDir.toString()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("DATABASE_UNAVAILABLE");
            var settings = host.databaseSettings().orElseThrow();
            assertThat(settings.state().nextTarget()).isEmpty();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> host.checkArtworkFolders(tempDir.resolve("wrong.db")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("DATABASE_UNAVAILABLE");
            var corrected = settings.check(java.util.Map.of("database.engine", "sqlite"));
            assertThat(corrected.target()).contains("sqlite:");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.prepare(java.util.Map.of("database.engine", "sqlite")))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(settings.state().nextTarget()).isEmpty();
            assertThat(host.supportsDatabaseFileSelection()).isFalse();
            assertThat(tempDir.resolve("wrong.db")).doesNotExist();
        } finally {
            if (previous == null) System.clearProperty("spring.config.location");
            else System.setProperty("spring.config.location", previous);
        }
    }

    @Test
    @DisplayName("回填默认参数读取当前保存的代理并保留关闭状态")
    void backfillDefaultsFollowSavedProxy(@TempDir Path tempDir) throws Exception {
        var file = new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(tempDir.resolve("config.yaml")).asConfigFile();
        file.writeAll(java.util.Map.of("proxy.enabled", "true", "proxy.host", "192.0.2.55", "proxy.port", "17891"));
        var host = new AppDesktopUiHost(0, file);
        var first = host.defaultBackfillOptions();
        assertThat(first.useProxy()).isTrue();
        assertThat(first.proxyHost()).isEqualTo("192.0.2.55");
        assertThat(first.proxyPort()).isEqualTo(17891);

        file.writeAll(java.util.Map.of("proxy.enabled", "false", "proxy.port", "17892"));
        var changed = host.defaultBackfillOptions();
        assertThat(changed.useProxy()).isFalse();
        assertThat(changed.proxyPort()).isEqualTo(17892);
        assertThat(file.read("proxy.enabled")).isEqualTo("false");
        var startup = StartupSchemaBackfill.options(host);
        assertThat(startup.useProxy()).isFalse();
        assertThat(startup.proxyHost()).isEqualTo("192.0.2.55");
        assertThat(startup.proxyPort()).isEqualTo(17892);
    }

    @Test
    @DisplayName("代理配置读取失败时不回退到另一条网络连接")
    void unreadableProxyConfigurationRejectsBackfillDefaults() throws Exception {
        var file = mock(DesktopUiHost.ConfigFile.class);
        when(file.readAll(org.mockito.ArgumentMatchers.anyCollection())).thenThrow(new java.io.IOException("fixture"));
        var host = new AppDesktopUiHost(0, file);
        org.assertj.core.api.Assertions.assertThatThrownBy(host::defaultBackfillOptions)
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    @DisplayName("候选查询借用注入的宿主连接池")
    void candidateCountBorrowsInjectedHostPool(@TempDir Path tempDir) throws Exception {
        Path databasePath = tempDir.resolve("host.db");
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:sqlite:" + databasePath);

        try (HikariDataSource pool = new HikariDataSource(hikari)) {
            try (Connection connection = pool.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, title TEXT NOT NULL,"
                        + " folder TEXT NOT NULL, count INTEGER NOT NULL, extensions TEXT NOT NULL, time INTEGER NOT NULL UNIQUE)");
            }
            DataSource observedPool = mock(DataSource.class);
            when(observedPool.getConnection()).thenAnswer(ignored -> pool.getConnection());
            AppDesktopUiHost host = new AppDesktopUiHost(
                    0,
                    mock(DesktopUiHost.ConfigFile.class),
                    () -> observedPool,
                    null,
                    top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.sqlite(databasePath)
            );

            assertThat(host.resolveDatabasePath(tempDir.resolve("other-root").toString())).isEqualTo(databasePath);
            assertThat(host.countBackfillCandidates(new DesktopUiHost.BackfillOptions(
                    databasePath.toString(),
                    "127.0.0.1",
                    7890,
                    false,
                    0,
                    0,
                    true
            ))).isZero();
            verify(observedPool).getConnection();
        }
    }
}
