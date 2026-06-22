package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadController;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadLegacyForwardController;
import top.sywyar.pixivdownload.novel.controller.NovelGalleryController;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.download.NovelDownloader;
import top.sywyar.pixivdownload.novel.download.NovelQueueOperations;
import top.sywyar.pixivdownload.novel.download.ScheduledNovelDownloadDelegate;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.novel.translation.NovelTranslationService;

/**
 * novel 插件的 Bean 装配收敛点：小说画廊侧业务 Bean（含 {@code @RestController}）均经
 * {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean} 显式提供。
 * 收敛范围为小说画廊侧三个类（Controller / Service / BatchService）+ 小说下载端点两个 controller
 * （{@link NovelDownloadController} 与旧址兼容垫片 {@link NovelDownloadLegacyForwardController}，随小说
 * 启停以满足「禁用 → 新旧小说路径一并 404」）；novel-core 其余部分（下载 / 正文 / 翻译 / TTS / AI 听书）
 * 不强拆，其 Bean 仍走根包扫描、在 controller 装配处按参数注入。
 * {@link NovelGalleryRepository} 已收编进核心数据层（{@code core.metadata}），作为根包扫描的
 * 核心 Bean 注入到画廊 controller，不再由本配置提供。
 * <p>
 * 计划任务的小说下载执行器 {@link ScheduledNovelDownloadDelegate}（实现核心契约
 * {@code core.schedule.work.ScheduledWorkRunner}）也随小说插件生命周期归属、由本配置显式装配：小说插件被禁 /
 * 卸载时它随之缺席，调度壳解析不到 {@code novel} 执行器即把小说计划任务标记为不可用并干净挂起（不偷跑、不启动失败）。
 * <p>
 * 插件 descriptor {@link NovelPlugin} 始终注册（{@code allPlugins()} / schema 合并 / disabledPlugins 都依赖
 * 全部 descriptor 在场）；其余业务 Bean 经 {@link ConditionalOnPluginEnabled} 随 {@code plugins.novel.enabled}
 * 装配 / 缺席——禁用本插件时小说画廊 / 下载 controller 与小说执行器都不在场，新旧小说下载 URL 因「未声明即 404」
 * 不可达，残留小说计划任务经作品类型执行器解析门走 {@code SOURCE_UNAVAILABLE} 干净挂起。novel-core 根包扫描的
 * 下载 / 正文 / 翻译机器不在本配置、不随开关移除（核心数据写入不受插件开关影响）。
 */
@Configuration
public class NovelPluginConfiguration {

    @Bean
    public NovelPlugin novelPlugin() {
        return new NovelPlugin();
    }

    /**
     * 小说作品类型的跨类型队列宿主操作适配器（清空 / 按 owner 清空；无单项取消）。随小说插件启停：禁用时缺席，
     * 核心队列宿主注册中心解析不到 {@code novel} 操作、跨类型清空只作用于在场类型。
     */
    @Bean
    @ConditionalOnPluginEnabled("novel")
    public QueueOperations novelQueueOperations(NovelDownloadService novelDownloadService) {
        return new NovelQueueOperations(novelDownloadService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public ScheduledNovelDownloadDelegate scheduledNovelDownloadDelegate(
            NovelDownloader novelDownloader,
            NovelMergeService novelMergeService,
            NovelAutoTranslateService novelAutoTranslateService) {
        return new ScheduledNovelDownloadDelegate(
                novelDownloader, novelMergeService, novelAutoTranslateService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelGalleryService novelGalleryService(WorkQueryService workQueryService,
                                                   WorkMetadataRepository workMetadataRepository,
                                                   WorkDeletionService workDeletionService) {
        return new NovelGalleryService(workQueryService, workMetadataRepository, workDeletionService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelBatchService novelBatchService(NovelGalleryService novelGalleryService,
                                               WorkMetadataRepository workMetadataRepository,
                                               WorkAssetService workAssetService,
                                               CollectionService collectionService,
                                               UserQuotaService userQuotaService,
                                               MultiModeConfig multiModeConfig,
                                               ObjectMapper objectMapper) {
        return new NovelBatchService(novelGalleryService, workMetadataRepository, workAssetService,
                collectionService, userQuotaService, multiModeConfig, objectMapper);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelGalleryController novelGalleryController(NovelGalleryService novelGalleryService,
                                                         NovelBatchService novelBatchService,
                                                         NovelMergeService novelMergeService,
                                                         NovelSeriesService novelSeriesService,
                                                         NovelTranslationService novelTranslationService,
                                                         NovelDatabase novelDatabase,
                                                         NovelGalleryRepository novelGalleryRepository,
                                                         WorkAssetService workAssetService,
                                                         GuestAccessGuard guestAccessGuard,
                                                         AppMessages messages) {
        return new NovelGalleryController(novelGalleryService, novelBatchService, novelMergeService,
                novelSeriesService, novelTranslationService, novelDatabase, novelGalleryRepository,
                workAssetService, guestAccessGuard, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDownloadController novelDownloadController(NovelDownloadService novelDownloadService,
                                                          NovelAutoTranslateService novelAutoTranslateService,
                                                          NovelDatabase novelDatabase,
                                                          SetupService setupService,
                                                          UserQuotaService userQuotaService,
                                                          MultiModeConfig multiModeConfig,
                                                          AppMessages messages) {
        return new NovelDownloadController(novelDownloadService, novelAutoTranslateService, novelDatabase,
                setupService, userQuotaService, multiModeConfig, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDownloadLegacyForwardController novelDownloadLegacyForwardController() {
        return new NovelDownloadLegacyForwardController();
    }
}
