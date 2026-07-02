package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPluginConfiguration;
import top.sywyar.pixivdownload.duplicate.DuplicatePluginConfiguration;
import top.sywyar.pixivdownload.gallery.GalleryPluginConfiguration;
import top.sywyar.pixivdownload.novel.NovelPluginConfiguration;
import top.sywyar.pixivdownload.plugin.market.PluginMarketPluginConfiguration;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.schedule.ScheduleHostPluginConfiguration;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponseMapper;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.ScheduledSourceRegistry;

class RegisteredPluginsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // CorePluginConfiguration 的 databaseInitializer bean 需要 JdbcTemplate / AppMessages：
            // 用内存 SQLite 与测试 i18n 兜底（@PostConstruct 会真实建表，库随上下文丢弃）；
            // Duplicate / Gallery / Novel PluginConfiguration 收敛的业务 bean 依赖核心组件，一律 mock 兜底
            .withBean("applicationTaskExecutor", org.springframework.core.task.TaskExecutor.class,
                    org.springframework.core.task.SyncTaskExecutor::new)
            .withBean(top.sywyar.pixivdownload.core.hash.ImageHashMapper.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.hash.ImageHashMapper.class))
            // 核心 Hash 写入服务现为核心 root 扫描 @Service（不再由 DuplicatePluginConfiguration 装配）：
            // 本切片不加载它，duplicate 的扫描 / 回填经正向 plugin→core 依赖注入它，故 mock 兜底。
            .withBean(top.sywyar.pixivdownload.core.hash.ArtworkHashService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.hash.ArtworkHashService.class))
            .withBean(top.sywyar.pixivdownload.download.ArtworkFileLocator.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.ArtworkFileLocator.class))
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
            // schedule 引擎 Bean 由计划任务宿主插件配置 ScheduleHostPluginConfiguration 装配：
            // 其依赖的核心 / 下载机器在本切片里一律 mock 兜底（ScheduleConfig 用默认值实例），
            // 两个下载池按 bean 名提供 SyncTaskExecutor（ScheduleExecutor 经 @Qualifier 按名解析）。
            // scheduled_tasks 的读写经核心 owned 语义 Store ScheduledTaskStore（core.schedule 接口，实现 root 扫描，本切片 mock 兜底），
            // 宿主引擎 Bean 不直接依赖 MyBatis ScheduledTaskMapper。
            .withBean(top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore.class))
            .withBean(top.sywyar.pixivdownload.download.PixivFetchService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.PixivFetchService.class))
            .withBean(top.sywyar.pixivdownload.plugin.registry.ScheduledSourceRegistry.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.registry.ScheduledSourceRegistry.class))
            .withBean(top.sywyar.pixivdownload.download.meta.WorkMetaCaptureService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.meta.WorkMetaCaptureService.class))
            .withBean(top.sywyar.pixivdownload.download.ArtworkDownloader.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.ArtworkDownloader.class))
            // 下载工作台贡献的插画队列操作适配器（IllustQueueOperations）依赖具体下载执行器，本切片 mock 兜底。
            .withBean(top.sywyar.pixivdownload.download.ArtworkDownloadExecutor.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.ArtworkDownloadExecutor.class))
            .withBean(top.sywyar.pixivdownload.novel.download.NovelDownloader.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.download.NovelDownloader.class))
            // 小说下载端点两个 controller 随小说插件启停收编进 NovelPluginConfiguration，其依赖的
            // novel-core 下载服务（根包扫描）在本切片里 mock 兜底。
            .withBean(top.sywyar.pixivdownload.novel.download.NovelDownloadService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.download.NovelDownloadService.class))
            .withBean(top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository.class))
            .withBean(top.sywyar.pixivdownload.notification.NotificationService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.notification.NotificationService.class))
            .withBean(top.sywyar.pixivdownload.setup.SetupService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.setup.SetupService.class))
            .withBean(top.sywyar.pixivdownload.core.appconfig.DownloadConfig.class,
                    top.sywyar.pixivdownload.core.appconfig.DownloadConfig::new)
            .withBean(top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService.class))
            .withBean(top.sywyar.pixivdownload.schedule.ScheduleConfig.class,
                    top.sywyar.pixivdownload.schedule.ScheduleConfig::new)
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
            // 作品类型执行器注册中心用真实 Bean：收集下载工作台贡献的插画执行器 + 小说插件贡献的小说执行器，
            // 验证 schedule 引擎经核心注册中心装配、不再注入单一 novel delegate。
            .withUserConfiguration(
                    CorePluginConfiguration.class,
                    DownloadWorkbenchPluginConfiguration.class,
                    ScheduleHostPluginConfiguration.class,
                    GalleryPluginConfiguration.class,
                    NovelPluginConfiguration.class,
                    DuplicatePluginConfiguration.class,
                    PluginMarketPluginConfiguration.class,
                    PluginRegistry.class,
                    top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry.class,
                    DatabaseSchemaRegistry.class);

    @Test
    @DisplayName("七个内置插件经各自 Configuration 注册进 PluginRegistry（stats 已外置、不在内置清单）")
    void allPluginsRegistered() {
        runner.run(context -> {
            PluginRegistry registry = context.getBean(PluginRegistry.class);
            assertThat(registry.plugins())
                    .extracting(PixivFeaturePlugin::id)
                    .containsExactlyInAnyOrder(
                            "core", "download-workbench", "schedule", "gallery", "novel", "duplicate", "plugin-market");
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
    @DisplayName("各插件 contribution 边界：core 独占 schema、声明共享静态资源与横切 / 共享路由，duplicate/gallery/novel 占功能路由 + 导航 + 页面静态资源、download-workbench 占下载页路由 + 下载页静态资源 + userscript 来源、schedule 宿主占 /api/schedule 路由（无前端贡献）、i18n namespace 由五个有前端的内置插件声明（stats 已外置）")
    void emptyPluginsContributeNothing() {
        // 路由：四个 web 功能插件声明各自页面 / API；core 额外声明横切与跨页共享路由（监控 / 邀请 / 下载数据 /
        // 图片字节 / 作者 / 系列 / 收藏 / 代理 / 公开与共享静态依赖 / 本地放行特例，AuthFilter 切 registry 后由其派生）；
        // download-workbench 声明下载页与提交 / 队列 / 状态 API；计划任务宿主 schedule 声明 /api/schedule/** 路由
        //（下载页其余 API 是跨插件共享、留核心）。
        // 导航：四个 web 功能插件 + core（监控 / 邀请码管理基础入口）+ download-workbench（下载页入口，前端导航开槽）。
        Set<String> routeContributingPlugins = Set.of("core", "download-workbench", "schedule", "duplicate", "gallery", "novel", "plugin-market");
        // i18n namespace 由有前端页面的五个内置插件声明（页面跟插件走、核心/共享 namespace 留 core）；计划任务宿主
        // schedule 是无前端的调度宿主（只声明 /api/schedule 路由），不声明 i18n namespace；统计 stats 已外置、其
        // namespace 经外置插件 contribution 注册，不在内置清单。
        Set<String> i18nContributingPlugins = Set.of("core", "download-workbench", "duplicate", "gallery", "novel", "plugin-market");
        Set<String> navContributingPlugins = Set.of("core", "download-workbench", "duplicate", "gallery", "novel", "plugin-market");
        Set<String> staticResourceContributingPlugins = Set.of("core", "download-workbench", "duplicate", "gallery", "novel", "plugin-market");
        // 下载页扩展点：作品类型由「下载什么」的插件声明（download-workbench=illust，novel=novel）；
        // 获取方式标签页（怎么找作品）唯下载工作台声明。
        Set<String> queueTypeContributingPlugins = Set.of("download-workbench", "novel");
        Set<String> downloadTabContributingPlugins = Set.of("download-workbench");
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
        // coreColumnUsages 仍仅画廊 / 小说：download-workbench 收编的 schedule 引擎对 scheduled_tasks 的访问
        // 经核心 owned 语义 Store ScheduledTaskStore（core.schedule 接口，其核心实现 ScheduledTaskStoreImpl 再包装
        // 根包扫描的 MyBatis ScheduledTaskMapper，与 ArtworkDownloadExecutor 同口径属核心机器、不计入），
        // 收编的引擎 Bean 自身无直接 SQL，故无核心列使用声明。
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
                // i18n namespace 由有前端页面的六个插件声明：页面跟插件走、核心/共享
                // namespace（common/translate/tour 等）留 core、batch/userscript 归下载工作台；
                // 无前端的计划任务宿主 schedule 不声明 i18n namespace。
                if (i18nContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.i18n()).isNotEmpty();
                } else {
                    assertThat(plugin.i18n()).isEmpty();
                }
                // 路由：四个 web 功能插件 + core（横切 / 共享路由）+ download-workbench（schedule 路由）声明
                if (routeContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.routes()).isNotEmpty();
                } else {
                    assertThat(plugin.routes()).isEmpty();
                }
                // 导航：四个 web 功能插件 + core（监控 / 邀请码管理）+ download-workbench（下载页入口）全员声明，
                // 由 /api/navigation 按当前身份过滤后供前端跨插件导航 slot 渲染。
                if (navContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.navigation()).isNotEmpty();
                } else {
                    assertThat(plugin.navigation()).isEmpty();
                }
                // 静态资源：核心声明共享公共库（/js、/css、/vendor），四个 web 功能插件声明各自页面目录，
                // 下载工作台声明下载页静态资源目录（/pixiv-batch/）
                if (staticResourceContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.staticResources()).isNotEmpty();
                } else {
                    assertThat(plugin.staticResources()).isEmpty();
                }
                // 油猴脚本扫描来源唯下载工作台声明
                if (plugin.id().equals("download-workbench")) {
                    assertThat(plugin.userscripts()).isNotEmpty();
                } else {
                    assertThat(plugin.userscripts()).isEmpty();
                }
                // 下载队列作品类型：download-workbench（illust）+ novel（novel）声明
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
