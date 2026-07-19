package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuiLauncher 启动期 schema")
class GuiLauncherStartupSchemaTest {

    private static final String PLUGIN_ID = "external-schema";
    private static final String TABLE = "external_schema_history";

    @Test
    @DisplayName("启动期 schema 应包含 bootstrap 已发现外置插件的受管表")
    void startupSchemaIncludesExternalPluginTables() throws Exception {
        ManagedDatabaseSchema.DatabaseSchema startupSchema = GuiLauncher.buildStartupManagedSchema(
                PluginEnabledSnapshot.empty(), discovery(new ExternalSchemaPlugin()));

        assertThat(startupSchema.tables().keySet()).contains(TABLE);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            exec(connection, "CREATE TABLE external_schema_history (id TEXT PRIMARY KEY)");

            DatabaseSchemaInspector.SchemaComparison builtInOnlyComparison =
                    DatabaseSchemaInspector.compare(connection,
                            DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema());
            assertThat(builtInOnlyComparison.details())
                    .anySatisfy(difference -> {
                        assertThat(difference.kind()).isEqualTo(
                                DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_TABLE);
                        assertThat(difference.tableName()).isEqualTo(TABLE);
                    });

            DatabaseSchemaInspector.SchemaComparison startupComparison =
                    DatabaseSchemaInspector.compare(connection, startupSchema);
            assertThat(startupComparison.details())
                    .noneSatisfy(difference -> {
                        assertThat(difference.kind()).isEqualTo(
                                DatabaseSchemaInspector.SchemaDifferenceKind.UNMANAGED_TABLE);
                        assertThat(difference.tableName()).isEqualTo(TABLE);
                    });
        }
    }

    @Test
    @DisplayName("禁用外置插件时启动期 schema 仍应包含其受管表")
    void disabledExternalPluginStillContributesStartupSchema() {
        ManagedDatabaseSchema.DatabaseSchema startupSchema = GuiLauncher.buildStartupManagedSchema(
                PluginEnabledSnapshot.ofDisabled(List.of(PLUGIN_ID), List.of()),
                discovery(new ExternalSchemaPlugin()));

        assertThat(startupSchema.tables().keySet()).contains(TABLE);
    }

    private static PluginDiscoveryResult discovery(PixivFeaturePlugin plugin) {
        return new PluginDiscoveryResult(List.of(new DiscoveredFeaturePlugin(
                PLUGIN_ID, plugin, GuiLauncherStartupSchemaTest.class.getClassLoader())), List.of());
    }

    private static void exec(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static final class ExternalSchemaPlugin implements PixivFeaturePlugin {

        @Override
        public String id() {
            return PLUGIN_ID;
        }

        @Override
        public String displayName() {
            return "plugin.name";
        }

        @Override
        public String description() {
            return "plugin.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<SchemaContribution> schema() {
            return List.of(new SchemaContribution(
                    List.of(new TableSpec(
                            TABLE,
                            List.of(new ColumnSpec("id", "TEXT", false, null, 1)),
                            List.of())),
                    List.of(),
                    List.of()));
        }
    }
}
