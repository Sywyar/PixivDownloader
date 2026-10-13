package top.sywyar.pixivdownload.plugin.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.core.db.DatabaseConfig;
import top.sywyar.pixivdownload.core.db.DatabasePoolProperties;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.UnavailableDataSource;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("无数据库时的插件 schema 激活")
class PluginSchemaLifecycleUnavailableTest {

    private final DatabaseSchemaRegistry registry = new DatabaseSchemaRegistry(new PluginRegistry(List.of()));
    private final UnavailableDataSource dataSource = new UnavailableDataSource();
    private final DatabaseInitializer initializer = spy(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.initializer(
            new JdbcTemplate(dataSource), registry.contributions(), registry.mergedSchema(),
            TestI18nBeans.appMessages(), event -> {
                throw new AssertionError("无数据库时不得发布 schema 就绪事件");
            }));
    private final org.springframework.jdbc.support.JdbcTransactionManager transactionManager = spy(
            new DatabaseConfig(mock(DownloadConfig.class), new DatabasePoolProperties(),
                    TestI18nBeans.appMessages()).transactionManager(dataSource, mock(DatabaseTarget.class)));
    private final PluginSchemaLifecycleCoordinator coordinator =
            new PluginSchemaLifecycleCoordinator(registry, initializer, transactionManager);

    @Test
    @DisplayName("空 owner 无需事务即可激活并发布声明，随后释放 reservation")
    void emptyOwnerCanActivateWithoutDatabase() {
        coordinator.activate(registered(List.of()));
        SchemaContribution empty = new SchemaContribution(List.of(), List.of(), List.of());

        coordinator.activate(registered(List.of(empty)));

        assertThat(registry.contributions()).containsExactly(empty);
        assertThat(registry.mergedSchema().tables()).isEmpty();
        verifyNoInteractions(transactionManager, initializer);
        try (var next = registry.reserveOwnerEvolution("dynamic", List.of())) {
            assertThat(next.ownerTableNames()).isEmpty();
        }
    }

    @Test
    @DisplayName("空新声明不能跳过历史 owner 表校验，无数据库时拒绝且保留 ledger")
    void retainedOwnerTablesStillRequireDatabase() {
        SchemaContribution retained = new SchemaContribution(List.of(new TableSpec("dynamic_items",
                List.of(new ColumnSpec("id", "INTEGER", false, null, 1)), List.of())), List.of(), List.of());
        registry.register("dynamic", retained);

        assertThatThrownBy(() -> coordinator.activate(registered(List.of())))
                .isInstanceOf(DatabaseUnavailableException.class);

        assertThat(registry.contributions()).containsExactly(retained);
        assertThat(registry.mergedSchema().tables()).containsOnlyKeys("dynamic_items");
        verifyNoInteractions(initializer);
        try (var next = registry.reserveOwnerEvolution("dynamic", List.of())) {
            assertThat(next.ownerTableNames()).containsExactly("dynamic_items");
        }
    }

    private PluginRegistry.RegisteredPlugin registered(List<SchemaContribution> declarations) {
        PixivFeaturePlugin plugin = mock(PixivFeaturePlugin.class);
        when(plugin.id()).thenReturn("dynamic");
        when(plugin.schema()).thenReturn(declarations);
        return new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, getClass().getClassLoader(), "dynamic", 1L);
    }
}
