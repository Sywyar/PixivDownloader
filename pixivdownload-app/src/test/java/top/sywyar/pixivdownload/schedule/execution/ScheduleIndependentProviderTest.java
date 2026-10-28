package top.sywyar.pixivdownload.schedule.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleSourceUnavailableException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("独立来源经过真实计划注册表执行")
class ScheduleIndependentProviderTest extends ScheduleExecutionEngineTestSupport {
    @Test
    @DisplayName("没有工作台 publication 也能执行，撤回来源后在凭证和副作用前拒绝")
    void executesUnknownProviderWithoutWorkbenchAndRejectsRetiredSource() throws Exception {
        var store = storeWithCredential();
        var registry = new ScheduleCapabilityRegistry();
        var completed = new AtomicInteger();
        var source = sourceWithPlan(plan(Set.of(WORK), List.of()), context -> {
            context.workSink().submit(work("opaque/independent-id"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        var bundle = bindingBundle(new ScheduleCapabilityOwner("fixture", "fixture-package", 1L),
                source, workExecutor(context -> {
                    completed.incrementAndGet();
                    return ScheduledWorkResult.completed();
                }), credentialPolicy(new AtomicReference<>()), guard(context -> ScheduledGuardDecision.proceed()));
        var publication = ScheduleCapabilityRegistryTestAccess.publish(registry, ScheduleOwnerBundle.prepare(
                bundle.owner(), bundle.sourceDescriptors(), bundle.sourceExecutors(), bundle.workExecutors(),
                bundle.credentialPolicies(), bundle.guards()));
        var engine = engine(store, registry, new ScheduleRunState(), new SyncTaskExecutor());
        assertThat(engine.execute(task()).completedWorkCount()).isEqualTo(1);
        assertThat(completed).hasValue(1);
        assertThat(registry.snapshot().owners()).extracting(item -> item.owner().featurePluginId())
                .containsExactly("fixture");
        var drain = ScheduleCapabilityRegistryTestAccess.withdraw(registry, publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
        clearInvocations(store);
        assertThatThrownBy(() -> engine.execute(task())).isInstanceOf(ScheduleSourceUnavailableException.class);
        verifyNoInteractions(store);
        assertThat(completed).hasValue(1);
    }
}
