package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPluginConfiguration;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.notification.NotificationDispatcher;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("ScheduleHostPluginConfiguration 统一能力装配")
class ScheduleHostPluginConfigurationTest {

    @Test
    @DisplayName("计划作品池按中性上限执行并为跨任务超额作品排队")
    void scheduleWorkPoolUsesNeutralHostLimitAndQueuesOverflow() {
        ThreadPoolTaskExecutor executor =
                new ScheduleHostPluginConfiguration().scheduleWorkTaskExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize())
                    .isEqualTo(ScheduleExecutionEngine.MAX_WORK_IN_FLIGHT);
            assertThat(executor.getMaxPoolSize())
                    .isEqualTo(ScheduleExecutionEngine.MAX_WORK_IN_FLIGHT);
            assertThat(executor.getThreadPoolExecutor().getQueue())
                    .isInstanceOf(LinkedBlockingQueue.class);
            assertThat(executor.getThreadPoolExecutor().allowsCoreThreadTimeOut()).isTrue();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("异步代理由计划宿主拥有且编排与作品执行使用独立线程池")
    void scheduleHostOwnsExplicitAsyncExecutionLane() throws NoSuchMethodException {
        EnableAsync owner = ScheduleHostPluginConfiguration.class.getAnnotation(EnableAsync.class);
        assertThat(owner).isNotNull();
        assertThat(owner.proxyTargetClass()).isTrue();
        assertThat(DownloadWorkbenchPluginConfiguration.class.getAnnotation(EnableAsync.class)).isNull();

        Async async = ScheduleExecutor.class.getDeclaredMethod(
                "runTaskAsync",
                long.class,
                ScheduleRunState.Claim.class,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken.class,
                top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityLease.class)
                .getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("scheduleRunTaskExecutor");

        Bean runPool = ScheduleHostPluginConfiguration.class.getDeclaredMethod(
                        "scheduleRunTaskExecutor", ThreadPoolTaskExecutorBuilder.class)
                .getAnnotation(Bean.class);
        Bean workPool = ScheduleHostPluginConfiguration.class.getDeclaredMethod(
                        "scheduleWorkTaskExecutor")
                .getAnnotation(Bean.class);
        assertThat(runPool.name()).containsExactly("scheduleRunTaskExecutor");
        assertThat(workPool.name()).containsExactly("scheduleWorkTaskExecutor");
        assertThat(runPool.destroyMethod()).isEqualTo("shutdown");
        assertThat(workPool.destroyMethod()).isEqualTo("shutdown");
    }

    @Test
    @DisplayName("计划 tick 与工作台延迟任务显式使用子上下文本地调度器")
    void scheduleHostOwnsExplicitTaskScheduler() throws NoSuchMethodException {
        ScheduleHostPluginConfiguration configuration = new ScheduleHostPluginConfiguration();
        ThreadPoolTaskScheduler scheduler = configuration.downloadWorkbenchTaskScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(5);
            assertThat(scheduler.getThreadNamePrefix())
                    .isEqualTo("download-workbench-scheduler-");
            assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy())
                    .isTrue();
            assertThat(scheduler.getScheduledThreadPoolExecutor()
                    .getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
            assertThat(scheduler.getScheduledThreadPoolExecutor()
                    .getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
        } finally {
            scheduler.shutdown();
        }

        Bean schedulerBean = ScheduleHostPluginConfiguration.class.getDeclaredMethod(
                        "downloadWorkbenchTaskScheduler")
                .getAnnotation(Bean.class);
        Scheduled scheduled = ScheduleRunner.class.getDeclaredMethod("tick")
                .getAnnotation(Scheduled.class);
        assertThat(schedulerBean.name()).containsExactly("downloadWorkbenchTaskScheduler");
        assertThat(schedulerBean.destroyMethod()).isEqualTo("shutdown");
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo("downloadWorkbenchTaskScheduler");
    }

    @Test
    @DisplayName("执行器与服务共享宿主注入的计划能力注册表和通用执行引擎")
    void executorAndServiceShareHostCapabilityRegistry() {
        ScheduleHostPluginConfiguration configuration = new ScheduleHostPluginConfiguration();
        ScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduleConfig config = new ScheduleConfig();
        ScheduleRunState runState = new ScheduleRunState();
        ScheduleRunQueue runQueue = new ScheduleRunQueue();
        ObjectMapper objectMapper = new ObjectMapper();
        PixivSchedulePersistenceCodec persistenceCodec =
                configuration.pixivSchedulePersistenceCodec(objectMapper);
        ScheduleExecutionEngine executionEngine = mock(ScheduleExecutionEngine.class);

        ScheduleExecutor executor = configuration.scheduleExecutor(
                store,
                registry,
                runState,
                objectMapper,
                mock(NotificationDispatcher.class),
                mock(MessageResolver.class),
                mock(NamespaceMessageResolver.class),
                mock(UserDisplayNameProvider.class),
                executionEngine);
        ScheduleService service = configuration.scheduleService(
                store, executor, config, runState, runQueue,
                objectMapper, persistenceCodec, executionEngine,
                mock(PlatformTransactionManager.class), registry);

        assertThat(ReflectionTestUtils.getField(executor, "scheduleCapabilityRegistry"))
                .isSameAs(registry);
        assertThat(ReflectionTestUtils.getField(service, "scheduleCapabilityRegistry"))
                .isSameAs(registry);
        assertThat(ReflectionTestUtils.getField(executor, "scheduleExecutionEngine"))
                .isSameAs(executionEngine);
        assertThat(ReflectionTestUtils.getField(service, "scheduleExecutionEngine"))
                .isSameAs(executionEngine);
        assertThat(ReflectionTestUtils.getField(service, "persistenceCodec"))
                .isSameAs(persistenceCodec);
    }
}
