package top.sywyar.pixivdownload.douyin.db.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixColumns;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.douyin.DouyinPlugin;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinSchemaContribution 抖音历史 schema")
class DouyinSchemaContributionTest {

    @Test
    @DisplayName("插件 schema 贡献抖音下载历史两张受管表")
    void contributesDouyinHistoryTables() {
        DatabaseSchemaRegistry registry = new DatabaseSchemaRegistry(
                new PluginRegistry(List.of(new DouyinPlugin())));

        assertThat(registry.mergedSchema().tables().keySet())
                .contains("douyin_works", "douyin_work_files");
        ManagedDatabaseSchema.TableSpec works = registry.mergedSchema().tables().get("douyin_works");
        assertThat(works.columns())
                .anySatisfy(column -> {
                    assertThat(column.name()).isEqualTo("description");
                    assertThat(column.type()).isEqualTo("TEXT");
                    assertThat(column.notNull()).isFalse();
                })
                .anySatisfy(column -> {
                    assertThat(column.name()).isEqualTo("item_title");
                    assertThat(column.type()).isEqualTo("TEXT");
                    assertThat(column.notNull()).isFalse();
                })
                .anySatisfy(column -> {
                    assertThat(column.name()).isEqualTo("caption");
                    assertThat(column.type()).isEqualTo("TEXT");
                    assertThat(column.notNull()).isFalse();
                });
        assertThat(works.indexes())
                .anySatisfy(index -> {
                    assertThat(index.origin()).isEqualTo(ManagedDatabaseSchema.IndexOrigin.UNIQUE_CONSTRAINT);
                    assertThat(index.unique()).isTrue();
                    assertThat(index.columns()).containsExactly("time");
                })
                .anySatisfy(index -> assertThat(index.name()).isEqualTo("idx_douyin_works_author_time"))
                .anySatisfy(index -> assertThat(index.name()).isEqualTo("idx_douyin_works_collection_order"));
        assertThat(registry.mergedSchema().tables().get("douyin_work_files").indexes())
                .singleElement()
                .satisfies(index -> assertThat(index.name()).isEqualTo("idx_douyin_work_files_work_id"));
    }

    @Test
    @DisplayName("folder 列登记到路径前缀列清单")
    void registersFolderAsPathPrefixColumn() {
        PathPrefixColumns columns = new DatabaseSchemaRegistry(
                new PluginRegistry(List.of(new DouyinPlugin()))).pathPrefixColumns();

        assertThat(columns.all()).containsExactly(
                new PathPrefixColumns.TableColumns("douyin_works", "work_id", List.of("folder")));
    }

    @Test
    @DisplayName("禁用 Douyin 插件时 schema 仍从安装态插件合并")
    void disabledPluginStillContributesSchema() {
        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginToggleProperties.PluginToggle off = new PluginToggleProperties.PluginToggle();
        off.setEnabled(false);
        toggles.put(DouyinPlugin.ID, off);
        PluginRegistry pluginRegistry = new PluginRegistry(List.of(new DouyinPlugin()), toggles);

        assertThat(pluginRegistry.plugins()).isEmpty();
        assertThat(pluginRegistry.allPlugins()).extracting(plugin -> plugin.id()).containsExactly(DouyinPlugin.ID);
        assertThat(new DatabaseSchemaRegistry(pluginRegistry).mergedSchema().tables().keySet())
                .contains("douyin_works", "douyin_work_files");
    }

    @Test
    @DisplayName("DatabaseInitializer 可创建并校验 Douyin 受管 schema")
    void initializerCreatesManagedDouyinSchema() throws Exception {
        DatabaseSchemaRegistry registry = new DatabaseSchemaRegistry(
                new PluginRegistry(List.of(new DouyinPlugin())));
        SingleConnectionDataSource ds = newDataSource();
        try {
            new DatabaseInitializer(new JdbcTemplate(ds),
                    registry.contributions(), registry.mergedSchema(), messages(), event -> {}).initialize();

            try (Connection connection = ds.getConnection()) {
                DatabaseSchemaInspector.SchemaComparison comparison =
                        DatabaseSchemaInspector.compare(connection, registry.mergedSchema());
                assertThat(comparison.matches())
                        .as("Douyin schema 差异：%s", comparison.details())
                        .isTrue();
            }
        } finally {
            ds.destroy();
        }
    }

    private static SingleConnectionDataSource newDataSource() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        return ds;
    }

    private static AppMessages messages() {
        return new AppMessages(new StaticMessageSource());
    }
}
