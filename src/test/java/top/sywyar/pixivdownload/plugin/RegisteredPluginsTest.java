package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPluginConfiguration;
import top.sywyar.pixivdownload.duplicate.DuplicatePluginConfiguration;
import top.sywyar.pixivdownload.gallery.GalleryPluginConfiguration;
import top.sywyar.pixivdownload.novel.NovelPluginConfiguration;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.stats.StatsPluginConfiguration;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegisteredPluginsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // CorePluginConfiguration 的 databaseInitializer bean 需要 JdbcTemplate / AppMessages，
            // StatsPluginConfiguration 的 statsRepository bean 需要 DataSource：
            // 用内存 SQLite 与测试 i18n 兜底（@PostConstruct 会真实建表，库随上下文丢弃）；
            // Duplicate / Gallery / Novel PluginConfiguration 收敛的业务 bean 依赖核心组件，一律 mock 兜底
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
            // schedule 引擎 Bean 随 schedule 能力收编进 DownloadWorkbenchPluginConfiguration：
            // 其依赖的核心 / 下载 / 小说机器在本切片里一律 mock 兜底（ScheduleConfig 用默认值实例），
            // 两个下载池按 bean 名提供 SyncTaskExecutor（ScheduleExecutor 经 @Qualifier 按名解析）。
            // scheduled_tasks 的读写经核心 owned 语义 Store ScheduledTaskStore（根包扫描，本切片 mock 兜底），
            // 收编的引擎 Bean 不再直接依赖 MyBatis ScheduledTaskMapper。
            .withBean(top.sywyar.pixivdownload.schedule.db.ScheduledTaskStore.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.schedule.db.ScheduledTaskStore.class))
            .withBean(top.sywyar.pixivdownload.download.PixivFetchService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.PixivFetchService.class))
            .withBean(top.sywyar.pixivdownload.plugin.ScheduledSourceRegistry.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.ScheduledSourceRegistry.class))
            .withBean(top.sywyar.pixivdownload.download.meta.WorkMetaCaptureService.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.meta.WorkMetaCaptureService.class))
            .withBean(top.sywyar.pixivdownload.download.ArtworkDownloader.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.download.ArtworkDownloader.class))
            .withBean(top.sywyar.pixivdownload.novel.download.NovelDownloader.class,
                    () -> org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.download.NovelDownloader.class))
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
    @DisplayName("各插件 contribution 边界：core 独占 schema、声明共享静态资源与横切 / 共享路由，stats/duplicate/gallery/novel 占功能路由 + 导航 + 页面静态资源、download-workbench 占 schedule 路由 + 下载页静态资源 + userscript 来源、i18n namespace 六插件全员声明")
    void emptyPluginsContributeNothing() {
        // 路由：四个 web 功能插件声明各自页面 / API；core 额外声明横切与跨页共享路由（监控 / 邀请 / 下载数据 /
        // 图片字节 / 作者 / 系列 / 收藏 / 代理 / 公开与共享静态依赖 / 本地放行特例，AuthFilter 切 registry 后由其派生）；
        // download-workbench 随 schedule 能力收编声明 /api/schedule/** 路由（下载页其余 API 是跨插件共享、留核心）。
        // 导航：仍仅四个 web 功能插件声明（core / download-workbench 不出现在导航栏）。
        Set<String> routeContributingPlugins = Set.of("core", "download-workbench", "stats", "duplicate", "gallery", "novel");
        Set<String> navContributingPlugins = Set.of("stats", "duplicate", "gallery", "novel");
        Set<String> staticResourceContributingPlugins = Set.of("core", "download-workbench", "stats", "duplicate", "gallery", "novel");
        // coreColumnUsages 仍仅画廊 / 小说：download-workbench 收编的 schedule 引擎对 scheduled_tasks 的访问
        // 经核心 owned 语义 Store ScheduledTaskStore（它再包装根包扫描的 MyBatis ScheduledTaskMapper，
        // 与 ArtworkDownloadExecutor 同口径属核心机器、不计入），收编的引擎 Bean 自身无直接 SQL，故无核心列使用声明。
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
                // i18n namespace 静态 map 退役后由六插件全员声明：页面跟插件走、核心/共享
                // namespace（common/translate/tour 等）留 core、batch/userscript 归下载工作台
                assertThat(plugin.i18n()).isNotEmpty();
                // 路由：四个 web 功能插件 + core（横切 / 共享路由）+ download-workbench（schedule 路由）声明
                if (routeContributingPlugins.contains(plugin.id())) {
                    assertThat(plugin.routes()).isNotEmpty();
                } else {
                    assertThat(plugin.routes()).isEmpty();
                }
                // 导航：仅四个 web 功能插件声明（无私有表，statistics、artwork_image_hashes 与 artworks 系
                // 均按卸载投影测试归 core；core 声明横切路由但不进导航栏）
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
