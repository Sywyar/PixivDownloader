package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.*;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.DownloadLifecycleCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@DisplayName("下载事件和前置规则生命周期")
class DownloadLifecycleRegistryTest {
    @Test @DisplayName("撤回中的选项链拒绝旧处理器，不回落到新发布或跳过规则")
    void withdrawnHookChainFailsClosed() throws Exception {
        var registry = new DownloadLifecycleRegistry();
        var invocations = new ExternalCapabilityInvocationRegistry();
        var adapter = new DownloadLifecycleCapabilityAdapter(registry, invocations);
        var registrar = new PluginCapabilityContributionRegistrar(List.of(), List.of(), List.of(adapter), invocations);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var laterCalls = new AtomicInteger();
        var worker = Executors.newSingleThreadExecutor();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("first", DownloadOptionsHook.class, () -> (attempt, options) -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return options;
            });
            context.registerBean("second", DownloadOptionsHook.class, () -> (attempt, options) -> {
                laterCalls.incrementAndGet();
                return options;
            });
            context.refresh();
            var prepared = registrar.allocateOwner("hook-test", "hook-package", 1L);
            registrar.prepareInto(prepared, context);
            var publication = registrar.publish(prepared);
            var attempt = new DownloadAttempt(UUID.randomUUID(), "example", "42");
            Future<?> work = worker.submit(() -> registry.options(attempt, java.util.Map.of("format", "txt")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registrar.withdraw(publication).orElseThrow();
            assertThat(drain.isDrained()).isFalse();
            release.countDown();
            assertThatThrownBy(() -> work.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(DownloadAdmissionRejectedException.class);
            assertThat(laterCalls).hasValue(0);
            assertThat(drain.isDrained()).isTrue();
            registrar.retireDrained(drain);
            registrar.acknowledgeRetired(drain);
            context.close();
            assertThat(registrar.releaseRetirementProof(drain)).isTrue();
            assertThat(registry.options(attempt, java.util.Map.of("format", "txt"))).containsEntry("format", "txt");
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test @DisplayName("观察失败尽力隔离，规则失败拒绝，撤回后不再调用旧插件")
    void failuresAndWithdrawal() {
        var registry = new DownloadLifecycleRegistry();
        var invocations = new ExternalCapabilityInvocationRegistry();
        var adapter = new DownloadLifecycleCapabilityAdapter(registry, invocations);
        var registrar = new PluginCapabilityContributionRegistrar(List.of(), List.of(), List.of(adapter), invocations);
        var calls = new AtomicInteger();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DownloadObserver.class, () -> event -> { calls.incrementAndGet(); throw new IllegalStateException(); });
            context.registerBean(DownloadAdmissionPolicy.class, () -> attempt -> { throw new IllegalStateException(); });
            context.refresh();
            var prepared = registrar.allocateOwner("observer-test", "observer-package", 1L);
            registrar.prepareInto(prepared, context);
            var publication = registrar.publish(prepared);
            var attempt = new DownloadAttempt(UUID.randomUUID(), "artwork", "42");
            var event = new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED);
            registry.publish(event);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(registry.observerFailureCount()).isEqualTo(1);
            assertThatThrownBy(() -> registry.checkAdmission(attempt)).isInstanceOf(DownloadAdmissionRejectedException.class);
            var drain = registrar.withdraw(publication).orElseThrow();
            assertThat(drain.isDrained()).isTrue();
            registrar.retireDrained(drain);
            registrar.acknowledgeRetired(drain);
            context.close();
            assertThat(registrar.releaseRetirementProof(drain)).isTrue();
            registry.publish(event);
            registry.checkAdmission(attempt);
            assertThat(calls.get()).isEqualTo(1);
            var replacementCalls = new AtomicInteger();
            var fatal = new java.util.concurrent.atomic.AtomicBoolean();
            try (var replacement = new AnnotationConfigApplicationContext()) {
                replacement.registerBean(DownloadObserver.class, () -> next -> {
                    replacementCalls.incrementAndGet();
                    if (fatal.get()) throw new VirtualMachineError("simulated") {};
                });
                replacement.refresh();
                var next = registrar.allocateOwner("observer-test", "observer-package", 2L);
                registrar.prepareInto(next, replacement);
                var nextPublication = registrar.publish(next);
                adapter.withdraw(prepared.owner());
                registry.publish(event);
                assertThat(replacementCalls.get()).isEqualTo(1);
                assertThat(calls.get()).isEqualTo(1);
                fatal.set(true);
                assertThatThrownBy(() -> registry.publish(event)).isInstanceOf(VirtualMachineError.class);
                assertThat(registry.observerFailureCount()).isEqualTo(1);
                var nextDrain = registrar.withdraw(nextPublication).orElseThrow();
                assertThat(nextDrain.isDrained()).isTrue();
                registrar.retireDrained(nextDrain);
                registrar.acknowledgeRetired(nextDrain);
                replacement.close();
                assertThat(registrar.releaseRetirementProof(nextDrain)).isTrue();
            }
        }
    }

    @Test @DisplayName("在途观察回调参与 drain，关闭准入后不增加新调用")
    void inFlightCallbackDrains() throws Exception {
        var registry = new DownloadLifecycleRegistry();
        var invocations = new ExternalCapabilityInvocationRegistry();
        var adapter = new DownloadLifecycleCapabilityAdapter(registry, invocations);
        var registrar = new PluginCapabilityContributionRegistrar(List.of(), List.of(), List.of(adapter), invocations);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DownloadObserver.class, () -> event -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            context.refresh();
            var prepared = registrar.allocateOwner("observer-test", "observer-package", 1L);
            registrar.prepareInto(prepared, context);
            var publication = registrar.publish(prepared);
            var event = new DownloadEvent(new DownloadAttempt(UUID.randomUUID(), "artwork", "42"), DownloadEvent.Phase.STARTED);
            Future<?> work = worker.submit(() -> registry.publish(event));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registrar.withdraw(publication).orElseThrow();
            assertThat(drain.isDrained()).isFalse();
            registry.publish(event);
            release.countDown();
            work.get(5, TimeUnit.SECONDS);
            assertThat(drain.isDrained()).isTrue();
            registrar.retireDrained(drain);
            registrar.acknowledgeRetired(drain);
            context.close();
            assertThat(registrar.releaseRetirementProof(drain)).isTrue();
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
