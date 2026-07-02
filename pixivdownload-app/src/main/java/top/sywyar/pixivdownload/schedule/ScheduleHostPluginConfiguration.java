package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.notification.NotificationService;
import top.sywyar.pixivdownload.plugin.registry.ScheduledSourceRegistry;
import top.sywyar.pixivdownload.schedule.controller.ScheduleController;
import top.sywyar.pixivdownload.setup.SetupService;

/**
 * 计划任务宿主插件的 Bean 装配收敛点。承载调度安全壳的全部托管 Bean：执行器 / 服务 / tick runner / 控制器 /
 * 运行状态 / 运行队列 / 过度访问告警。它们经 {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean}
 * 显式提供（对标其它插件的收敛形态）。
 * <p>
 * 计划任务宿主是<b>必选插件</b>（{@link ScheduleHostPlugin#required()} 返回 {@code true}），不可经
 * {@code plugins.schedule.enabled} 禁用。故下列引擎 Bean（含唯一 {@code @Scheduled} tick {@link ScheduleRunner}）
 * <b>无条件装配、恒在场</b>，不标 {@code @ConditionalOnPluginEnabled}——后台调度 tick 恒运行、
 * {@code /api/schedule/**} 路由恒声明。
 * <p>
 * <b>数据访问边界：</b>{@code scheduled_tasks} / {@code scheduled_task_pending} 表归核心（schema 由核心
 * contribution 保证）。调度壳<b>不</b>直接拿 MyBatis {@code ScheduledTaskMapper} 做自由 SQL，而是经核心 owned、
 * 根包扫描的语义 Store {@code core.schedule.ScheduledTaskStore} 读写——由 Spring 注入这些 {@code @Bean}。
 * <p>
 * <b>依赖方向：</b>调度壳需要 Pixiv 抓取与作品下载等核心下载机器（{@link PixivFetchService} /
 * {@link ArtworkDownloader} / {@link WorkMetaCaptureService}，均由根包扫描装配、属核心机器），以及来源
 * 执行契约（{@code download.schedule.source}，住下载工作台域）；故本装配层依赖 download 包，<b>不</b> import 任何
 * novel 包类型。下载派发统一经核心契约 {@code core.schedule.work.ScheduledWorkRunner} + 注册中心
 * {@link ScheduledWorkRunnerRegistry} 按作品类型解析：插画执行器由下载工作台贡献、小说执行器由小说插件贡献，
 * 装配层只注入核心注册中心；来源发现 / 派发经来源 provider（下载工作台贡献、经来源注册中心解析）完成。
 * {@link NovelMetadataRepository} 是核心去重接口（{@code core.metadata.novel}）、本就属核心，保留。
 */
@Configuration
public class ScheduleHostPluginConfiguration {

    @Bean
    public ScheduleHostPlugin scheduleHostPlugin() {
        return new ScheduleHostPlugin();
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
