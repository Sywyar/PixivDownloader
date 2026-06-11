package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.plugin.api.ColumnMigrationSpec;
import top.sywyar.pixivdownload.plugin.api.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.TableSpec;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DatabaseSchemaRegistry 单元测试")
class DatabaseSchemaRegistryTest {

    private static DatabaseSchemaRegistry emptyRegistry() {
        return new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
    }

    private static TableSpec table(String name, ColumnSpec... columns) {
        return new TableSpec(name, List.of(columns), List.of());
    }

    private static ColumnSpec col(String name, String type, boolean notNull, String defaultValue, int pk) {
        return new ColumnSpec(name, type, notNull, defaultValue, pk);
    }

    private static SchemaContribution contribution(String ownerPluginId, TableSpec... tables) {
        return new SchemaContribution(ownerPluginId, List.of(tables), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("内置插件合并结果与旧静态总表逐表逐列等价")
    void mergedSchemaEqualsLegacyBaseline() {
        ManagedDatabaseSchema.DatabaseSchema merged = DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema();
        ManagedDatabaseSchema.DatabaseSchema baseline = LegacySchemaBaseline.spec();

        assertThat(merged.tables().keySet())
                .containsExactlyInAnyOrderElementsOf(baseline.tables().keySet());
        for (Map.Entry<String, ManagedDatabaseSchema.TableSpec> expected : baseline.tables().entrySet()) {
            ManagedDatabaseSchema.TableSpec actual = merged.tables().get(expected.getKey());
            assertThat(actual.columns())
                    .as("表 %s 的列", expected.getKey())
                    .containsExactlyElementsOf(expected.getValue().columns());
            assertThat(actual.indexes())
                    .as("表 %s 的索引", expected.getKey())
                    .containsExactlyElementsOf(expected.getValue().indexes());
        }
    }

    @Test
    @DisplayName("register→unregister→再 register 后状态与首次注册一致")
    void reRegisterAfterUnregisterRestoresState() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        SchemaContribution contribution = contribution("demo",
                table("demo_items", col("id", "INTEGER", false, null, 1)));

        registry.register(contribution);
        List<SchemaContribution> firstContributions = registry.contributions();
        ManagedDatabaseSchema.DatabaseSchema firstMerged = registry.mergedSchema();

        registry.unregister("demo");
        assertThat(registry.contributions()).isEmpty();
        assertThat(registry.mergedSchema().tables()).isEmpty();

        registry.register(contribution);
        assertThat(registry.contributions()).isEqualTo(firstContributions);
        assertThat(registry.mergedSchema()).isEqualTo(firstMerged);
    }

    @Test
    @DisplayName("注销未声明 schema 的插件应静默返回")
    void unregisterUnknownPluginIsSilent() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(contribution("demo",
                table("demo_items", col("id", "INTEGER", false, null, 1))));

        registry.unregister("no-schema-plugin");

        assertThat(registry.contributions()).hasSize(1);
    }

    @Test
    @DisplayName("缺少 ownerPluginId 的 contribution 注册时应抛错")
    void registerWithoutOwnerPluginIdFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register(contribution(" ",
                table("demo_items", col("id", "INTEGER", false, null, 1)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ownerPluginId");
    }

    @Test
    @DisplayName("表名（含大小写差异）冲突时注册应抛错")
    void duplicateTableNameFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(contribution("first",
                table("demo_items", col("id", "INTEGER", false, null, 1))));

        assertThatThrownBy(() -> registry.register(contribution("second",
                table("DEMO_ITEMS", col("id", "INTEGER", false, null, 1)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo_items")
                .hasMessageContaining("second");
    }

    @Test
    @DisplayName("跨表附加索引暂不支持，注册时应显式拒绝")
    void standaloneIndexContributionRejected() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        SchemaContribution withIndex = new SchemaContribution(
                "demo",
                List.of(),
                List.of(new IndexSpec("idx_demo", IndexOrigin.CREATE_INDEX, false, List.of("id"))),
                List.of(),
                List.of());

        assertThatThrownBy(() -> registry.register(withIndex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    @DisplayName("安全补列规则应合并进目标表的期望形态")
    void columnMigrationAppendsColumn() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(contribution("owner",
                table("demo_items", col("id", "INTEGER", false, null, 1))));
        registry.register(new SchemaContribution(
                "extender",
                List.of(),
                List.of(),
                List.of(new ColumnMigrationSpec("demo_items", col("extra", "TEXT", false, null, 0))),
                List.of()));

        ManagedDatabaseSchema.TableSpec merged = registry.mergedSchema().tables().get("demo_items");

        assertThat(merged.columns())
                .extracting(ManagedDatabaseSchema.ColumnSpec::name)
                .containsExactly("id", "extra");
    }

    @Test
    @DisplayName("补列目标表不存在时合并应抛错")
    void columnMigrationUnknownTableFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(new SchemaContribution(
                "extender",
                List.of(),
                List.of(),
                List.of(new ColumnMigrationSpec("missing_table", col("extra", "TEXT", false, null, 0))),
                List.of()));

        assertThatThrownBy(registry::mergedSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing_table");
    }

    @Test
    @DisplayName("补列与既有列重名时合并应抛错")
    void columnMigrationDuplicateColumnFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(contribution("owner",
                table("demo_items", col("id", "INTEGER", false, null, 1))));
        registry.register(new SchemaContribution(
                "extender",
                List.of(),
                List.of(),
                List.of(new ColumnMigrationSpec("demo_items", col("ID", "INTEGER", false, null, 0))),
                List.of()));

        assertThatThrownBy(registry::mergedSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo_items.id");
    }
}
