package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.notification.NotificationService;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ScheduleHostPluginConfiguration 生产执行装配")
class ScheduleHostProductionWiringTest {

    @Test
    @DisplayName("Spring Bean 工厂只标注接收非空通用引擎的 ScheduleExecutor 重载")
    void springFactoryWiresGenericExecutionEngine() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);

        ScheduleExecutor executor = productionExecutor(registry, engine);

        assertThat(ReflectionTestUtils.getField(executor, "scheduleExecutionEngine"))
                .isSameAs(engine);
        List<Method> factories = Arrays.stream(ScheduleHostPluginConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("scheduleExecutor"))
                .toList();
        assertThat(factories).hasSize(2);
        assertThat(factories)
                .filteredOn(method -> Arrays.asList(method.getParameterTypes())
                        .contains(ScheduleExecutionEngine.class))
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getAnnotation(Bean.class)).isNotNull();
                    assertThat(Arrays.stream(method.getParameters())
                            .filter(parameter -> parameter.getType() == TaskExecutor.class)
                            .map(parameter -> parameter.getAnnotation(Qualifier.class))
                            .map(Qualifier::value))
                            .containsExactly("downloadTaskExecutor", "scheduleWorkTaskExecutor");
                });
        assertThat(factories)
                .filteredOn(method -> !Arrays.asList(method.getParameterTypes())
                        .contains(ScheduleExecutionEngine.class))
                .singleElement()
                .satisfies(method -> assertThat(method.getAnnotation(Bean.class)).isNull());
    }

    @Test
    @DisplayName("生产执行器存在通用引擎时拒绝仅发布 legacy 能力的来源")
    void productionExecutorRejectsLegacyOnlySource() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduledSourceProvider legacySource = mock(ScheduledSourceProvider.class);
        when(legacySource.type()).thenReturn("legacy-only");
        when(legacySource.legacyTypeNames()).thenReturn(java.util.Set.of("LEGACY_ONLY"));
        ScheduledWorkRunner legacyRunner = mock(ScheduledWorkRunner.class);
        when(legacyRunner.kind()).thenReturn("illust");
        ScheduleCapabilityTestFixture.publish(registry, ScheduleOwnerBundle.prepare(
                new ScheduleCapabilityOwner("legacy-owner", "legacy-package", 1L),
                List.of(legacySource),
                List.of(legacyRunner),
                List.of(), List.of(), List.of(), List.of(), List.of()));
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        ScheduleExecutor executor = productionExecutor(registry, engine);
        ScheduledTask task = mock(ScheduledTask.class);
        when(task.id()).thenReturn(99L);
        when(task.sourceType()).thenReturn("legacy-only");
        when(task.sourceOwnerPluginId()).thenReturn("legacy-owner");

        assertThat(executor.canResolveExecution(task)).isFalse();
        verifyNoInteractions(engine);
    }

    private static ScheduleExecutor productionExecutor(
            ScheduleCapabilityRegistry registry,
            ScheduleExecutionEngine engine) {
        ScheduleHostPluginConfiguration configuration = new ScheduleHostPluginConfiguration();
        ObjectMapper objectMapper = new ObjectMapper();
        PixivSchedulePersistenceCodec persistenceCodec =
                configuration.pixivSchedulePersistenceCodec(objectMapper);
        TaskExecutor direct = Runnable::run;
        return configuration.scheduleExecutor(
                mock(ScheduledTaskStore.class),
                registry,
                mock(PixivFetchService.class),
                mock(PixivDatabase.class),
                mock(WorkMetaCaptureService.class),
                mock(ArtworkDownloader.class),
                mock(WorkQueryService.class),
                new ScheduleConfig(),
                new ScheduleRunState(),
                new ScheduleRunQueue(),
                objectMapper,
                persistenceCodec,
                mock(OveruseWarningService.class),
                mock(NotificationService.class),
                mock(AppMessages.class),
                mock(WebI18nBundleRegistry.class),
                mock(UserDisplayNameProvider.class),
                mock(DownloadSettings.class),
                direct,
                direct,
                engine);
    }
}
