package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.notification.NotificationService;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.setup.SetupService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("ScheduleHostPluginConfiguration 统一能力装配")
class ScheduleHostPluginConfigurationTest {

    @Test
    @DisplayName("执行器与服务共享宿主注入的同一 ScheduleCapabilityRegistry")
    void executorAndServiceShareHostCapabilityRegistry() {
        ScheduleHostPluginConfiguration configuration = new ScheduleHostPluginConfiguration();
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduleConfig config = new ScheduleConfig();
        ScheduleRunState runState = new ScheduleRunState();
        ScheduleRunQueue runQueue = new ScheduleRunQueue();
        TaskExecutor direct = Runnable::run;

        ScheduleExecutor executor = configuration.scheduleExecutor(
                store,
                registry,
                mock(PixivFetchService.class),
                mock(PixivDatabase.class),
                mock(WorkMetaCaptureService.class),
                mock(ArtworkDownloader.class),
                mock(NovelMetadataRepository.class),
                config,
                runState,
                runQueue,
                new ObjectMapper(),
                mock(OveruseWarningService.class),
                mock(NotificationService.class),
                mock(AppMessages.class),
                mock(SetupService.class),
                new DownloadConfig(),
                direct,
                direct);
        ScheduleService service = configuration.scheduleService(
                store, executor, config, runState, runQueue, registry);

        assertThat(ReflectionTestUtils.getField(executor, "scheduleCapabilityRegistry"))
                .isSameAs(registry);
        assertThat(ReflectionTestUtils.getField(service, "scheduleCapabilityRegistry"))
                .isSameAs(registry);
    }
}
