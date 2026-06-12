package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPluginConfiguration;
import top.sywyar.pixivdownload.duplicate.DuplicatePluginConfiguration;
import top.sywyar.pixivdownload.gallery.GalleryPluginConfiguration;
import top.sywyar.pixivdownload.novel.NovelPluginConfiguration;
import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;
import top.sywyar.pixivdownload.stats.StatsPluginConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class RegisteredPluginsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // CorePluginConfiguration 的 databaseInitializer bean 需要 JdbcTemplate / AppMessages，
            // StatsPluginConfiguration 的 statsRepository bean 需要 DataSource：
            // 用内存 SQLite 与测试 i18n 兜底（@PostConstruct 会真实建表，库随上下文丢弃）；
            // DuplicatePluginConfiguration 收敛的业务 bean 依赖核心组件，一律 mock 兜底
            .withBean("applicationTaskExecutor", org.springframework.core.task.TaskExecutor.class,
                    org.springframework.core.task.SyncTaskExecutor::new)
            .withBean(top.sywyar.pixivdownload.duplicate.ImageHashMapper.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.duplicate.ImageHashMapper.class))
            .withBean(top.sywyar.pixivdownload.download.ArtworkFileLocator.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.ArtworkFileLocator.class))
            .withBean(top.sywyar.pixivdownload.core.db.PixivDatabase.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.db.PixivDatabase.class))
            .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                    () -> org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class))
            .withBean(javax.sql.DataSource.class, () -> {
                org.springframework.jdbc.datasource.SingleConnectionDataSource ds =
                        new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                                "jdbc:sqlite::memory:", true);
                ds.setDriverClassName("org.sqlite.JDBC");
                return ds;
            })
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> {
                org.springframework.jdbc.datasource.SingleConnectionDataSource ds =
                        new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                                "jdbc:sqlite::memory:", true);
                ds.setDriverClassName("org.sqlite.JDBC");
                return new org.springframework.jdbc.core.JdbcTemplate(ds);
            })
            .withBean(top.sywyar.pixivdownload.i18n.AppMessages.class,
                    top.sywyar.pixivdownload.i18n.TestI18nBeans::appMessages)
            .withUserConfiguration(
                    CorePluginConfiguration.class,
                    DownloadWorkbenchPluginConfiguration.class,
                    GalleryPluginConfiguration.class,
                    NovelPluginConfiguration.class,
                    StatsPluginConfiguration.class,
                    DuplicatePluginConfiguration.class,
                    PluginRegistry.class,
                    DatabaseSchemaRegistry.class);

    @Test
    @DisplayName("六个空插件经各自 Configuration 注册进 PluginRegistry")
    void allPluginsRegistered() {
        runner.run(context -> {
            PluginRegistry registry = context.getBean(PluginRegistry.class);
            assertThat(registry.plugins())
                    .extracting(PixivFeaturePlugin::id)
                    .containsExactlyInAnyOrder(
                            "core", "download-workbench", "gallery", "novel", "stats", "duplicate");
        });
    }

    @Test
    @DisplayName("core 插件为 CORE 类别，其余为 FEATURE")
    void pluginKinds() {
        runner.run(context -> {
            PluginRegistry registry = context.getBean(PluginRegistry.class);
            assertThat(registry.find("core").orElseThrow().kind()).isEqualTo(PluginKind.CORE);
            assertThat(registry.plugins())
                    .filteredOn(plugin -> !plugin.id().equals("core"))
                    .allSatisfy(plugin -> assertThat(plugin.kind()).isEqualTo(PluginKind.FEATURE));
        });
    }

    @Test
    @DisplayName("除 core 声明各领域 schema、stats/duplicate 声明 web contribution 外，其余插件暂不声明任何 contribution")
    void emptyPluginsContributeNothing() {
        runner.run(context -> {
            PluginRegistry registry = context.getBean(PluginRegistry.class);
            assertThat(registry.plugins()).allSatisfy(plugin -> {
                if (plugin.id().equals("core")) {
                    // 按卸载投影测试，现存全部长期事实表归核心：领域 contribution 拆类但 owner 一律 core
                    assertThat(plugin.schema()).isNotEmpty();
                    assertThat(plugin.schema()).allSatisfy(contribution ->
                            assertThat(contribution.ownerPluginId()).isEqualTo("core"));
                } else {
                    assertThat(plugin.schema()).isEmpty();
                }
                assertThat(plugin.coreColumnUsages()).isEmpty();
                if (plugin.id().equals("stats") || plugin.id().equals("duplicate")) {
                    // 试点插件已声明路由 / 静态资源 / i18n / 导航（无私有表，statistics 与
                    // artwork_image_hashes 均按卸载投影测试归 core）
                    assertThat(plugin.routes()).isNotEmpty();
                    assertThat(plugin.staticResources()).isNotEmpty();
                    assertThat(plugin.i18n()).isNotEmpty();
                    assertThat(plugin.navigation()).isNotEmpty();
                } else {
                    assertThat(plugin.routes()).isEmpty();
                    assertThat(plugin.staticResources()).isEmpty();
                    assertThat(plugin.i18n()).isEmpty();
                    assertThat(plugin.navigation()).isEmpty();
                }
            });
        });
    }

    @Test
    @DisplayName("BuiltInPlugins 组合根清单与 Spring 注册的插件集合一致")
    void builtInPluginsMirrorSpringRegistration() {
        runner.run(context -> {
            PluginRegistry registry = context.getBean(PluginRegistry.class);
            assertThat(BuiltInPlugins.createAll())
                    .extracting(PixivFeaturePlugin::id)
                    .containsExactlyInAnyOrderElementsOf(
                            registry.plugins().stream().map(PixivFeaturePlugin::id).toList());
        });
    }
}
