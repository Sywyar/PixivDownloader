package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.DownloadStatisticsService;
import top.sywyar.pixivdownload.core.download.DownloadedArtworkService;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkService;
import top.sywyar.pixivdownload.download.controller.BatchStateController;
import top.sywyar.pixivdownload.download.controller.DownloadQueueController;
import top.sywyar.pixivdownload.download.controller.DownloadStatusController;
import top.sywyar.pixivdownload.download.controller.DownloadTaskController;
import top.sywyar.pixivdownload.download.controller.PixivProxyController;
import top.sywyar.pixivdownload.download.controller.SSEController;
import top.sywyar.pixivdownload.download.schedule.work.ScheduledIllustWorkRunner;
import top.sywyar.pixivdownload.download.schedule.work.PixivScheduledIllustWorkExecutor;
import top.sywyar.pixivdownload.download.schedule.credential.PixivScheduledCredentialPolicy;
import top.sywyar.pixivdownload.download.schedule.guard.PixivOveruseExecutionGuard;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivCollectionScheduledSourceExecutor;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivFollowLatestScheduledSourceExecutor;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivMyBookmarksScheduledSourceExecutor;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivScheduledLocalWorkLookup;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivScheduledSourceSupport;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivSearchScheduledSourceExecutor;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivSeriesScheduledSourceExecutor;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivUserNewScheduledSourceExecutor;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivUserRequestScheduledSourceExecutor;
import top.sywyar.pixivdownload.download.state.BatchStateFiles;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.web.DownloadExtensionController;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.scripts.ScriptController;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.series.MangaSeriesService;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.schedule.OveruseWarningService;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;

/**
 * 下载工作台外置插件的 Bean 装配收敛点。子上下文只注册本配置类，不扫描应用根包；因此下载执行器、
 * Pixiv 代理、队列控制器、SSE、userscript 入口与下载页状态控制器均在这里显式声明，随插件生命周期注册 / 注销。
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
public class DownloadWorkbenchPluginConfiguration {

    @Bean
    public DownloadWorkbenchPlugin downloadWorkbenchPlugin() {
        return new DownloadWorkbenchPlugin();
    }

    @Bean
    public PixivFetchService pixivFetchService(@Qualifier("restTemplate") RestTemplate restTemplate,
                                               ObjectMapper objectMapper) {
        return new PixivFetchService(restTemplate, objectMapper);
    }

    @Bean
    public UgoiraService ugoiraService(@Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                                       AppMessages messages) {
        return new UgoiraService(downloadRestTemplate, messages);
    }

    @Bean
    public ArtworkDownloadExecutor artworkDownloadExecutor(DownloadSettings downloadSettings,
                                                           ApplicationEventPublisher eventPublisher,
                                                           PixivDatabase pixivDatabase,
                                                           UserQuotaService userQuotaService,
                                                           @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                                                           @Qualifier("taskScheduler") TaskScheduler taskScheduler,
                                                           @Qualifier("downloadTaskExecutor") TaskExecutor downloadTaskExecutor,
                                                           PixivBookmarkService pixivBookmarkService,
                                                           UgoiraService ugoiraService,
                                                           AuthorService authorService,
                                                           CollectionService collectionService,
                                                           MangaSeriesService mangaSeriesService,
                                                           ArtworkHashService artworkHashService,
                                                           WorkMetaCaptureService workMetaCaptureService,
                                                           DownloadStatisticsService downloadStatisticsService,
                                                           DownloadedArtworkService downloadedArtworkService,
                                                           AppMessages messages) {
        return new ArtworkDownloadExecutor(downloadSettings, eventPublisher, pixivDatabase, userQuotaService,
                downloadRestTemplate, taskScheduler, downloadTaskExecutor,
                pixivBookmarkService, ugoiraService, authorService,
                collectionService, mangaSeriesService, artworkHashService, workMetaCaptureService,
                downloadStatisticsService, downloadedArtworkService, messages);
    }

    /** 插画作品类型的计划任务下载执行器，经注册中心按 kind 解析。 */
    @Bean
    public ScheduledIllustWorkRunner scheduledIllustWorkRunner(ArtworkDownloader artworkDownloader) {
        return new ScheduledIllustWorkRunner(artworkDownloader);
    }

    @Bean
    public PixivScheduledIllustWorkExecutor pixivScheduledIllustWorkExecutor(
            PixivFetchService pixivFetchService,
            PixivDatabase pixivDatabase,
            ArtworkDownloader artworkDownloader,
            WorkMetaCaptureService workMetaCaptureService,
            ScheduledIllustWorkRunner scheduledIllustWorkRunner,
            PixivSchedulePersistenceCodec persistenceCodec,
            ObjectMapper objectMapper,
            DownloadSettings downloadSettings) {
        return new PixivScheduledIllustWorkExecutor(
                pixivFetchService, pixivDatabase, artworkDownloader, workMetaCaptureService,
                scheduledIllustWorkRunner, persistenceCodec, objectMapper, downloadSettings);
    }

    @Bean
    public PixivScheduledCredentialPolicy pixivScheduledCredentialPolicy(
            OveruseWarningService overuseWarningService,
            PixivSchedulePersistenceCodec persistenceCodec) {
        return new PixivScheduledCredentialPolicy(overuseWarningService, persistenceCodec);
    }

    @Bean
    public PixivOveruseExecutionGuard pixivOveruseExecutionGuard(
            OveruseWarningService overuseWarningService,
            PixivSchedulePersistenceCodec persistenceCodec,
            ObjectMapper objectMapper) {
        return new PixivOveruseExecutionGuard(
                overuseWarningService, persistenceCodec, objectMapper);
    }

    @Bean
    public PixivScheduledLocalWorkLookup pixivScheduledLocalWorkLookup(
            PixivDatabase pixivDatabase,
            ArtworkDownloader artworkDownloader,
            WorkQueryService workQueryService) {
        return (key, download) -> {
            long id = Long.parseLong(key.id());
            if (PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL.equals(key.workType())) {
                return download.redownloadDeleted()
                        ? workQueryService.hasActiveWork(WorkType.NOVEL, id)
                        : workQueryService.hasWork(WorkType.NOVEL, id);
            }
            if (download.redownloadDeleted()) {
                return download.verifyFiles()
                        ? !pixivDatabase.isArtworkDeleted(id)
                        && artworkDownloader.isArtworkDownloaded(id, true)
                        : pixivDatabase.hasActiveArtwork(id);
            }
            return download.verifyFiles()
                    ? artworkDownloader.isArtworkDownloaded(id, true)
                    : pixivDatabase.hasArtwork(id);
        };
    }

    @Bean
    public PixivScheduledSourceSupport pixivScheduledSourceSupport(
            ObjectMapper objectMapper,
            PixivFetchService pixivFetchService,
            PixivSchedulePersistenceCodec persistenceCodec,
            PixivScheduledLocalWorkLookup localWorkLookup,
            ScheduleConfig scheduleConfig) {
        return new PixivScheduledSourceSupport(
                objectMapper, pixivFetchService, persistenceCodec,
                localWorkLookup, scheduleConfig::getInboxCheckEvery);
    }

    @Bean
    public PixivUserNewScheduledSourceExecutor pixivUserNewScheduledSourceExecutor(
            PixivScheduledSourceSupport support) {
        return new PixivUserNewScheduledSourceExecutor(support);
    }

    @Bean
    public PixivUserRequestScheduledSourceExecutor pixivUserRequestScheduledSourceExecutor(
            PixivScheduledSourceSupport support) {
        return new PixivUserRequestScheduledSourceExecutor(support);
    }

    @Bean
    public PixivSearchScheduledSourceExecutor pixivSearchScheduledSourceExecutor(
            PixivScheduledSourceSupport support) {
        return new PixivSearchScheduledSourceExecutor(support);
    }

    @Bean
    public PixivSeriesScheduledSourceExecutor pixivSeriesScheduledSourceExecutor(
            PixivScheduledSourceSupport support) {
        return new PixivSeriesScheduledSourceExecutor(support);
    }

    @Bean
    public PixivMyBookmarksScheduledSourceExecutor pixivMyBookmarksScheduledSourceExecutor(
            PixivScheduledSourceSupport support) {
        return new PixivMyBookmarksScheduledSourceExecutor(support);
    }

    @Bean
    public PixivFollowLatestScheduledSourceExecutor pixivFollowLatestScheduledSourceExecutor(
            PixivScheduledSourceSupport support) {
        return new PixivFollowLatestScheduledSourceExecutor(support);
    }

    @Bean
    public PixivCollectionScheduledSourceExecutor pixivCollectionScheduledSourceExecutor(
            PixivScheduledSourceSupport support) {
        return new PixivCollectionScheduledSourceExecutor(support);
    }

    /** 插画作品类型的跨类型队列宿主操作适配器（取消 / 清空），经核心队列宿主注册中心按 queueType 解析。 */
    @Bean
    public QueueOperations illustQueueOperations(ArtworkDownloadExecutor artworkDownloadExecutor) {
        return new IllustQueueOperations(artworkDownloadExecutor);
    }

    @Bean
    public BatchStateFiles batchStateFiles(RuntimePathProvider runtimePathProvider,
                                           DownloadSettings downloadSettings) {
        return new BatchStateFiles(runtimePathProvider, downloadSettings);
    }

    @Bean
    public BatchStateController batchStateController(BatchStateFiles batchStateFiles,
                                                     ApplicationModeProvider applicationModeProvider) {
        return new BatchStateController(batchStateFiles, applicationModeProvider);
    }

    @Bean
    public DownloadTaskController downloadTaskController(ArtworkDownloadExecutor artworkDownloadExecutor,
                                                         ApplicationModeProvider applicationModeProvider,
                                                         RequestOwnerIdentityResolver requestOwnerIdentityResolver,
                                                         UserQuotaService userQuotaService,
                                                         MultiModeSettings multiModeSettings,
                                                         PixivDatabase pixivDatabase,
                                                         AppMessages messages) {
        return new DownloadTaskController(artworkDownloadExecutor, applicationModeProvider,
                requestOwnerIdentityResolver, userQuotaService, multiModeSettings, pixivDatabase, messages);
    }

    @Bean
    public DownloadQueueController downloadQueueController(QueueOperationRegistry queueOperationRegistry,
                                                           DownloadExtensionRegistry downloadExtensionRegistry,
                                                           RequestOwnerIdentityResolver requestOwnerIdentityResolver,
                                                           AppMessages messages) {
        return new DownloadQueueController(
                queueOperationRegistry, downloadExtensionRegistry, requestOwnerIdentityResolver, messages);
    }

    @Bean
    public DownloadExtensionController downloadExtensionController(DownloadExtensionRegistry extensionRegistry) {
        return new DownloadExtensionController(extensionRegistry);
    }

    @Bean
    public ScriptController scriptController(ScriptRegistry scriptRegistry,
                                             RateLimitService rateLimitService,
                                             AppMessages messages) {
        return new ScriptController(scriptRegistry, rateLimitService, messages);
    }

    @Bean
    public DownloadStatusController downloadStatusController(ArtworkDownloadExecutor artworkDownloadExecutor,
                                                             RequestOwnerIdentityResolver requestOwnerIdentityResolver,
                                                             AppMessages messages) {
        return new DownloadStatusController(artworkDownloadExecutor, requestOwnerIdentityResolver, messages);
    }

    @Bean
    public SSEController sseController(@Qualifier("taskScheduler") TaskScheduler taskScheduler,
                                       RequestOwnerIdentityResolver requestOwnerIdentityResolver,
                                       AppMessages messages,
                                       PluginStreamRegistry pluginStreamRegistry) {
        return new SSEController(taskScheduler, requestOwnerIdentityResolver, messages, pluginStreamRegistry);
    }

    @Bean
    public PixivProxyController pixivProxyController(ObjectMapper objectMapper,
                                                     @Qualifier("restTemplate") RestTemplate restTemplate,
                                                     PixivFetchService pixivFetchService,
                                                     ApplicationModeProvider applicationModeProvider,
                                                     RequestOwnerIdentityResolver requestOwnerIdentityResolver,
                                                     UserQuotaService userQuotaService,
                                                     MultiModeSettings multiModeSettings,
                                                     WorkVisibilityService workVisibilityService,
                                                     AppMessages messages) {
        return new PixivProxyController(objectMapper, restTemplate, pixivFetchService,
                applicationModeProvider, requestOwnerIdentityResolver,
                userQuotaService, multiModeSettings, workVisibilityService, messages);
    }
}
