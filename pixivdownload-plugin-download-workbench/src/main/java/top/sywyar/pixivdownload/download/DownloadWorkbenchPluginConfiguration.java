package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.DownloadStatisticsService;
import top.sywyar.pixivdownload.core.download.DownloadedArtworkService;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkService;
import top.sywyar.pixivdownload.download.controller.BatchStateController;
import top.sywyar.pixivdownload.download.controller.DownloadQueueController;
import top.sywyar.pixivdownload.download.controller.DownloadStatusController;
import top.sywyar.pixivdownload.download.controller.DownloadTaskController;
import top.sywyar.pixivdownload.download.controller.PixivProxyController;
import top.sywyar.pixivdownload.download.controller.SSEController;
import top.sywyar.pixivdownload.download.schedule.work.ScheduledIllustWorkRunner;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.registry.DownloadTabRegistry;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.web.DownloadExtensionController;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.scripts.ScriptController;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.series.MangaSeriesService;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

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
    public ArtworkDownloadExecutor artworkDownloadExecutor(DownloadConfig downloadConfig,
                                                           ApplicationEventPublisher eventPublisher,
                                                           PixivDatabase pixivDatabase,
                                                           UserQuotaService userQuotaService,
                                                           @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                                                           @Qualifier("taskScheduler") TaskScheduler taskScheduler,
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
        return new ArtworkDownloadExecutor(downloadConfig, eventPublisher, pixivDatabase, userQuotaService,
                downloadRestTemplate, taskScheduler, pixivBookmarkService, ugoiraService, authorService,
                collectionService, mangaSeriesService, artworkHashService, workMetaCaptureService,
                downloadStatisticsService, downloadedArtworkService, messages);
    }

    /** 插画作品类型的计划任务下载执行器，经注册中心按 kind 解析。 */
    @Bean
    public ScheduledIllustWorkRunner scheduledIllustWorkRunner(ArtworkDownloader artworkDownloader) {
        return new ScheduledIllustWorkRunner(artworkDownloader);
    }

    /** 插画作品类型的跨类型队列宿主操作适配器（取消 / 清空），经核心队列宿主注册中心按 queueType 解析。 */
    @Bean
    public QueueOperations illustQueueOperations(ArtworkDownloadExecutor artworkDownloadExecutor) {
        return new IllustQueueOperations(artworkDownloadExecutor);
    }

    @Bean
    public BatchStateController batchStateController(DownloadConfig downloadConfig,
                                                     SetupService setupService) {
        return new BatchStateController(downloadConfig, setupService);
    }

    @Bean
    public DownloadTaskController downloadTaskController(ArtworkDownloadExecutor artworkDownloadExecutor,
                                                         SetupService setupService,
                                                         UserQuotaService userQuotaService,
                                                         MultiModeConfig multiModeConfig,
                                                         PixivDatabase pixivDatabase,
                                                         AppMessages messages) {
        return new DownloadTaskController(artworkDownloadExecutor, setupService, userQuotaService,
                multiModeConfig, pixivDatabase, messages);
    }

    @Bean
    public DownloadQueueController downloadQueueController(QueueOperationRegistry queueOperationRegistry,
                                                           SetupService setupService,
                                                           AppMessages messages) {
        return new DownloadQueueController(queueOperationRegistry, setupService, messages);
    }

    @Bean
    public DownloadExtensionController downloadExtensionController(QueueTypeRegistry queueTypeRegistry,
                                                                   DownloadTabRegistry downloadTabRegistry,
                                                                   WebUiSlotRegistry webUiSlotRegistry) {
        return new DownloadExtensionController(queueTypeRegistry, downloadTabRegistry, webUiSlotRegistry);
    }

    @Bean
    public ScriptController scriptController(ScriptRegistry scriptRegistry,
                                             RateLimitService rateLimitService,
                                             AppMessages messages) {
        return new ScriptController(scriptRegistry, rateLimitService, messages);
    }

    @Bean
    public DownloadStatusController downloadStatusController(ArtworkDownloadExecutor artworkDownloadExecutor,
                                                             SetupService setupService,
                                                             AppMessages messages) {
        return new DownloadStatusController(artworkDownloadExecutor, setupService, messages);
    }

    @Bean
    public SSEController sseController(@Qualifier("taskScheduler") TaskScheduler taskScheduler,
                                       SetupService setupService,
                                       AppMessages messages,
                                       PluginStreamRegistry pluginStreamRegistry) {
        return new SSEController(taskScheduler, setupService, messages, pluginStreamRegistry);
    }

    @Bean
    public PixivProxyController pixivProxyController(ObjectMapper objectMapper,
                                                     @Qualifier("restTemplate") RestTemplate restTemplate,
                                                     PixivFetchService pixivFetchService,
                                                     SetupService setupService,
                                                     UserQuotaService userQuotaService,
                                                     MultiModeConfig multiModeConfig,
                                                     GuestAccessGuard guestAccessGuard,
                                                     AppMessages messages) {
        return new PixivProxyController(objectMapper, restTemplate, pixivFetchService, setupService,
                userQuotaService, multiModeConfig, guestAccessGuard, messages);
    }
}
