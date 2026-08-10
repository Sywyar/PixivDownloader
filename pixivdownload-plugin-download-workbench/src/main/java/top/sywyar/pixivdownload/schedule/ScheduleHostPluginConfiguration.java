package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.notification.NotificationDispatcher;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContributor;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.schedule.controller.ScheduleController;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.execution.ScheduleNetworkRouteResolver;
import top.sywyar.pixivdownload.schedule.execution.ScheduleWorkConcurrencyLimiter;
import top.sywyar.pixivdownload.schedule.notification.ScheduleNotificationTemplateContributor;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

/**
 * 计划任务宿主插件的 Bean 装配收敛点。承载调度安全壳的全部托管 Bean：执行器 / 服务 / tick runner / 控制器 /
 * 运行状态与运行队列。它们经 {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean}
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
 * <b>依赖方向：</b>调度壳只经 plugin-api 计划契约与 {@link ScheduleCapabilityAccess} generation lease
 * 调用来源、作品、凭证和 Guard 能力；具体实现由各 owner 的 child context 贡献。
 * 来源与作品执行器随 owner bundle 一次发布，不会出现来源已可见而执行器尚不可见的半代。
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
@EnableScheduling
public class ScheduleHostPluginConfiguration {

    @Bean
    public NotificationTemplateContributor scheduleNotificationTemplateContributor(
            @Qualifier("downloadWorkbenchMessages") MessageResolver messages,
            LocaleBundlePolicy localeBundlePolicy) {
        return new ScheduleNotificationTemplateContributor(messages, localeBundlePolicy.supportedLocales());
    }

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
     * 下载工作台 child context 自有的调度器，承载计划 tick、下载状态保留与 SSE 心跳。
     * 插件停止或重载时由 child context 关闭，不能回退到宿主 {@code taskScheduler}。
     */
    @Bean(name = "downloadWorkbenchTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler downloadWorkbenchTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("download-workbench-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    /**
     * 计划运行的编排池。它必须与作品执行池分离：编排线程会向作品池提交任务并同步等待结果，
     * 若二者复用同一池，多条计划运行可能占满全部线程后互相等待尚未执行的作品任务。
     */
    @Bean(name = "scheduleRunTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor scheduleRunTaskExecutor(
            ThreadPoolTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("schedule-run-").build();
    }

    /**
     * 调度宿主共享的作品执行池。真实并发由 execution plan、作品执行器与
     * 进程级作品类型限制器共同约束；执行池仅提供与单任务最大在途数一致的线程上限。
     * 跨任务的合法超额作品进入队列，不会因共享池瞬时满载被误记为派发失败。
     */
    @Bean(name = "scheduleWorkTaskExecutor", destroyMethod = "shutdown")
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
            ScheduleCapabilityAccess scheduleCapabilityRegistry,
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
    public ScheduleExecutor scheduleExecutor(ScheduledTaskStore store,
                                             ScheduleCapabilityAccess scheduleCapabilityRegistry,
                                             ScheduleRunState runState,
                                             ObjectMapper objectMapper,
                                             NotificationDispatcher notificationDispatcher,
                                             @Qualifier("downloadWorkbenchMessages") MessageResolver messages,
                                             NamespaceMessageResolver namespaceMessageResolver,
                                             UserDisplayNameProvider userDisplayNameProvider,
                                             ScheduleExecutionEngine scheduleExecutionEngine,
                                             PlatformTransactionManager transactionManager,
                                             ScheduleHostIdentity hostIdentity) {
        return new ScheduleExecutor(
                store, scheduleCapabilityRegistry, runState, objectMapper,
                notificationDispatcher, messages, namespaceMessageResolver,
                userDisplayNameProvider, scheduleExecutionEngine,
                new TransactionTemplate(transactionManager), hostIdentity);
    }

    @Bean
    public ScheduleCredentialService scheduleCredentialService(
            ScheduledTaskStore store,
            ScheduleRunState runState,
            ScheduleExecutionEngine scheduleExecutionEngine,
            ScheduleCapabilityAccess scheduleCapabilityRegistry,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        return new ScheduleCredentialService(
                store, runState, scheduleExecutionEngine, scheduleCapabilityRegistry,
                new TransactionTemplate(transactionManager), objectMapper);
    }

    @Bean
    public ScheduleService scheduleService(ScheduledTaskStore store,
                                           ScheduleExecutor executor,
                                           ScheduleConfig config,
                                           ScheduleRunState runState,
                                           ScheduleRunQueue runQueue,
                                           ObjectMapper objectMapper,
                                           ScheduleCredentialService credentialService,
                                           PlatformTransactionManager transactionManager,
                                           ScheduleCapabilityAccess scheduleCapabilityRegistry,
                                           ScheduleHostIdentity hostIdentity) {
        return new ScheduleService(store, executor, config, runState, runQueue,
                objectMapper, credentialService,
                new TransactionTemplate(transactionManager), scheduleCapabilityRegistry,
                hostIdentity);
    }

    @Bean
    public ScheduleRunner scheduleRunner(ScheduledTaskStore store,
                                         ScheduleExecutor executor,
                                         ScheduleConfig config,
                                         ScheduleRunState runState,
                                         ScheduleCapabilityAccess scheduleCapabilityRegistry,
                                         ScheduleHostIdentity hostIdentity) {
        return new ScheduleRunner(
                store, executor, config, runState, scheduleCapabilityRegistry, hostIdentity);
    }

    @Bean
    public ScheduleController scheduleController(
            ScheduleService scheduleService,
            @Qualifier("downloadWorkbenchMessages") MessageResolver messages) {
        return new ScheduleController(scheduleService, messages);
    }
}
