package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;
import top.sywyar.pixivdownload.core.notification.NotificationService;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScheduleHostPluginConfiguration 生产执行装配")
class ScheduleHostProductionWiringTest {

    @Test
    @DisplayName("Spring Bean 工厂只保留接收通用执行引擎的九依赖装配")
    void springFactoryWiresGenericExecutionEngine() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);

        ScheduleExecutor executor = productionExecutor(registry, engine);

        assertThat(ReflectionTestUtils.getField(executor, "scheduleExecutionEngine"))
                .isSameAs(engine);
        List<Method> factories = Arrays.stream(ScheduleHostPluginConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("scheduleExecutor"))
                .toList();
        assertThat(factories)
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getAnnotation(Bean.class)).isNotNull();
                    assertThat(method.getParameterTypes()).containsExactly(
                            ScheduledTaskStore.class,
                            ScheduleCapabilityRegistry.class,
                            ScheduleRunState.class,
                            ObjectMapper.class,
                            NotificationService.class,
                            AppMessages.class,
                            WebI18nBundleRegistry.class,
                            UserDisplayNameProvider.class,
                            ScheduleExecutionEngine.class);
                });
    }

    @Test
    @DisplayName("来源可解析性只委派给通用执行引擎，不再读取旧来源桥")
    void productionExecutorDelegatesResolutionToGenericEngine() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        ScheduleExecutor executor = productionExecutor(registry, engine);
        ScheduledTask task = mock(ScheduledTask.class);
        when(engine.canResolve(task)).thenReturn(true);

        assertThat(executor.canResolveExecution(task)).isTrue();
        verify(engine).canResolve(task);
    }

    private static ScheduleExecutor productionExecutor(
            ScheduleCapabilityRegistry registry,
            ScheduleExecutionEngine engine) {
        ScheduleHostPluginConfiguration configuration = new ScheduleHostPluginConfiguration();
        ObjectMapper objectMapper = new ObjectMapper();
        return configuration.scheduleExecutor(
                mock(ScheduledTaskStore.class),
                registry,
                new ScheduleRunState(),
                objectMapper,
                mock(NotificationService.class),
                mock(AppMessages.class),
                mock(WebI18nBundleRegistry.class),
                mock(UserDisplayNameProvider.class),
                engine);
    }
}
