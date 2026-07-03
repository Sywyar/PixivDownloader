package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.gallery.GalleryPluginConfiguration;
import top.sywyar.pixivdownload.novel.NovelPluginConfiguration;
import top.sywyar.pixivdownload.plugin.market.PluginMarketPluginConfiguration;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

class RegisteredPluginsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // CorePluginConfiguration 的 databaseInitializer bean 需要 JdbcTemplate / AppMessages：
            // 用内存 SQLite 与测试 i18n 兜底（@PostConstruct 会真实建表，库随上下文丢弃）；
            // Gallery / Novel PluginConfiguration 收敛的业务 bean 依赖核心组件，一律 mock 兜底
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
            .withBean(top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService.class))
            .withBean(top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository.class))
            .withBean(top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService.class))
            .withBean(top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService.class))
            .withBean(top.sywyar.pixivdownload.collection.CollectionService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.collection.CollectionService.class))
            .withBean(top.sywyar.pixivdownload.quota.UserQuotaService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.quota.UserQuotaService.class))
            .withBean(top.sywyar.pixivdownload.setup.guest.GuestAccessGuard.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.setup.guest.GuestAccessGuard.class))
            .withBean(top.sywyar.pixivdownload.novel.export.NovelMergeService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.export.NovelMergeService.class))
            .withBean(top.sywyar.pixivdownload.novel.NovelSeriesService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.NovelSeriesService.class))
            .withBean(top.sywyar.pixivdownload.novel.translation.NovelTranslationService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.translation.NovelTranslationService.class))
            .withBean(top.sywyar.pixivdownload.novel.db.NovelDatabase.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.db.NovelDatabase.class))
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
            .withBean(top.sywyar.pixivdownload.novel.download.NovelDownloader.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.download.NovelDownloader.class))
            // 小说下载端点两个 controller 随小说插件启停收编进 NovelPluginConfiguration，其依赖的
            // novel-core 下载服务（根包扫描）在本切片里 mock 兜底。
            .withBean(top.sywyar.pixivdownload.novel.download.NovelDownloadService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.download.NovelDownloadService.class))
            .withBean(top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository.class))
            .withBean(top.sywyar.pixivdownload.core.notification.NotificationService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.notification.NotificationService.class))
            .withBean(top.sywyar.pixivdownload.setup.SetupService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.setup.SetupService.class))
            .withBean(top.sywyar.pixivdownload.core.appconfig.DownloadConfig.class,
                    top.sywyar.pixivdownload.core.appconfig.DownloadConfig::new)
            .withBean(top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService.class))
            .withBean("downloadTaskExecutor", org.springframework.core.task.TaskExecutor.class,
                    org.springframework.core.task.SyncTaskExecutor::new)
            .withBean("novelDownloadTaskExecutor", org.springframework.core.task.TaskExecutor.class,
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
            // 作品类型执行器注册中心用真实 Bean：收集小说插件贡献的小说执行器。
            .withUserConfiguration(
                    CorePluginConfiguration.class,
                    GalleryPluginConfiguration.class,
                    NovelPluginConfiguration.class,
                    PluginMarketPluginConfiguration.class,
                    PluginRegistry.class,
                    top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry.class,
                    DatabaseSchemaRegistry.class);

    @Test
    @DisplayName("四个内置插件经各自 Configuration 注册进 PluginRegistry（download-workbench/stats/duplicate 已外置、不在内置清单）")
    void allPluginsRegistered() {
        runner.run(context -> {
            PluginRegistry registry = context.getBean(PluginRegistry.class);
            assertThat(registry.plugins())
                    .extracting(PixivFeaturePlugin::id)
                    .containsExactlyInAnyOrder(
                            "core", "gallery", "novel", "plugin-market");
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
    @DisplayName("各内置插件 contribution 边界：core 独占 schema，gallery/novel/plugin-market 占各自功能贡献，download-workbench/duplicate 已外置")
    void emptyPluginsContributeNothing() {
        // 路由：四个 web 功能插件声明各自页面 / API；core 额外声明横切与跨页共享路由（监控 / 邀请 / 下载数据 /
        // 图片字节 / 作者 / 系列 / 收藏 / 代理 / 公开与共享静态依赖 / 本地放行特例，AuthFilter 切 registry 后由其派生）；
        // download-workbench 与 schedule 安全壳已在外置必需插件包中，duplicate 也随外置插件包接入。
        Set<String> routeContributingPlugins = Set.of("core", "gallery", "novel", "plugin-market");
        Set<String> i18nContributingPlugins = Set.of("core", "gallery", "novel", "plugin-market");
        Set<String> navContributingPlugins = Set.of("core", "gallery", "novel", "plugin-market");
        Set<String> staticResourceContributingPlugins = Set.of("core", "gallery", "novel", "plugin-market");
        Set<String> queueTypeContributingPlugins = Set.of("novel");
        Set<String> downloadTabContributingPlugins = Set.of();
        // 落点 / 入口（landing）：受邀访客落点唯画廊（priority 20）+ 小说（priority 30）声明，
        // 锁死契约面——其它插件不得静默声明落点（避免借落点 / 导航 order 间接改变业务落点）。
        Set<String> landingContributingPlugins = Set.of("gallery", "novel");
        // 页面区块（page sections）：唯画廊向统计页 placement 贡献「视图 / 收藏夹」借用区块，
        // 统计页只声明空 section slot——其它插件不贡献区块（宿主不需要知道是哪个插件）。
        Set<String> pageSectionContributingPlugins = Set.of("gallery");
        // 语义下钻（drilldowns）：唯画廊向统计页两个语义 placement（stats.top-authors / stats.top-tags）贡献
        // 下钻模板——统计页只认得语义 placement，其它插件不贡献下钻（宿主不需要知道是哪个插件）。
        Set<String> drilldownContributingPlugins = Set.of("gallery");
        // GUI 引导步骤：唯画廊贡献打开本地页面并完成网页操作指引的步骤；宿主只按中性 step contract 渲染。
        Set<String> onboardingStepContributingPlugins = Set.of("gallery");
        // coreColumnUsages 仍仅画廊 / 小说；download-workbench 的 schedule 引擎外置后不进内置清单。
        Set<String> coreColumnUsingPlugins = Set.of("gallery", "novel");
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
                if (coreColumnUsingPlugins.contains(plugin.id())) {
                    // 画廊/小说声明各自收敛范围内直接 SQL 仓库触及的核心列（只读使用契约，无私有表）
                    assertThat(plugin.coreColumnUsages()).isNotEmpty();
                } else {
                    assertThat(plugin.coreColumnUsages()).isEmpty();
                }
                // i18n namespace 由内置前端插件声明；batch/userscript 归外置 download-workbench。
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
                // 导航：内置 web 功能插件 + core（监控 / 邀请码管理）声明，
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
                // 下载队列作品类型：内置清单中仅 novel 声明；illust 随外置 download-workbench 声明。
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
                // 落点 / 入口：唯画廊 + 小说声明受邀访客落点；其余插件不得贡献落点
                if (landingContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.landings()).isNotEmpty();
                } else {
                    assertThat(plugin.landings()).isEmpty();
                }
                // 页面区块：唯画廊向统计页贡献借用区块；其余插件不贡献
                if (pageSectionContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.pageSections()).isNotEmpty();
                } else {
                    assertThat(plugin.pageSections()).isEmpty();
                }
                // 语义下钻：唯画廊向统计页贡献作者 / 标签下钻模板；其余插件不贡献
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
    @DisplayName("插件声明的核心列使用均能在受管 schema 中找到对应表列（归一化比对）")
    void coreColumnUsagesResolveAgainstManagedSchema() {
        ManagedDatabaseSchema.DatabaseSchema schema =
                DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema();
        for (PixivFeaturePlugin plugin : BuiltInPlugins.createAll()) {
            for (CoreColumnUsage usage : plugin.coreColumnUsages()) {
                ManagedDatabaseSchema.TableSpec table = schema.tables().values().stream()
                        .filter(spec -> spec.name()
                                .equals(ManagedDatabaseSchema.normalizeIdentifier(usage.table())))
                        .findFirst()
                        .orElse(null);
                assertThat(table)
                        .as("插件 %s 声明的核心表 %s 应在受管 schema 中", plugin.id(), usage.table())
                        .isNotNull();
                Set<String> columns = table.columns().stream()
                        .map(ManagedDatabaseSchema.ColumnSpec::name)
                        .collect(Collectors.toSet());
                for (String column : usage.columns()) {
                    assertThat(columns)
                            .as("插件 %s 声明的核心列 %s.%s 应在受管 schema 中",
                                    plugin.id(), usage.table(), column)
                            .contains(ManagedDatabaseSchema.normalizeIdentifier(column));
                }
            }
        }
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
