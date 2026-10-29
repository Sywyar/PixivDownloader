package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.execution.ScheduleNetworkRouteResolver;
import top.sywyar.pixivdownload.schedule.execution.ScheduleWorkConcurrencyLimiter;
import top.sywyar.pixivdownload.schedule.notification.ScheduleNotificationTemplateContributor;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

/** 宿主持有计划编排、独立执行池与持久化状态机；业务能力经精确 publication 调用。 */
@Configuration
@EnableAsync(proxyTargetClass = true)
@EnableScheduling
public class ScheduleHostConfiguration {

    @Bean
    public NotificationTemplateContributor scheduleNotificationTemplateContributor(
            MessageResolver messages,
            LocaleBundlePolicy localeBundlePolicy) {
        return new ScheduleNotificationTemplateContributor(messages, localeBundlePolicy.supportedLocales());
    }

    @Bean
    @ConfigurationProperties(prefix = "schedule")
    public ScheduleConfig scheduleConfig(Environment environment) {
        return Binder.get(environment).bind("schedule", ScheduleConfig.class)
                .orElseGet(ScheduleConfig::new);
    }

    @Bean
    public ScheduleHostLifecycle scheduleHostLifecycle(ScheduleRunState runState) {
        return new ScheduleHostLifecycle(runState);
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
     * 宿主计划 tick 的独立调度器，随应用上下文关闭。
     */
    @Bean(name = "scheduleTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler scheduleTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("schedule-tick-");
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
            ObjectMapper objectMapper,
            top.sywyar.pixivdownload.core.database.DatabaseAvailability database) {
        return new ScheduleExecutionEngine(
                store, scheduleCapabilityRegistry, runState, runQueue, scheduleConfig,
                persistenceCodec, routeResolver, scheduleWorkTaskExecutor,
                workConcurrencyLimiter, objectMapper, database);
    }

    @Bean
    public ScheduleExecutor scheduleExecutor(ScheduledTaskStore store,
                                             ScheduleCapabilityAccess scheduleCapabilityRegistry,
                                             ScheduleRunState runState,
                                             ObjectMapper objectMapper,
                                             NotificationDispatcher notificationDispatcher,
                                             MessageResolver messages,
                                             NamespaceMessageResolver namespaceMessageResolver,
                                             UserDisplayNameProvider userDisplayNameProvider,
                                             ScheduleExecutionEngine scheduleExecutionEngine,
                                             PlatformTransactionManager transactionManager,
                                             ScheduleHostLifecycle hostLifecycle) {
        return new ScheduleExecutor(
                store, scheduleCapabilityRegistry, runState, objectMapper,
                notificationDispatcher, messages, namespaceMessageResolver,
                userDisplayNameProvider, scheduleExecutionEngine,
                new TransactionTemplate(transactionManager), hostLifecycle);
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
                                           ScheduleHostLifecycle hostLifecycle,
            top.sywyar.pixivdownload.core.database.DatabaseAvailability database) {
        return new ScheduleService(store, executor, config, runState, runQueue,
                objectMapper, credentialService,
                new TransactionTemplate(transactionManager), scheduleCapabilityRegistry,
                hostLifecycle, database);
    }

    @Bean
    public ScheduleRunner scheduleRunner(ScheduledTaskStore store,
                                         ScheduleExecutor executor,
                                         ScheduleConfig config,
                                         ScheduleRunState runState,
                                         ScheduleHostLifecycle hostLifecycle,
            top.sywyar.pixivdownload.core.database.DatabaseAvailability database) {
        return new ScheduleRunner(
                store, executor, config, runState, hostLifecycle, database);
    }

}
