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
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.notification.NotificationService;
import top.sywyar.pixivdownload.plugin.ScheduledSourceRegistry;
import top.sywyar.pixivdownload.schedule.OveruseWarningService;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.ScheduleExecutor;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleRunner;
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.controller.ScheduleController;
import top.sywyar.pixivdownload.schedule.work.ScheduledIllustWorkRunner;
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
 * <b>schedule → novel 解耦（已清偿）：</b>schedule 引擎不再 import 任何 novel 包类型，本装配层亦然。下载派发统一
 * 经核心契约 {@code core.schedule.work.ScheduledWorkRunner} + 注册中心 {@code ScheduledWorkRunnerRegistry} 按
 * 作品类型解析：插画执行器 {@link ScheduledIllustWorkRunner} 由本配置贡献（薄包 {@code ArtworkDownloader}），
 * 小说执行器由小说插件贡献并显式装配；来源发现 / 派发经 {@code ScheduledSource} provider（下载工作台贡献、经来源
 * 注册中心解析）完成。本配置注入的是核心注册中心 {@code ScheduledWorkRunnerRegistry}（Spring 收集各方执行器
 * Bean），故装配层不 import 任何 novel 包类型。仅 {@code NovelMetadataRepository} 是核心去重接口
 * （{@code core.metadata.novel}）、本就属核心，保留。
 */
@Configuration
public class DownloadWorkbenchPluginConfiguration {

    @Bean
    public DownloadWorkbenchPlugin downloadWorkbenchPlugin() {
        return new DownloadWorkbenchPlugin();
    }

    /** 插画作品类型的计划任务下载执行器（薄包核心窄接缝 {@code ArtworkDownloader}），经注册中心按 kind 解析。 */
    @Bean
    public ScheduledIllustWorkRunner scheduledIllustWorkRunner(ArtworkDownloader artworkDownloader) {
        return new ScheduledIllustWorkRunner(artworkDownloader);
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
                                             ScheduledWorkRunnerRegistry workRunnerRegistry,
                                             NovelMetadataRepository novelMetadataRepository,
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
                workMetaCaptureService, artworkDownloader, workRunnerRegistry, novelMetadataRepository,
                scheduleConfig, runState, runQueue, objectMapper, overuseWarningService,
                notificationService, messages, setupService, downloadConfig,
                downloadTaskExecutor, novelDownloadTaskExecutor);
    }

    @Bean
    public ScheduleService scheduleService(ScheduledTaskStore store,
                                           ScheduleExecutor executor,
                                           ScheduleConfig config,
                                           ScheduleRunState runState,
                                           ScheduleRunQueue runQueue,
                                           ScheduledWorkRunnerRegistry workRunnerRegistry) {
        return new ScheduleService(store, executor, config, runState, runQueue, workRunnerRegistry);
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
