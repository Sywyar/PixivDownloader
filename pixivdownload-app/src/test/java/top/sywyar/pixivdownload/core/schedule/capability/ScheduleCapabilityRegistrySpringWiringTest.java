package top.sywyar.pixivdownload.core.schedule.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划能力运行时的宿主 Spring 接线")
class ScheduleCapabilityRegistrySpringWiringTest {

    @Test
    @DisplayName("生产构造器使用宿主生命周期准入视图而非无参放行策略")
    void productionConstructorUsesHostLifecycleAdmission() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PluginLifecycleState.class);
            context.registerBean(ScheduleCapabilityRegistry.class);
            context.refresh();

            PluginLifecycleState lifecycle = context.getBean(PluginLifecycleState.class);
            ScheduleCapabilityRegistry registry = context.getBean(ScheduleCapabilityRegistry.class);
            ScheduleCapabilityOwner owner =
                    new ScheduleCapabilityOwner("wiring-feature", "wiring-package", 1L);
            lifecycle.initialize(owner.featurePluginId(), PluginRuntimePhase.LOADED);
            ScheduleCapabilityPublication publication = ScheduleCapabilityRegistryTestAccess.publish(
                    registry,
                    ScheduleOwnerBundle.prepare(
                            owner,
                            List.of(),
                            List.of(),
                            List.of(workExecutor("work:wiring")),
                            List.of(),
                            List.of()));

            ScheduleCapabilityHandle<ScheduledWorkExecutor> handle =
                    registry.resolveWorkExecutor("work:wiring").orElseThrow();
            assertThat(registry.prepareAcquire(handle)).isEmpty();

            lifecycle.transition(owner.featurePluginId(), PluginRuntimePhase.STARTED);
            try (ScheduleSingleCapabilityLease<ScheduledWorkExecutor> lease =
                         registry.prepareAcquire(handle).orElseThrow()) {
                assertThat(registry.activate(lease)).isTrue();
            }

            assertThat(registry.withdraw(publication).orElseThrow().isDrained()).isTrue();
        }
    }

    private static ScheduledWorkExecutor workExecutor(String workType) {
        return new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return workType;
            }

            @Override
            public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }
        };
    }
}
