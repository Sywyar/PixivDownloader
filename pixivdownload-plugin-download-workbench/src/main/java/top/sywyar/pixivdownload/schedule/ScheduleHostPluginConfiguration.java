package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.core.notification.NotificationService;
import top.sywyar.pixivdownload.schedule.controller.ScheduleController;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.execution.ScheduleNetworkRouteResolver;
import top.sywyar.pixivdownload.schedule.execution.ScheduleWorkConcurrencyLimiter;
import top.sywyar.pixivdownload.schedule.persistence.migration.PixivLegacySchedulePersistenceDescriptorProvider;
import top.sywyar.pixivdownload.schedule.persistence.migration.PixivLegacyScheduledTaskMigrationAdapter;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

/**
 * 计划任务宿主插件的 Bean 装配收敛点。承载调度安全壳的全部托管 Bean：执行器 / 服务 / tick runner / 控制器 /
 * 运行状态 / 运行队列 / 过度访问告警。它们经 {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean}
 * 显式提供（对标其它插件的收敛形态）。
 * <p>
 * 计划任务安全壳随 download-workbench 外置包加载；包级 feature id 仍只有 {@code download-workbench}，
 * 不再向插件注册表暴露单独 {@code schedule} feature。故下列引擎 Bean（含唯一 {@code @Scheduled} tick
 * {@link ScheduleRunner}）随下载工作台生命周期装配 / 注销。
 * <p>
 * <b>数据访问边界：</b>{@code scheduled_tasks} / {@code scheduled_task_pending} 表归核心（schema 由核心
 * contribution 保证）。调度壳<b>不</b>直接拿 MyBatis {@code ScheduledTaskMapper} 做自由 SQL，而是经核心 owned、
 * 根包扫描的语义 Store {@code core.schedule.ScheduledTaskStore} 读写——由 Spring 注入这些 {@code @Bean}。
 * <p>
 * <b>依赖方向：</b>调度壳只经 plugin-api 计划契约与 {@link ScheduleCapabilityRegistry} generation lease
 * 调用来源、作品、凭证和 Guard 能力；具体 Pixiv / 小说执行实现由各 owner 的 child context 贡献。
 * 来源与作品执行器随 owner bundle 一次发布，不会出现来源已可见而执行器尚不可见的半代。
 */
@Configuration
@EnableScheduling
public class ScheduleHostPluginConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "schedule")
    public ScheduleConfig scheduleConfig() {
        return new ScheduleConfig();
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
    public PixivSchedulePersistenceCodec pixivSchedulePersistenceCodec(ObjectMapper objectMapper) {
        return new PixivSchedulePersistenceCodec(objectMapper);
    }

    @Bean
    public ScheduleWorkPersistenceCodec scheduleWorkPersistenceCodec(ObjectMapper objectMapper) {
        return new ScheduleWorkPersistenceCodec(objectMapper);
    }

    @Bean
    public ScheduleNetworkRouteResolver scheduleNetworkRouteResolver(OutboundProxySettings proxySettings) {
        return new ScheduleNetworkRouteResolver(proxySettings);
    }

    @Bean
    public ScheduleWorkConcurrencyLimiter scheduleWorkConcurrencyLimiter() {
        return new ScheduleWorkConcurrencyLimiter();
    }

    /**
     * 调度宿主共享的作品执行池。真实并发由 execution plan、作品执行器与
     * 进程级作品类型限制器共同约束；执行池仅提供与单任务最大在途数一致的线程上限。
     * 跨任务的合法超额作品进入队列，不会因共享池瞬时满载被误记为派发失败。
     */
    @Bean("scheduleWorkTaskExecutor")
    public ThreadPoolTaskExecutor scheduleWorkTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ScheduleExecutionEngine.MAX_WORK_IN_FLIGHT);
        executor.setMaxPoolSize(ScheduleExecutionEngine.MAX_WORK_IN_FLIGHT);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("schedule-work-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    @Bean
    public ScheduleExecutionEngine scheduleExecutionEngine(
            ScheduledTaskStore store,
            ScheduleCapabilityRegistry scheduleCapabilityRegistry,
            ScheduleRunState runState,
            ScheduleRunQueue runQueue,
            ScheduleConfig scheduleConfig,
            ScheduleWorkPersistenceCodec persistenceCodec,
            ScheduleNetworkRouteResolver routeResolver,
            @Qualifier("scheduleWorkTaskExecutor") TaskExecutor scheduleWorkTaskExecutor,
            ScheduleWorkConcurrencyLimiter workConcurrencyLimiter,
            ObjectMapper objectMapper) {
        return new ScheduleExecutionEngine(
                store, scheduleCapabilityRegistry, runState, runQueue, scheduleConfig,
                persistenceCodec, routeResolver, scheduleWorkTaskExecutor,
                workConcurrencyLimiter, objectMapper);
    }

    @Bean
    public PixivLegacyScheduledTaskMigrationAdapter pixivLegacyScheduledTaskMigrationAdapter(
            ObjectMapper objectMapper,
            PixivSchedulePersistenceCodec codec) {
        return new PixivLegacyScheduledTaskMigrationAdapter(objectMapper, codec);
    }

    @Bean
    public PixivLegacySchedulePersistenceDescriptorProvider
            pixivLegacySchedulePersistenceDescriptorProvider() {
        return new PixivLegacySchedulePersistenceDescriptorProvider();
    }

    @Bean
    public ScheduleExecutor scheduleExecutor(ScheduledTaskStore store,
                                             ScheduleCapabilityRegistry scheduleCapabilityRegistry,
                                             ScheduleRunState runState,
                                             ObjectMapper objectMapper,
                                             NotificationService notificationService,
                                             AppMessages messages,
                                             WebI18nBundleRegistry webI18nBundleRegistry,
                                             UserDisplayNameProvider userDisplayNameProvider,
                                             ScheduleExecutionEngine scheduleExecutionEngine) {
        return new ScheduleExecutor(
                store, scheduleCapabilityRegistry, runState, objectMapper,
                notificationService, messages, webI18nBundleRegistry,
                userDisplayNameProvider, scheduleExecutionEngine);
    }

    @Bean
    public ScheduleService scheduleService(ScheduledTaskStore store,
                                           ScheduleExecutor executor,
                                           ScheduleConfig config,
                                           ScheduleRunState runState,
                                           ScheduleRunQueue runQueue,
                                           ObjectMapper objectMapper,
                                           PixivSchedulePersistenceCodec persistenceCodec,
                                           ScheduleExecutionEngine scheduleExecutionEngine,
                                           PlatformTransactionManager transactionManager,
                                           ScheduleCapabilityRegistry scheduleCapabilityRegistry) {
        return new ScheduleService(store, executor, config, runState, runQueue,
                objectMapper, persistenceCodec, scheduleExecutionEngine,
                new TransactionTemplate(transactionManager), scheduleCapabilityRegistry);
    }

    @Bean
    public ScheduleRunner scheduleRunner(ScheduledTaskStore store,
                                         ScheduleExecutor executor,
                                         ScheduleConfig config,
                                         ScheduleRunState runState,
                                         ScheduleCapabilityRegistry scheduleCapabilityRegistry) {
        return new ScheduleRunner(store, executor, config, runState, scheduleCapabilityRegistry);
    }

    @Bean
    public ScheduleController scheduleController(ScheduleService scheduleService) {
        return new ScheduleController(scheduleService);
    }
}
