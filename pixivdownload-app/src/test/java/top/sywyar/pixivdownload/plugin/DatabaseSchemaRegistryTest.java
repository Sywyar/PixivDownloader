package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixColumns;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnMigrationSpec;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

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

    private static SchemaContribution contribution(TableSpec... tables) {
        return new SchemaContribution(List.of(tables), List.of(), List.of());
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
        SchemaContribution contribution = contribution(
                table("demo_items", col("id", "INTEGER", false, null, 1)));

        registry.register("demo", contribution);
        List<SchemaContribution> firstContributions = registry.contributions();
        ManagedDatabaseSchema.DatabaseSchema firstMerged = registry.mergedSchema();

        registry.unregister("demo");
        assertThat(registry.contributions()).isEmpty();
        assertThat(registry.mergedSchema().tables()).isEmpty();

        registry.register("demo", contribution);
        assertThat(registry.contributions()).isEqualTo(firstContributions);
        assertThat(registry.mergedSchema()).isEqualTo(firstMerged);
    }

    @Test
    @DisplayName("owner schema reservation 只在 publish 后原子推进累计 ledger")
    void ownerReservationPublishesAccumulatedLedgerAtomically() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("demo", contribution(table("demo_items",
                col("id", "INTEGER", false, null, 1),
                col("value", "TEXT", false, null, 0))));

        SchemaContribution replacement = new SchemaContribution(
                List.of(new TableSpec("demo_items",
                        List.of(
                                col("id", "INTEGER", false, null, 1),
                                col("value", "TEXT", false, null, 0),
                                col("category", "TEXT", false, null, 0)),
                        List.of(new IndexSpec("idx_demo_category", IndexOrigin.CREATE_INDEX,
                                false, List.of("category"))))),
                List.of(),
                List.of());

        try (DatabaseSchemaRegistry.OwnerSchemaReservation reservation =
                     registry.reserveOwnerEvolution("demo", List.of(replacement))) {
            assertThat(registry.mergedSchema().tables().get("demo_items").columns())
                    .extracting(ManagedDatabaseSchema.ColumnSpec::name)
                    .containsExactly("id", "value");
            assertThat(reservation.ownerSchema().tables().get("demo_items").columns())
                    .extracting(ManagedDatabaseSchema.ColumnSpec::name)
                    .containsExactly("id", "value", "category");
            reservation.publish();
        }

        assertThat(registry.mergedSchema().tables().get("demo_items").columns())
                .extracting(ManagedDatabaseSchema.ColumnSpec::name)
                .containsExactly("id", "value", "category");
    }

    @Test
    @DisplayName("owner schema 新代不得追加 SQLite 无法安全补齐的列")
    void ownerReplacementRejectsUnsafeMissingColumn() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("demo", contribution(
                table("demo_items", col("id", "INTEGER", false, null, 1))));
        SchemaContribution replacement = contribution(table("demo_items",
                col("id", "INTEGER", false, null, 1),
                col("required_value", "TEXT", true, null, 0)));

        assertThatThrownBy(() -> {
            try (var ignored = registry.reserveOwnerEvolution("demo", List.of(replacement))) {
                // reservation creation itself must reject the unsafe evolution
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot safely append column")
                .hasMessageContaining("demo_items.required_value")
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("owner schema 新代不得向既有表追加 SQLite 无法 ALTER 的唯一约束")
    void ownerReplacementRejectsNewUniqueConstraint() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("demo", contribution(table("demo_items",
                col("id", "INTEGER", false, null, 1),
                col("value", "TEXT", false, null, 0))));
        SchemaContribution replacement = new SchemaContribution(
                List.of(new TableSpec("demo_items",
                        List.of(
                                col("id", "INTEGER", false, null, 1),
                                col("value", "TEXT", false, null, 0)),
                        List.of(new IndexSpec(null, IndexOrigin.UNIQUE_CONSTRAINT,
                                true, List.of("value"))))),
                List.of(),
                List.of());

        assertThatThrownBy(() -> {
            try (var ignored = registry.reserveOwnerEvolution("demo", List.of(replacement))) {
                // reservation creation itself must reject the unsafe evolution
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot safely append constraint")
                .hasMessageContaining("demo_items")
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("owner schema 新代不得删除或重定义既有列")
    void ownerReplacementRejectsRedefinedColumn() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("demo", contribution(table("demo_items",
                col("id", "INTEGER", false, null, 1),
                col("value", "TEXT", false, null, 0))));
        SchemaContribution replacement = contribution(table("demo_items",
                col("id", "INTEGER", false, null, 1),
                col("value", "INTEGER", false, null, 0)));

        assertThatThrownBy(() -> {
            try (var ignored = registry.reserveOwnerEvolution("demo", List.of(replacement))) {
                // reservation creation itself must reject the redefinition
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compatible subset or safe superset")
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("owner 写 reservation 覆盖 publish 并阻塞并发 registry 变更")
    void ownerReservationPreventsStalePublish() throws Exception {
        DatabaseSchemaRegistry registry = emptyRegistry();
        SchemaContribution contribution = contribution(
                table("demo_items", col("id", "INTEGER", false, null, 1)));
        registry.register("demo", contribution);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch attempted = new CountDownLatch(1);
        Future<?> concurrentMutation;
        try {
            try (DatabaseSchemaRegistry.OwnerSchemaReservation reservation =
                         registry.reserveOwnerEvolution("demo", List.of(contribution))) {
                concurrentMutation = executor.submit(() -> {
                    attempted.countDown();
                    registry.register("other", contribution(
                            table("other_items", col("id", "INTEGER", false, null, 1))));
                });
                assertThat(attempted.await(1, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> concurrentMutation.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                reservation.publish();
            }
            concurrentMutation.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(registry.mergedSchema().tables()).containsKeys("demo_items", "other_items");
    }

    @Test
    @DisplayName("既有表不得在运行期追加唯一显式索引")
    void ownerEvolutionRejectsUniqueIndexOnRetainedTable() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("demo", contribution(table("demo_items",
                col("id", "INTEGER", false, null, 1),
                col("value", "TEXT", false, null, 0))));
        SchemaContribution replacement = new SchemaContribution(
                List.of(new TableSpec("demo_items",
                        List.of(
                                col("id", "INTEGER", false, null, 1),
                                col("value", "TEXT", false, null, 0)),
                        List.of(new IndexSpec("idx_demo_value_unique", IndexOrigin.CREATE_INDEX,
                                true, List.of("value"))))),
                List.of(),
                List.of());

        assertThatThrownBy(() -> {
            try (var ignored = registry.reserveOwnerEvolution("demo", List.of(replacement))) {
                // reservation creation itself must reject the unique index
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot safely append constraint")
                .hasMessageContaining("demo_items");
    }

    @Test
    @DisplayName("注销未声明 schema 的插件应静默返回")
    void unregisterUnknownPluginIsSilent() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("demo", contribution(
                table("demo_items", col("id", "INTEGER", false, null, 1))));

        registry.unregister("no-schema-plugin");

        assertThat(registry.contributions()).hasSize(1);
    }

    @Test
    @DisplayName("宿主签发的 ownerPluginId 为空时注册应抛错")
    void registerWithoutOwnerPluginIdFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register(" ", contribution(
                table("demo_items", col("id", "INTEGER", false, null, 1)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ownerPluginId");
    }

    @Test
    @DisplayName("表名（含大小写差异）冲突时注册应抛错")
    void duplicateTableNameFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("first", contribution(
                table("demo_items", col("id", "INTEGER", false, null, 1))));

        assertThatThrownBy(() -> registry.register("second", contribution(
                table("DEMO_ITEMS", col("id", "INTEGER", false, null, 1)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo_items")
                .hasMessageContaining("second");
    }

    @Test
    @DisplayName("AUTOINCREMENT 声明在单列 INTEGER 主键上应注册成功")
    void autoIncrementOnSingleIntegerPrimaryKeySucceeds() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        registry.register("demo", contribution(table("demo_items",
                new ColumnSpec("id", "INTEGER", false, null, 1, true),
                col("name", "TEXT", true, null, 0))));

        assertThat(registry.contributions()).hasSize(1);
    }

    @Test
    @DisplayName("AUTOINCREMENT 声明在非主键列上应注册抛错")
    void autoIncrementOnNonPrimaryKeyColumnFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register("demo", contribution(table("demo_items",
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

        assertThatThrownBy(() -> registry.register("demo", contribution(table("demo_items",
                new ColumnSpec("a", "INTEGER", true, null, 1, true),
                col("b", "INTEGER", true, null, 2)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTOINCREMENT");
    }

    @Test
    @DisplayName("AUTOINCREMENT 声明在非 INTEGER 主键上应注册抛错")
    void autoIncrementOnNonIntegerPrimaryKeyFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register("demo", contribution(table("demo_items",
                new ColumnSpec("id", "TEXT", false, null, 1, true)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTOINCREMENT");
    }

    @Test
    @DisplayName("安全补列规则应合并进目标表的期望形态")
    void columnMigrationAppendsColumn() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("owner", contribution(
                table("demo_items", col("id", "INTEGER", false, null, 1))));
        registry.register("owner", new SchemaContribution(
                List.of(),
                List.of(new ColumnMigrationSpec("DEMO_ITEMS", col("extra", "TEXT", false, null, 0))),
                List.of()));

        ManagedDatabaseSchema.TableSpec merged = registry.mergedSchema().tables().get("demo_items");

        assertThat(merged.columns())
                .extracting(ManagedDatabaseSchema.ColumnSpec::name)
                .containsExactly("id", "extra");
    }

    @Test
    @DisplayName("插件不得为其他插件拥有的表声明补列")
    void columnMigrationAcrossOwnersFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("owner", contribution(
                table("demo_items", col("id", "INTEGER", false, null, 1))));
        registry.register("extender", new SchemaContribution(
                List.of(),
                List.of(new ColumnMigrationSpec("demo_items", col("extra", "TEXT", false, null, 0))),
                List.of()));

        assertThatThrownBy(registry::mergedSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owned by another plugin")
                .hasMessageContaining("owner: owner")
                .hasMessageContaining("plugin: extender");
    }

    @Test
    @DisplayName("补列目标表不存在时合并应抛错")
    void columnMigrationUnknownTableFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("extender", new SchemaContribution(
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
    @DisplayName("内置核心 schema 应按宿主签发的 core owner 整体注销")
    void builtInContributionsUseTrustedCoreOwner() {
        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();

        assertThat(registry.contributions()).isNotEmpty();
        assertThat(registry.mergedSchema().tables()).isNotEmpty();

        registry.unregister("core");

        assertThat(registry.contributions()).isEmpty();
        assertThat(registry.mergedSchema().tables()).isEmpty();
        assertThat(registry.pathPrefixColumns().all()).isEmpty();
    }

    @Test
    @DisplayName("schema owner 应复用注册时捕获的插件 id")
    void schemaOwnerUsesCapturedPluginId() {
        AtomicInteger idCalls = new AtomicInteger();
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                if (idCalls.incrementAndGet() == 1) {
                    return "stable-owner";
                }
                throw new IllegalStateException("plugin id must not be read again");
            }

            @Override
            public String displayName() {
                return "stable owner";
            }

            @Override
            public String description() {
                return "stable owner fixture";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<SchemaContribution> schema() {
                return List.of(contribution(
                        table("stable_items", col("id", "INTEGER", false, null, 1))));
            }
        };
        PluginRegistry plugins = new PluginRegistry(List.of(plugin));

        DatabaseSchemaRegistry registry = new DatabaseSchemaRegistry(plugins);

        assertThat(registry.mergedSchema().tables()).containsKey("stable_items");
        assertThat(idCalls).hasValue(1);
        registry.unregister("stable-owner");
        assertThat(registry.contributions()).isEmpty();
        assertThat(registry.mergedSchema().tables()).isEmpty();
    }

    @Test
    @DisplayName("同一表内列名（含大小写差异）重复时注册应抛错")
    void duplicateColumnNameFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register("demo", contribution(
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
        registry.register("first", new SchemaContribution(
                List.of(new TableSpec("first_items",
                        List.of(col("id", "INTEGER", false, null, 1)),
                        List.of(new IndexSpec("idx_shared", IndexOrigin.CREATE_INDEX, false, List.of("id"))))),
                List.of(), List.of()));

        assertThatThrownBy(() -> registry.register("second", new SchemaContribution(
                List.of(new TableSpec("second_items",
                        List.of(col("id", "INTEGER", false, null, 1)),
                        List.of(new IndexSpec("IDX_SHARED", IndexOrigin.CREATE_INDEX, false, List.of("id"))))),
                List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idx_shared")
                .hasMessageContaining("second");
    }

    @Test
    @DisplayName("同一表的路径列被重复声明时注册应抛错")
    void duplicatePathColumnDeclarationFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("demo", new SchemaContribution(
                List.of(table("demo_items",
                        col("id", "INTEGER", false, null, 1),
                        col("folder", "TEXT", false, null, 0))),
                List.of(),
                List.of(new PathColumnSpec("demo_items", "id", List.of("folder")))));

        assertThatThrownBy(() -> registry.register("demo", new SchemaContribution(
                List.of(), List.of(),
                List.of(new PathColumnSpec("DEMO_ITEMS", "id", List.of("folder"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo_items")
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("路径列指向未知表时合并应抛错")
    void pathColumnsUnknownTableFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("demo", new SchemaContribution(
                List.of(), List.of(),
                List.of(new PathColumnSpec("missing_table", "id", List.of("folder")))));

        assertThatThrownBy(registry::pathPrefixColumns)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing_table");
    }

    @Test
    @DisplayName("路径列指向未知列时合并应抛错")
    void pathColumnsUnknownColumnFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("demo", new SchemaContribution(
                List.of(table("demo_items",
                        col("id", "INTEGER", false, null, 1),
                        col("folder", "TEXT", false, null, 0))),
                List.of(),
                List.of(new PathColumnSpec("demo_items", "id", List.of("folder", "move_folder")))));

        assertThatThrownBy(registry::pathPrefixColumns)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo_items.move_folder");
    }

    @Test
    @DisplayName("跨 contribution 声明既有表的路径列应并入合并结果")
    void pathColumnsAcrossContributionsMerge() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("owner", contribution(
                table("demo_items",
                        col("id", "INTEGER", false, null, 1),
                        col("folder", "TEXT", false, null, 0))));
        registry.register("owner", new SchemaContribution(
                List.of(), List.of(),
                List.of(new PathColumnSpec("demo_items", "id", List.of("folder")))));

        assertThat(registry.pathPrefixColumns().all()).containsExactly(
                new PathPrefixColumns.TableColumns("demo_items", "id", List.of("folder")));
    }

    @Test
    @DisplayName("插件不得为其他插件拥有的表声明路径列")
    void pathColumnsAcrossOwnersFail() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("owner", contribution(
                table("demo_items",
                        col("id", "INTEGER", false, null, 1),
                        col("folder", "TEXT", false, null, 0))));
        registry.register("extender", new SchemaContribution(
                List.of(), List.of(),
                List.of(new PathColumnSpec("demo_items", "id", List.of("folder")))));

        assertThatThrownBy(registry::pathPrefixColumns)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owned by another plugin")
                .hasMessageContaining("owner: owner")
                .hasMessageContaining("plugin: extender");
    }

    @Test
    @DisplayName("补列与既有列重名时合并应抛错")
    void columnMigrationDuplicateColumnFails() {
        DatabaseSchemaRegistry registry = emptyRegistry();
        registry.register("owner", contribution(
                table("demo_items", col("id", "INTEGER", false, null, 1))));
        registry.register("owner", new SchemaContribution(
                List.of(),
                List.of(new ColumnMigrationSpec("demo_items", col("ID", "INTEGER", false, null, 0))),
                List.of()));

        assertThatThrownBy(registry::mergedSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo_items.id");
    }
}
