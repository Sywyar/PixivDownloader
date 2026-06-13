package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.core.db.PathPrefixColumns;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnMigrationSpec;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

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
    @DisplayName("AUTOINCREMENT 声明在单列 INTEGER 主键上应注册成功")
    void autoIncrementOnSingleIntegerPrimaryKeySucceeds() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        registry.register(contribution("demo", table("demo_items",
                new ColumnSpec("id", "INTEGER", false, null, 1, true),
                col("name", "TEXT", true, null, 0))));

        assertThat(registry.contributions()).hasSize(1);
    }

    @Test
    @DisplayName("AUTOINCREMENT 声明在非主键列上应注册抛错")
    void autoIncrementOnNonPrimaryKeyColumnFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register(contribution("demo", table("demo_items",
                col("id", "INTEGER", false, null, 1),
                new ColumnSpec("seq", "INTEGER", false, null, 0, true)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTOINCREMENT")
                .hasMessageContaining("demo_items.seq");
    }

    @Test
    @DisplayName("AUTOINCREMENT 声明在复合主键列上应注册抛错")
    void autoIncrementOnCompositePrimaryKeyFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register(contribution("demo", table("demo_items",
                new ColumnSpec("a", "INTEGER", true, null, 1, true),
                col("b", "INTEGER", true, null, 2)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTOINCREMENT");
    }

    @Test
    @DisplayName("AUTOINCREMENT 声明在非 INTEGER 主键上应注册抛错")
    void autoIncrementOnNonIntegerPrimaryKeyFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register(contribution("demo", table("demo_items",
                new ColumnSpec("id", "TEXT", false, null, 1, true)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTOINCREMENT");
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
    @DisplayName("内置插件合并出的路径前缀列与旧静态总表五张表等价")
    void mergedPathColumnsEqualLegacyFiveTables() {
        PathPrefixColumns merged = DatabaseSchemaRegistry.forBuiltInPlugins().pathPrefixColumns();

        assertThat(merged.all()).containsExactlyInAnyOrder(
                new PathPrefixColumns.TableColumns("artworks", "artwork_id", List.of("folder", "move_folder")),
                new PathPrefixColumns.TableColumns("novels", "novel_id", List.of("folder")),
                new PathPrefixColumns.TableColumns("manga_series", "series_id", List.of("cover_folder")),
                new PathPrefixColumns.TableColumns("novel_series", "series_id", List.of("cover_folder")),
                new PathPrefixColumns.TableColumns("collections", "id", List.of("download_root")));
    }

    @Test
    @DisplayName("内置 contribution 的 ownerPluginId 应全部为 core（卸载投影裁定）")
    void builtInContributionsAreAllOwnedByCore() {
        List<SchemaContribution> contributions = DatabaseSchemaRegistry.forBuiltInPlugins().contributions();

        assertThat(contributions).isNotEmpty();
        assertThat(contributions).allSatisfy(contribution ->
                assertThat(contribution.ownerPluginId()).isEqualTo("core"));
    }

    @Test
    @DisplayName("同一表内列名（含大小写差异）重复时注册应抛错")
    void duplicateColumnNameFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register(contribution("demo",
                table("demo_items",
                        col("id", "INTEGER", false, null, 1),
                        col("ID", "TEXT", false, null, 0)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo_items.id")
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("显式索引名跨表 / 跨 contribution 重复时注册应抛错")
    void duplicateIndexNameFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(new SchemaContribution("first",
                List.of(new TableSpec("first_items",
                        List.of(col("id", "INTEGER", false, null, 1)),
                        List.of(new IndexSpec("idx_shared", IndexOrigin.CREATE_INDEX, false, List.of("id"))))),
                List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> registry.register(new SchemaContribution("second",
                List.of(new TableSpec("second_items",
                        List.of(col("id", "INTEGER", false, null, 1)),
                        List.of(new IndexSpec("IDX_SHARED", IndexOrigin.CREATE_INDEX, false, List.of("id"))))),
                List.of(), List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idx_shared")
                .hasMessageContaining("second");
    }

    @Test
    @DisplayName("同一表的路径列被重复声明时注册应抛错")
    void duplicatePathColumnDeclarationFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(new SchemaContribution("first",
                List.of(table("demo_items",
                        col("id", "INTEGER", false, null, 1),
                        col("folder", "TEXT", false, null, 0))),
                List.of(), List.of(),
                List.of(new PathColumnSpec("demo_items", "id", List.of("folder")))));

        assertThatThrownBy(() -> registry.register(new SchemaContribution("second",
                List.of(), List.of(), List.of(),
                List.of(new PathColumnSpec("DEMO_ITEMS", "id", List.of("folder"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo_items")
                .hasMessageContaining("second");
    }

    @Test
    @DisplayName("路径列指向未知表时合并应抛错")
    void pathColumnsUnknownTableFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(new SchemaContribution("demo",
                List.of(), List.of(), List.of(),
                List.of(new PathColumnSpec("missing_table", "id", List.of("folder")))));

        assertThatThrownBy(registry::pathPrefixColumns)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing_table");
    }

    @Test
    @DisplayName("路径列指向未知列时合并应抛错")
    void pathColumnsUnknownColumnFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(new SchemaContribution("demo",
                List.of(table("demo_items",
                        col("id", "INTEGER", false, null, 1),
                        col("folder", "TEXT", false, null, 0))),
                List.of(), List.of(),
                List.of(new PathColumnSpec("demo_items", "id", List.of("folder", "move_folder")))));

        assertThatThrownBy(registry::pathPrefixColumns)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo_items.move_folder");
    }

    @Test
    @DisplayName("跨 contribution 声明既有表的路径列应并入合并结果")
    void pathColumnsAcrossContributionsMerge() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register(contribution("owner",
                table("demo_items",
                        col("id", "INTEGER", false, null, 1),
                        col("folder", "TEXT", false, null, 0))));
        registry.register(new SchemaContribution("extender",
                List.of(), List.of(), List.of(),
                List.of(new PathColumnSpec("demo_items", "id", List.of("folder")))));

        assertThat(registry.pathPrefixColumns().all()).containsExactly(
                new PathPrefixColumns.TableColumns("demo_items", "id", List.of("folder")));
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
