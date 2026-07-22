package top.sywyar.pixivdownload.plugin.schema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("外置插件动态 schema 生命周期")
class PluginSchemaLifecycleCoordinatorTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private DatabaseSchemaRegistry registry;
    private PluginSchemaLifecycleCoordinator coordinator;
    private List<Object> events;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);
        jdbc = new JdbcTemplate(dataSource);
        registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
        events = new ArrayList<>();
        DatabaseInitializer initializer = new DatabaseInitializer(
                jdbc,
                registry.contributions(),
                registry.mergedSchema(),
                TestI18nBeans.appMessages(),
                events::add);
        coordinator = new PluginSchemaLifecycleCoordinator(
                registry, initializer, new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    @DisplayName("首次运行期接入先建表和索引再发布 owner ledger")
    void firstActivationAppliesSchemaBeforePublishingRegistry() {
        jdbc.execute("CREATE TABLE unrelated_data (id INTEGER PRIMARY KEY)");
        SchemaContribution schema = schema(
                List.of(column("id", "INTEGER", false, null, 1),
                        column("value", "TEXT", false, null, 0)),
                List.of(new IndexSpec("idx_dynamic_value", IndexOrigin.CREATE_INDEX,
                        false, List.of("value"))),
                List.of());

        coordinator.activate(registered("dynamic", 1L, List.of(schema)));

        assertThat(tableExists("dynamic_items")).isTrue();
        assertThat(indexExists("idx_dynamic_value")).isTrue();
        assertThat(registry.mergedSchema().tables()).containsKey("dynamic_items");
        assertThat(tableExists("unrelated_data")).as("owner 校验不得拒绝其它领域的表").isTrue();
        assertThat(events).as("运行期增量 DDL 不得重放启动就绪事件").isEmpty();
    }

    @Test
    @DisplayName("新 generation 只允许安全补列和追加非唯一索引")
    void replacementGenerationAppliesAdditiveSchema() {
        coordinator.activate(registered("dynamic", 1L, List.of(schema(
                List.of(column("id", "INTEGER", false, null, 1),
                        column("value", "TEXT", false, null, 0)),
                List.of(),
                List.of()))));
        SchemaContribution replacement = schema(
                List.of(column("id", "INTEGER", false, null, 1),
                        column("value", "TEXT", false, null, 0),
                        column("category", "TEXT", false, null, 0)),
                List.of(new IndexSpec("idx_dynamic_category", IndexOrigin.CREATE_INDEX,
                        false, List.of("category"))),
                List.of());

        coordinator.activate(registered("dynamic", 2L, List.of(replacement)));

        assertThat(jdbc.queryForList("SELECT lower(name) FROM pragma_table_info('dynamic_items')", String.class))
                .containsExactly("id", "value", "category");
        assertThat(indexExists("idx_dynamic_category")).isTrue();
    }

    @Test
    @DisplayName("v2 业务启动失败恢复 v1 声明时保留已提交的 v2 schema 超集")
    void olderGenerationCanRecoverAgainstRetainedSuperset() {
        SchemaContribution versionOne = schema(
                List.of(column("id", "INTEGER", false, null, 1),
                        column("value", "TEXT", false, null, 0)),
                List.of(),
                List.of());
        SchemaContribution versionTwo = schema(
                List.of(column("id", "INTEGER", false, null, 1),
                        column("value", "TEXT", false, null, 0),
                        column("category", "TEXT", false, null, 0)),
                List.of(),
                List.of());
        coordinator.activate(registered("dynamic", 1L, List.of(versionOne)));
        coordinator.activate(registered("dynamic", 2L, List.of(versionTwo)));

        coordinator.activate(registered("dynamic", 3L, List.of(versionOne)));

        assertThat(jdbc.queryForList("SELECT lower(name) FROM pragma_table_info('dynamic_items')", String.class))
                .containsExactly("id", "value", "category");
        assertThat(registry.mergedSchema().tables().get("dynamic_items").columns())
                .extracting(column -> column.name())
                .containsExactly("id", "value", "category");
    }

    @Test
    @DisplayName("空 schema 声明恢复时保留 owner 已累计的表结构")
    void emptyDeclarationRetainsOwnerLedger() {
        SchemaContribution schema = schema(
                List.of(column("id", "INTEGER", false, null, 1),
                        column("value", "TEXT", false, null, 0)),
                List.of(),
                List.of());
        coordinator.activate(registered("dynamic", 1L, List.of(schema)));

        coordinator.activate(registered("dynamic", 2L, List.of()));

        assertThat(tableExists("dynamic_items")).isTrue();
        assertThat(registry.mergedSchema().tables()).containsKey("dynamic_items");
    }

    @Test
    @DisplayName("DDL 失败应回滚数据库且不得发布 registry 新代")
    void ddlFailureRollsBackDatabaseAndKeepsRegistryUnchanged() {
        SchemaContribution invalid = schema(
                List.of(column("id", "INTEGER", false, null, 1)),
                List.of(new IndexSpec("idx_dynamic_missing", IndexOrigin.CREATE_INDEX,
                        false, List.of("missing_column"))),
                List.of());

        assertThatThrownBy(() -> coordinator.activate(registered("dynamic", 1L, List.of(invalid))))
                .isInstanceOf(RuntimeException.class);

        assertThat(tableExists("dynamic_items")).isFalse();
        assertThat(indexExists("idx_dynamic_missing")).isFalse();
        assertThat(registry.contributions()).isEmpty();
        assertThat(registry.mergedSchema().tables()).isEmpty();
    }

    @Test
    @DisplayName("既有磁盘表结构漂移时回滚本次 DDL 并拒绝发布 owner ledger")
    void physicalSchemaDriftRejectsActivation() {
        jdbc.execute("CREATE TABLE dynamic_items (id TEXT PRIMARY KEY, value TEXT)");
        SchemaContribution expected = schema(
                List.of(column("id", "INTEGER", false, null, 1),
                        column("value", "TEXT", false, null, 0),
                        column("category", "TEXT", false, null, 0)),
                List.of(),
                List.of());

        assertThatThrownBy(() -> coordinator.activate(registered("dynamic", 1L, List.of(expected))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the physical database");

        assertThat(jdbc.queryForList("SELECT lower(name) FROM pragma_table_info('dynamic_items')", String.class))
                .containsExactly("id", "value");
        assertThat(registry.contributions()).isEmpty();
    }

    @Test
    @DisplayName("无法安全补齐的缺列必须回滚并拒绝接入")
    void skippedUnsafeColumnRejectsActivation() {
        jdbc.execute("CREATE TABLE dynamic_items (id INTEGER PRIMARY KEY)");
        SchemaContribution expected = schema(
                List.of(column("id", "INTEGER", false, null, 1),
                        column("required_value", "TEXT", true, null, 0)),
                List.of(),
                List.of());

        assertThatThrownBy(() -> coordinator.activate(registered("dynamic", 1L, List.of(expected))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot append safely")
                .hasMessageContaining("dynamic_items.required_value");

        assertThat(jdbc.queryForList("SELECT lower(name) FROM pragma_table_info('dynamic_items')", String.class))
                .containsExactly("id");
        assertThat(registry.contributions()).isEmpty();
    }

    @Test
    @DisplayName("运行期路径列变化在缺少 owner-scoped 迁移时显式拒绝")
    void dynamicPathColumnsFailClosed() {
        SchemaContribution withPathColumn = schema(
                List.of(column("id", "INTEGER", false, null, 1),
                        column("folder", "TEXT", false, null, 0)),
                List.of(),
                List.of("folder"));

        assertThatThrownBy(() -> coordinator.activate(registered(
                "dynamic", 1L, List.of(withPathColumn))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owner-scoped path migration")
                .hasMessageContaining("dynamic");

        assertThat(tableExists("dynamic_items")).isFalse();
        assertThat(registry.contributions()).isEmpty();
    }

    @Test
    @DisplayName("schema getter 返回 null 时在数据库和 registry 变更前拒绝接入")
    void nullSchemaIsRejectedBeforeMutation() {
        assertThatThrownBy(() -> coordinator.activate(registered("dynamic", 1L, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema() returned null")
                .hasMessageContaining("dynamic");

        assertThat(registry.contributions()).isEmpty();
        assertThat(tableExists("dynamic_items")).isFalse();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND lower(name) = lower(?)",
                Integer.class,
                tableName);
        return count != null && count > 0;
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND lower(name) = lower(?)",
                Integer.class,
                indexName);
        return count != null && count > 0;
    }

    private static SchemaContribution schema(
            List<ColumnSpec> columns,
            List<IndexSpec> indexes,
            List<String> pathColumns) {
        List<PathColumnSpec> paths = pathColumns.isEmpty()
                ? List.of()
                : List.of(new PathColumnSpec("dynamic_items", "id", pathColumns));
        return new SchemaContribution(
                List.of(new TableSpec("dynamic_items", columns, indexes)),
                List.of(),
                paths);
    }

    private PluginRegistry.RegisteredPlugin registered(
            String id,
            long generation,
            List<SchemaContribution> schema) {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public String description() {
                return id;
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<SchemaContribution> schema() {
                return schema;
            }
        };
        return new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, getClass().getClassLoader(), id, generation);
    }

    private static ColumnSpec column(
            String name,
            String type,
            boolean notNull,
            String defaultValue,
            int primaryKeyPosition) {
        return new ColumnSpec(name, type, notNull, defaultValue, primaryKeyPosition);
    }
}
