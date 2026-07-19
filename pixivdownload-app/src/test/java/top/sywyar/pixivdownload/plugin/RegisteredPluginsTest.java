package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.sywyar.pixivdownload.plugin.market.PluginMarketPluginConfiguration;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.push.PushFormatConverter;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

class RegisteredPluginsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // CorePluginConfiguration 的 databaseInitializer bean 需要 JdbcTemplate / AppMessages：
            // 用内存 SQLite 与测试 i18n 兜底（@PostConstruct 会真实建表，库随上下文丢弃）；
            // 外置插件不参与内置组合根切片；这里仅为 core / plugin-market 依赖提供兜底。
            .withBean("applicationTaskExecutor", org.springframework.core.task.TaskExecutor.class,
                    org.springframework.core.task.SyncTaskExecutor::new)
            .withBean(top.sywyar.pixivdownload.core.hash.ImageHashMapper.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.hash.ImageHashMapper.class))
            .withBean(top.sywyar.pixivdownload.core.hash.ArtworkHashService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.hash.ArtworkHashService.class))
            .withBean(top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator.class))
            .withBean(top.sywyar.pixivdownload.core.db.PixivDatabase.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.db.PixivDatabase.class))
            .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                    () -> org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class))
            .withBean(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class))
            .withBean(top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository.class))
            .withBean(top.sywyar.pixivdownload.core.work.service.WorkAssetService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.work.service.WorkAssetService.class))
            .withBean(top.sywyar.pixivdownload.core.work.service.WorkDeletionService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.work.service.WorkDeletionService.class))
            .withBean(top.sywyar.pixivdownload.collection.CollectionService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.collection.CollectionService.class))
            .withBean(top.sywyar.pixivdownload.quota.UserQuotaService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.quota.UserQuotaService.class))
            .withBean(top.sywyar.pixivdownload.setup.guest.GuestAccessGuard.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.setup.guest.GuestAccessGuard.class))
            .withBean(top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository.class))
            .withBean(top.sywyar.pixivdownload.core.appconfig.MultiModeConfig.class,
                    top.sywyar.pixivdownload.core.appconfig.MultiModeConfig::new)
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                    com.fasterxml.jackson.databind.ObjectMapper::new)
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
            .withBean(top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService.class))
            .withBean(top.sywyar.pixivdownload.core.pixiv.PixivAjaxProxyClient.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.pixiv.PixivAjaxProxyClient.class))
            .withBean(top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessGuard.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessGuard.class))
            .withBean(top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository.class))
            .withBean(top.sywyar.pixivdownload.core.notification.NotificationService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.notification.NotificationService.class))
            .withBean(top.sywyar.pixivdownload.setup.SetupService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.setup.SetupService.class))
            .withBean(top.sywyar.pixivdownload.core.appconfig.DownloadConfig.class,
                    top.sywyar.pixivdownload.core.appconfig.DownloadConfig::new)
            .withBean("downloadTaskExecutor", org.springframework.core.task.TaskExecutor.class,
                    org.springframework.core.task.SyncTaskExecutor::new)
            // 插件市场托管 Bean（PluginMarketService / PluginMarketController）随 PluginMarketPluginConfiguration 装配，
            // 其依赖的 catalog 引擎（仓库注册中心 / 清单读取 / 受信安装编排，核心基础设施、根包扫描）与安装响应映射 /
            // locale 解析在本切片里一律 mock 兜底。
            .withBean(top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry.class))
            .withBean(top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService.class))
            .withBean(top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService.class))
            .withBean(top.sywyar.pixivdownload.plugin.install.PluginInstallResponseMapper.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.install.PluginInstallResponseMapper.class))
            // 插件市场服务现额外只读 PluginStatusService（投影安装状态），本切片 mock 兜底。
            .withBean(top.sywyar.pixivdownload.plugin.management.PluginStatusService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.management.PluginStatusService.class))
            .withBean(top.sywyar.pixivdownload.i18n.AppLocaleResolver.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.i18n.AppLocaleResolver.class))
            // 插件启用开关：空实例代表全部启用（本切片不验证禁用语义，只需 PluginRegistry 的 @Autowired 构造可解析）。
            .withBean(PluginToggleProperties.class, PluginToggleProperties::new)
            // 计划任务能力只接受 STARTED owner；本切片不发布能力，未初始化的真实状态 Bean 保持默认拒绝。
            .withBean(top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState.class,
                    top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState::new)
            // 统一计划任务能力 registry 使用真实 Bean；该切片不发布外置插件能力。
            .withUserConfiguration(
                    CorePluginConfiguration.class,
                    PluginMarketPluginConfiguration.class,
                    PluginRegistry.class,
                    top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry.class,
                    DatabaseSchemaRegistry.class);

    @Test
    @DisplayName("内置插件经各自 Configuration 注册进 PluginRegistry（功能插件已外置、不在内置清单）")
    void allPluginsRegistered() {
        runner.run(context -> {
            PluginRegistry registry = context.getBean(PluginRegistry.class);
            assertThat(registry.plugins())
                    .extracting(PixivFeaturePlugin::id)
                    .containsExactlyInAnyOrder(
                            "core", "plugin-market");
        });
    }

    @Test
    @DisplayName("core 组合根只装配一个中性推送格式转换器")
    void coreProvidesSinglePushFormatConverter() {
        runner.run(context -> assertThat(context.getBeansOfType(PushFormatConverter.class)).hasSize(1));
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
    @DisplayName("各内置插件 contribution 边界：core 独占 schema，plugin-market 占自身功能贡献，功能插件已外置")
    void emptyPluginsContributeNothing() {
        // 路由：四个 web 功能插件声明各自页面 / API；core 额外声明横切与跨页共享路由（监控 / 邀请 / 下载数据 /
        // 图片字节 / 作者 / 系列 / 收藏 / 代理 / 公开与共享静态依赖 / 本地放行特例，AuthFilter 切 registry 后由其派生）；
        // download-workbench、gallery、novel 与 schedule 安全壳已在外置插件包中，duplicate 也随外置插件包接入。
        Set<String> routeContributingPlugins = Set.of("core", "plugin-market");
        Set<String> i18nContributingPlugins = Set.of("core", "plugin-market");
        Set<String> navContributingPlugins = Set.of("core", "plugin-market");
        Set<String> staticResourceContributingPlugins = Set.of("core", "plugin-market");
        Set<String> queueTypeContributingPlugins = Set.of();
        Set<String> downloadTabContributingPlugins = Set.of();
        // 落点 / 入口（landing）：gallery/novel 落点随外置插件接入，
        // 锁死契约面——其它插件不得静默声明落点（避免借落点 / 导航 order 间接改变业务落点）。
        Set<String> landingContributingPlugins = Set.of();
        // gallery 外置后，页面区块 / 下钻 / GUI 引导步骤均由外置 gallery 插件声明。
        Set<String> pageSectionContributingPlugins = Set.of();
        Set<String> drilldownContributingPlugins = Set.of();
        Set<String> onboardingStepContributingPlugins = Set.of();
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
                // i18n namespace 由内置前端插件声明；batch/userscript 归外置 download-workbench，gallery 归外置 gallery。
                if (i18nContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.i18n()).isNotEmpty();
                } else {
                    assertThat(plugin.i18n()).isEmpty();
                }
                // 路由：内置 web 功能插件 + core（横切 / 共享路由）声明。
                if (routeContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.routes()).isNotEmpty();
                } else {
                    assertThat(plugin.routes()).isEmpty();
                }
                // 导航：内置 web 功能插件 + core（邀请码管理 / 插件入口）声明，
                // 由 /api/navigation 按当前身份过滤后供前端跨插件导航 slot 渲染。
                if (navContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.navigation()).isNotEmpty();
                } else {
                    assertThat(plugin.navigation()).isEmpty();
                }
                // 静态资源：核心声明共享公共库（/js、/css、/vendor），内置 web 功能插件声明各自页面目录。
                if (staticResourceContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.staticResources()).isNotEmpty();
                } else {
                    assertThat(plugin.staticResources()).isEmpty();
                }
                // 油猴脚本扫描来源随外置 download-workbench 声明，内置插件均不声明。
                assertThat(plugin.userscripts()).isEmpty();
                // 下载队列作品类型随外置插件声明：illust 由 download-workbench 提供，novel 由 novel 提供。
                if (queueTypeContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.queueTypes()).isNotEmpty();
                } else {
                    assertThat(plugin.queueTypes()).isEmpty();
                }
                // 获取方式标签页：唯下载工作台声明
                if (downloadTabContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.downloadTabs()).isNotEmpty();
                } else {
                    assertThat(plugin.downloadTabs()).isEmpty();
                }
                // 落点 / 入口：内置清单中仅小说声明受邀访客落点；其余插件不得贡献落点
                if (landingContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.landings()).isNotEmpty();
                } else {
                    assertThat(plugin.landings()).isEmpty();
                }
                // 页面区块：gallery 外置后内置插件不贡献
                if (pageSectionContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.pageSections()).isNotEmpty();
                } else {
                    assertThat(plugin.pageSections()).isEmpty();
                }
                // 语义下钻：gallery 外置后内置插件不贡献
                if (drilldownContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.drilldowns()).isNotEmpty();
                } else {
                    assertThat(plugin.drilldowns()).isEmpty();
                }
                if (onboardingStepContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.guiOnboardingSteps()).isNotEmpty();
                } else {
                    assertThat(plugin.guiOnboardingSteps()).isEmpty();
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
