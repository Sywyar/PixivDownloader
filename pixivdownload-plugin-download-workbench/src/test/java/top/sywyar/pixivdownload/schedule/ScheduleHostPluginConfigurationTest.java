package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import top.sywyar.pixivdownload.core.notification.NotificationService;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
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
    @DisplayName("执行器与服务共享宿主注入的计划能力注册表和通用执行引擎")
    void executorAndServiceShareHostCapabilityRegistry() {
        ScheduleHostPluginConfiguration configuration = new ScheduleHostPluginConfiguration();
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
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
                mock(NotificationService.class),
                mock(AppMessages.class),
                mock(WebI18nBundleRegistry.class),
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
