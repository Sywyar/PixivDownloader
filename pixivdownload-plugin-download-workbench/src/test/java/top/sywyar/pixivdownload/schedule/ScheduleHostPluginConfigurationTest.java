package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.notification.NotificationService;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.i18n.AppMessages;
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
    @DisplayName("执行器与服务共享宿主注入的同一 ScheduleCapabilityRegistry")
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
        OveruseWarningService overuseWarningService = mock(OveruseWarningService.class);
        TaskExecutor direct = Runnable::run;

        ScheduleExecutor executor = configuration.scheduleExecutor(
                store,
                registry,
                mock(PixivFetchService.class),
                mock(PixivDatabase.class),
                mock(WorkMetaCaptureService.class),
                mock(ArtworkDownloader.class),
                mock(WorkQueryService.class),
                config,
                runState,
                runQueue,
                objectMapper,
                persistenceCodec,
                overuseWarningService,
                mock(NotificationService.class),
                mock(AppMessages.class),
                mock(UserDisplayNameProvider.class),
                mock(DownloadSettings.class),
                direct,
                direct);
        ScheduleService service = configuration.scheduleService(
                store, executor, config, runState, runQueue,
                objectMapper, persistenceCodec, mock(ScheduleExecutionEngine.class),
                mock(PlatformTransactionManager.class), registry);

        assertThat(ReflectionTestUtils.getField(executor, "scheduleCapabilityRegistry"))
                .isSameAs(registry);
        assertThat(ReflectionTestUtils.getField(service, "scheduleCapabilityRegistry"))
                .isSameAs(registry);
        assertThat(ReflectionTestUtils.getField(executor, "persistenceCodec"))
                .isSameAs(persistenceCodec);
        assertThat(ReflectionTestUtils.getField(service, "persistenceCodec"))
                .isSameAs(persistenceCodec);
    }
}
