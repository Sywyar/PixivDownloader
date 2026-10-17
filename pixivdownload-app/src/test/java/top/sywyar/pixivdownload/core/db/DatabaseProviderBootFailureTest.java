package top.sywyar.pixivdownload.core.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.provider.ProviderDataSource;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderBinding;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLNonTransientConnectionException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("数据库 provider 引导失败不阻断恢复入口")
class DatabaseProviderBootFailureTest {
    @TempDir Path temp;

    @Test
    @DisplayName("未知标识按契约准入，目标投影错误拒绝且不连接")
    void rejectsInvalidProjectionBeforeConnection() throws Exception {
        var endpoint = mock(DatabaseConnectionTarget.class);
        when(endpoint.localFile()).thenReturn(Optional.empty());
        var accepted = config().databaseTarget(session("unknown", endpoint), selectedEnvironment());
        assertThat(accepted.available()).isTrue();
        assertThat(accepted.databaseId()).isEqualTo("unknown");
        when(endpoint.localFile()).thenThrow(new AssertionError("secret-like fixture"));
        assertUnavailable(config().databaseTarget(session("sqlite", endpoint), selectedEnvironment()));
        verify(endpoint, never()).openConnection(any());
    }

    @Test
    @DisplayName("投影的进程级致命错误原样传播")
    void preservesFatalFailure() throws Exception {
        var endpoint = mock(DatabaseConnectionTarget.class);
        var fatal = new OutOfMemoryError("fixture");
        when(endpoint.localFile()).thenThrow(fatal);
        assertThatThrownBy(() -> config().databaseTarget(session("sqlite", endpoint), selectedEnvironment()))
                .isSameAs(fatal);
    }

    @Test
    @DisplayName("首次选择不可写时保持无库并保留原配置路径")
    void failedSelectionSaveLeavesRecoveryAvailable() throws Exception {
        String previous = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        try {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, temp.toString());
            Path blocked = Files.createDirectories(temp.resolve("config.yaml"));
            var endpoint = mock(DatabaseConnectionTarget.class);
            when(endpoint.localFile()).thenReturn(Optional.empty());
            var target = config().databaseTarget(session("sqlite", endpoint), new MockEnvironment());
            assertUnavailable(target);
            assertThat(blocked).isDirectory();
            verify(endpoint, never()).openConnection(any());
        } finally {
            if (previous == null) System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, previous);
        }
    }

    @Test
    @DisplayName("选定 provider 的 start 失败早于建池并进入恢复模式")
    void selectedFeatureFailurePreventsPoolCreation() throws Exception {
        var endpoint = mock(DatabaseConnectionTarget.class);
        when(endpoint.localFile()).thenReturn(Optional.empty());
        var target = config().databaseTarget(session("sqlite", endpoint), selectedEnvironment());
        var plugin = mock(PixivFeaturePlugin.class);
        when(plugin.id()).thenReturn("fixture-database");
        doThrow(new IllegalStateException("secret-like fixture")).when(plugin).start();
        var registry = new PluginRegistry(List.of(plugin));
        var source = config().dataSource(target, registry, DatabaseProviderTestFixtures.session());
        assertThat(source).isInstanceOf(UnavailableDataSource.class);
        assertThat(recovery(source, registry).isActive()).isTrue();
        assertThat(target.available()).isFalse();
        verify(endpoint, never()).openConnection(any());
        assertThat(registry.lifecycleFailuresById().toString()).doesNotContain("secret-like");
    }

    @Test
    @DisplayName("所选 provider 后续贡献失败撤回借连接和业务可用状态")
    void laterLifecycleFailureDeniesDatabaseAccess() throws Exception {
        var endpoint = mock(DatabaseConnectionTarget.class);
        when(endpoint.localFile()).thenReturn(Optional.empty());
        var target = config().databaseTarget(session("sqlite", endpoint), selectedEnvironment());
        var plugin = mock(PixivFeaturePlugin.class);
        when(plugin.id()).thenReturn("fixture-database");
        var registry = new PluginRegistry(List.of(plugin));
        var owner = registry.registeredPlugins().get(0);
        var pool = mock(HikariDataSource.class);
        try (var source = new ProviderDataSource(pool, target, registry, owner)) {
            var recovery = recovery(source, registry);
            assertThat(recovery.isActive()).isFalse();
            registry.recordLifecycleFailure(owner, new IllegalStateException("fixture"));
            assertThat(recovery.isActive()).isTrue();
            assertThatThrownBy(source::getConnection).isInstanceOf(SQLNonTransientConnectionException.class);
            verify(pool, never()).getConnection();
        }
        verify(pool).close();
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    @DisplayName("精确 provider 注销后主私库与借出对象永久失效，事务没有落库")
    void removedProviderRevokesMainAndPrivateLeases() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(temp.resolve("main.db"));
        var registry = DatabaseProviderTestFixtures.registry(target);
        var owner = registry.registeredPlugins().get(0);
        var privateSettings = new top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings(
                "fixture-owner", temp.resolve("private"), "fixture-installation", "fixture-secret");
        try (var source = new ProviderDataSource(target.openPool(2), target, registry, owner);
             var privateDatabase = new top.sywyar.pixivdownload.plugin.storage.AppPluginDatabase(
                     "fixture-owner", target, source, () -> privateSettings);
             var connection = source.getConnection(); var statement = connection.createStatement();
             var privateConnection = privateDatabase.getConnection();
             var privateStatement = privateConnection.createStatement()) {
            statement.execute("CREATE TABLE facts(value INTEGER)");
            privateStatement.execute("CREATE TABLE facts(value INTEGER)");
            connection.setAutoCommit(false);
            privateConnection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO facts VALUES (1)");
            privateStatement.executeUpdate("INSERT INTO facts VALUES (1)");
            try (var rows = statement.executeQuery("SELECT value FROM facts");
                 var privateRows = privateStatement.executeQuery("SELECT value FROM facts")) {
                assertThat(rows.next()).isTrue();
                assertThat(privateRows.next()).isTrue();
                registry.unregister(owner);
                assertThat(registry.lifecycleFailure(owner)).isEmpty();
                assertThat(recovery(source, registry).isActive()).isTrue();
                assertThat(privateDatabase.available()).isFalse();
                assertThatThrownBy(source::getConnection).isInstanceOf(SQLNonTransientConnectionException.class);
                assertThatThrownBy(privateDatabase::getConnection).isInstanceOf(SQLNonTransientConnectionException.class);
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO facts VALUES (2)"))
                        .isInstanceOf(java.sql.SQLException.class).hasMessage("DATABASE_UNAVAILABLE");
                assertThatThrownBy(() -> privateStatement.executeUpdate("INSERT INTO facts VALUES (2)"))
                        .isInstanceOf(java.sql.SQLException.class).hasMessage("DATABASE_UNAVAILABLE");
                assertThatThrownBy(connection::commit).hasMessage("DATABASE_UNAVAILABLE");
                assertThatThrownBy(privateConnection::commit).hasMessage("DATABASE_UNAVAILABLE");
                assertThatThrownBy(rows::next).hasMessage("DATABASE_UNAVAILABLE");
                assertThatThrownBy(privateRows::next).hasMessage("DATABASE_UNAVAILABLE");
            }
            registry.register(owner);
            assertThat(registry.isActiveIdentity(owner)).isTrue();
            assertThat(source.available()).isFalse();
            registry.unregister(owner);
            registry.register(owner.plugin());
            assertThat(registry.registeredPlugins().get(0)).isNotSameAs(owner);
            assertThat(source.available()).isFalse();
            try (var unopened = new top.sywyar.pixivdownload.plugin.storage.AppPluginDatabase(
                    "unopened-owner", target, source, () -> {
                        throw new AssertionError("unavailable database must not create private credentials");
                    })) {
                assertThatThrownBy(unopened::getConnection).isInstanceOf(SQLNonTransientConnectionException.class);
            }
        }
        var inspection = DatabaseProviderTestFixtures.sqlite(temp.resolve("main.db"));
        try (var main = inspection.openConnection(); var ownerConnection = inspection.privateStorage(privateSettings)
                .openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            for (var connection : List.of(main, ownerConnection)) {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM facts")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    @DisplayName("首次复核前同 id 已换代时仍拒绝旧 provider，不能以 id 相同冒充原身份")
    void replacementBeforeAvailabilityCheckStillRevokesTarget() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(temp.resolve("main.db"));
        var registry = DatabaseProviderTestFixtures.registry(target);
        var owner = registry.registeredPlugins().get(0);
        var pool = mock(HikariDataSource.class);
        try (var source = new ProviderDataSource(pool, target, registry, owner)) {
            assertThat(source.available()).isTrue();
            registry.unregister(owner);
            registry.register(owner.plugin());
            assertThat(registry.registeredPlugins().get(0).id()).isEqualTo(owner.id());
            assertThat(source.available()).isFalse();
            assertThatThrownBy(source::getConnection).isInstanceOf(SQLNonTransientConnectionException.class);
            verify(pool, never()).getConnection();
        }
    }

    @Test
    @DisplayName("迁移收据父路径被文件占用时仍能创建恢复数据源且不连接目标")
    void blockedReceiptDirectoryKeepsRecoveryAvailable() throws Exception {
        Path source = Files.createDirectories(temp.resolve("fixture-owner")).resolve("plugin.db");
        Files.writeString(source, "retained private data");
        Path blocked = temp.resolve("database-migrations");
        Files.writeString(blocked, "preserve");
        var target = mock(DatabaseTarget.class);
        when(target.available()).thenReturn(true);
        when(target.databaseId()).thenReturn("postgresql");
        when(target.providerOwner()).thenReturn("fixture-database");
        var plugin = mock(PixivFeaturePlugin.class);
        when(plugin.id()).thenReturn("fixture-database");
        var registry = new PluginRegistry(List.of(plugin));
        try (var paths = mockStatic(RuntimeFiles.class, CALLS_REAL_METHODS)) {
            paths.when(RuntimeFiles::dataDirectory).thenReturn(temp);
            var sourcePool = config().dataSource(target, registry, DatabaseProviderTestFixtures.session());
            assertThat(sourcePool).isInstanceOf(UnavailableDataSource.class);
            assertThat(recovery(sourcePool, registry).isActive()).isTrue();
            assertThat(Files.readString(blocked)).isEqualTo("preserve");
            verify(target, never()).openPool(anyInt());
        }
    }

    private void assertUnavailable(DatabaseTarget target) throws Exception {
        assertThat(target.available()).isFalse();
        var source = config().dataSource(target, new PluginRegistry(List.of()), DatabaseProviderTestFixtures.session());
        assertThatThrownBy(source::getConnection).isInstanceOf(SQLNonTransientConnectionException.class);
    }

    private static RecoveryModeService recovery(javax.sql.DataSource source, PluginRegistry registry) {
        return new RecoveryModeService(new PluginStatusService(registry, PluginInventory.empty(), RequiredPluginPolicy.empty()),
                RequiredPluginPolicy.empty(), source);
    }

    private static DatabaseConfig config() {
        return new DatabaseConfig(new DatabasePoolProperties(), mock(AppMessages.class));
    }

    private static MockEnvironment selectedEnvironment() {
        return new MockEnvironment().withProperty("database.provider", "fixture-database");
    }

    private static PluginBootstrapSession session(String dialect, DatabaseConnectionTarget endpoint) throws Exception {
        var provider = mock(DatabaseProvider.class);
        when(provider.resolve(any())).thenReturn(endpoint);
        var selection = new DatabaseProviderSelection.Result("fixture-database", dialect, Optional.of(provider),
                DatabaseProviderSelection.Failure.NONE);
        var session = mock(PluginBootstrapSession.class);
        when(session.bindDatabase(anyString(), anyString(), any(), any())).thenAnswer(call -> {
            Supplier<DatabaseConnectionSettings> settings = call.getArgument(2);
            UnaryOperator<DatabaseProviderBinding> prepare = call.getArgument(3);
            return DatabaseProviderBinding.resolve(selection, settings, prepare);
        });
        return session;
    }
}
