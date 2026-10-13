package top.sywyar.pixivdownload.plugin.runtime.bootstrap.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection.Failure;

@DisplayName("启动数据库能力选择")
class DatabaseProviderSelectionTest {
    @Test
    @DisplayName("已选插件丢失时不改投同方言或另一引擎")
    void missingSelectionNeverFallsBack() {
        var inventory = inventory(installation("replacement", "sqlite"), installation("remote", "postgresql"));
        var result = select("selected", "sqlite", inventory);
        assertThat(result.available()).isFalse();
        assertThat(result.owner()).isEqualTo("selected");
        assertThat(result.failure()).isEqualTo(Failure.MISSING);
    }

    @Test
    @DisplayName("首次选择只绑定配置方言的唯一可用 provider")
    void initialSelectionIsUnambiguousAndEngineBound() {
        var local = installation("local", "sqlite");
        var remote = installation("remote", "postgresql");
        assertThat(select("", "sqlite", inventory(local, remote)).owner()).isEqualTo("local");
        assertThat(select("", "sqlite", inventory(remote)).failure()).isEqualTo(Failure.NOT_SELECTED);
        assertThat(select("", "sqlite", inventory()).failure()).isEqualTo(Failure.NOT_SELECTED);
        assertThat(select("", "sqlite", inventory(local, installation("another", "sqlite"))).failure())
                .isEqualTo(Failure.AMBIGUOUS);
    }

    @Test
    @DisplayName("禁用与不兼容插件即使另有可用 provider 也不可接管")
    void disabledOrIncompatibleSelectionIsUnavailable() {
        var selected = installation("selected", "sqlite");
        var alternative = installation("alternative", "sqlite");
        var disabled = DatabaseProviderSelection.select("selected", "sqlite", inventory(selected, alternative),
                PluginEnabledSnapshot.ofDisabled(List.of("selected"), List.of()));
        assertThat(disabled.failure()).isEqualTo(Failure.DISABLED);
        var incompatible = new PluginInstallation(selected.descriptor(), PluginStatus.INCOMPATIBLE, null, null);
        assertThat(select("selected", "sqlite", inventory(incompatible, alternative)).failure())
                .isEqualTo(Failure.INCOMPATIBLE);
    }

    @Test
    @DisplayName("拒绝可热切换的数据库插件")
    void requiresFullProcessRestart() {
        var hot = installation("hot", new Provider("hot", "sqlite"), PluginLifecyclePolicy.HOT_RELOAD);
        assertThat(select("hot", "sqlite", inventory(hot)).failure()).isEqualTo(Failure.INVALID_LIFECYCLE);
    }

    @Test
    @DisplayName("普通声明故障返回不可用且进程级错误原样传播")
    void providerFailureDoesNotLeakOrFallback() {
        var broken = new Provider("broken", "sqlite") {
            @Override public String databaseId() { throw new IllegalStateException("credential-like text"); }
        };
        assertThat(select("broken", "sqlite", inventory(installation("broken", broken,
                PluginLifecyclePolicy.PROCESS_RESTART))).failure()).isEqualTo(Failure.FAILED);
        var fatal = new Provider("fatal", "sqlite") {
            @Override public String databaseId() { throw new OutOfMemoryError("fixture"); }
        };
        assertThatThrownBy(() -> select("fatal", "sqlite", inventory(installation("fatal", fatal,
                PluginLifecyclePolicy.PROCESS_RESTART)))).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("拒绝重复或空连接字段声明，不回退到其它提供者")
    void rejectsInvalidConnectionFields() {
        for (boolean duplicate : List.of(false, true)) {
            var provider = new Provider("invalid", "third") {
                @Override public java.util.List<top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionField> connectionFields() {
                    var field = super.connectionFields().get(0);
                    return duplicate ? List.of(field, field) : null;
                }
            };
            assertThat(select("invalid", "third", inventory(installation("invalid", provider,
                    PluginLifecyclePolicy.PROCESS_RESTART))).failure()).isEqualTo(Failure.FAILED);
        }
    }

    private static DatabaseProviderSelection.Result select(String owner, String engine, PluginInventory inventory) {
        return DatabaseProviderSelection.select(owner, engine, inventory, PluginEnabledSnapshot.empty());
    }

    private static PluginInventory inventory(PluginInstallation... plugins) {
        return new PluginInventory(List.of(plugins), List.of());
    }

    private static PluginInstallation installation(String owner, String engine) {
        return installation(owner, new Provider(owner, engine), PluginLifecyclePolicy.PROCESS_RESTART);
    }

    private static PluginInstallation installation(String owner, Provider provider, PluginLifecyclePolicy lifecycle) {
        var descriptor = new PluginDescriptor(owner, owner, "7.2.0", VersionRequirement.unspecified(), List.of(),
                "fixture.Provider", null, "plugin.name", "plugin.summary", "database", "neutral",
                PluginKind.FEATURE, List.of(), lifecycle, PluginExecutionMode.HOST_PROCESS_FULL_TRUST);
        return new PluginInstallation(descriptor, PluginStatus.STARTED, provider.getClass().getClassLoader(), provider);
    }

    private static class Provider implements PixivFeaturePlugin, DatabaseProvider {
        private final String id;
        private final String engine;
        Provider(String id, String engine) { this.id = id; this.engine = engine; }
        @Override public String id() { return id; }
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public String databaseId() { return engine; }
        @Override public java.util.List<top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionField> connectionFields() { return java.util.List.of(new top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionField("database.jdbc-url", top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType.STRING)); }
        @Override public void prepareStorage(DatabaseConnectionSettings settings) { throw new AssertionError("selection must not prepare storage"); }
        @Override public DatabaseConnectionSettings localFileSettings(DatabaseConnectionSettings settings, java.nio.file.Path file) {
            throw new AssertionError("selection must not map files");
        }
        @Override public DatabaseConnectionSettings normalizeConfiguration(DatabaseConnectionSettings settings, boolean selectionChanged) { return settings; }
        @Override public DatabaseConnectionTarget resolve(DatabaseConnectionSettings settings) {
            throw new AssertionError("selection must not resolve or connect");
        }
        @Override public java.util.Map<String, DatabaseConnectionTarget> retainedLocalPrivateDatabases(java.nio.file.Path data, String installation) { throw new AssertionError("selection must not inspect storage"); }
        @Override public java.util.Set<String> privateDatabaseOwners(DatabaseConnectionTarget target, java.nio.file.Path data, String installation) { throw new AssertionError("selection must not inspect storage"); }
        @Override public top.sywyar.pixivdownload.plugin.api.database.migration.DatabaseExportSession exportDatabase(DatabaseConnectionTarget target, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationRequest request, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationControl control) { throw new AssertionError("selection must not export"); }
        @Override public top.sywyar.pixivdownload.plugin.api.database.migration.DatabaseImportSession importDatabase(DatabaseConnectionTarget target, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationControl control) { throw new AssertionError("selection must not import"); }
        @Override public void verifySource(DatabaseConnectionTarget target, String revision) { throw new AssertionError("selection must not verify data"); }
        @Override public Inspection inspectMigration(DatabaseConnectionTarget target, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema, boolean source) { throw new AssertionError("selection must not inspect migration"); }
        @Override public void verifyImported(DatabaseConnectionTarget target, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSnapshot snapshot, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationControl control) { throw new AssertionError("selection must not verify data"); }
    }
}
