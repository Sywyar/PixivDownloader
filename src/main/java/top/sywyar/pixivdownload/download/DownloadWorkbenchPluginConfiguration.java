package top.sywyar.pixivdownload.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.download.meta.WorkMetaCaptureService;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.notification.NotificationService;
import top.sywyar.pixivdownload.novel.download.NovelDownloader;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.plugin.ScheduledSourceRegistry;
import top.sywyar.pixivdownload.schedule.OveruseWarningService;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.ScheduleExecutor;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleRunner;
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.controller.ScheduleController;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.setup.SetupService;

/**
 * 下载工作台插件的 Bean 装配收敛点。当前承载随 schedule 能力收编进本插件的计划任务引擎 Bean：
 * 它们经 {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean} 显式提供
 * （对标 stats / novel 等插件的收敛形态）。
 * <p>
 * 下载执行机器（{@code ArtworkDownloadExecutor} 等）住 download 包但属核心、仍走根包扫描，不在此装配。
 * <p>
 * <b>schedule 数据访问边界：</b>{@code scheduled_tasks} / {@code scheduled_task_pending} 表归核心
 * （schema 由核心 contribution 保证）。收编进本插件的 schedule 引擎 Bean<b>不</b>直接拿 MyBatis
 * {@code ScheduledTaskMapper} 做自由 SQL，而是经核心 owned、根包扫描的语义 Store
 * {@code ScheduledTaskStore} 读写——它把 mapper 收拢为内部实现，由 Spring 注入这些 {@code @Bean}。
 * （{@code ScheduledTaskStore} 与 {@code ScheduledTaskMapper} 均不在本收敛、各自经根包 / mapper 扫描注册。）
 * <p>
 * <b>schedule → novel 既有耦合（显性化偏差）：</b>schedule 引擎对插画 / 小说下载与翻译实现
 * （{@code ArtworkDownloader} / {@code NovelDownloader} / {@code NovelMergeService} /
 * {@code NovelAutoTranslateService} / {@code NovelMetadataRepository}）的依赖是其作为跨域调度编排者的
 * 既有耦合，本收敛只是把它显式化在装配层。该耦合的彻底解耦待来源 provider 的执行契约落地后再拆，
 * 当前不在本插件收编 novel 业务 Bean、也不把 novel 依赖引入 plugin.api。
 */
@Configuration
public class DownloadWorkbenchPluginConfiguration {

    @Bean
    public DownloadWorkbenchPlugin downloadWorkbenchPlugin() {
        return new DownloadWorkbenchPlugin();
    }

    @Bean
    public ScheduleRunState scheduleRunState() {
        return new ScheduleRunState();
    }

    @Bean
    public ScheduleRunQueue scheduleRunQueue() {
        return new ScheduleRunQueue();
    }

    @Bean
    public OveruseWarningService overuseWarningService(PixivFetchService pixivFetchService) {
        return new OveruseWarningService(pixivFetchService);
    }

    @Bean
    public ScheduleExecutor scheduleExecutor(ScheduledTaskStore store,
                                             ScheduledSourceRegistry scheduledSourceRegistry,
                                             PixivFetchService pixivFetchService,
                                             PixivDatabase pixivDatabase,
                                             WorkMetaCaptureService workMetaCaptureService,
                                             ArtworkDownloader artworkDownloader,
                                             NovelDownloader novelDownloader,
                                             NovelMetadataRepository novelMetadataRepository,
                                             NovelMergeService novelMergeService,
                                             ScheduleConfig scheduleConfig,
                                             ScheduleRunState runState,
                                             ScheduleRunQueue runQueue,
                                             ObjectMapper objectMapper,
                                             OveruseWarningService overuseWarningService,
                                             NotificationService notificationService,
                                             AppMessages messages,
                                             SetupService setupService,
                                             DownloadConfig downloadConfig,
                                             @Qualifier("downloadTaskExecutor") TaskExecutor downloadTaskExecutor,
                                             @Qualifier("novelDownloadTaskExecutor") TaskExecutor novelDownloadTaskExecutor) {
        return new ScheduleExecutor(store, scheduledSourceRegistry, pixivFetchService, pixivDatabase,
                workMetaCaptureService, artworkDownloader, novelDownloader, novelMetadataRepository,
                novelMergeService, scheduleConfig, runState, runQueue, objectMapper, overuseWarningService,
                notificationService, messages, setupService, downloadConfig,
                downloadTaskExecutor, novelDownloadTaskExecutor);
    }

    @Bean
    public ScheduleService scheduleService(ScheduledTaskStore store,
                                           ScheduleExecutor executor,
                                           ScheduleConfig config,
                                           ScheduleRunState runState,
                                           ScheduleRunQueue runQueue,
                                           NovelAutoTranslateService novelAutoTranslateService) {
        return new ScheduleService(store, executor, config, runState, runQueue, novelAutoTranslateService);
    }

    @Bean
    public ScheduleRunner scheduleRunner(ScheduledTaskStore store,
                                         ScheduleExecutor executor,
                                         ScheduleConfig config,
                                         ScheduleRunState runState) {
        return new ScheduleRunner(store, executor, config, runState);
    }

    @Bean
    public ScheduleController scheduleController(ScheduleService scheduleService) {
        return new ScheduleController(scheduleService);
    }
}
