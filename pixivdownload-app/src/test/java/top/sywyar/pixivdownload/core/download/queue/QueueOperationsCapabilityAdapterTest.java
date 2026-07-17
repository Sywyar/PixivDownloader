package top.sywyar.pixivdownload.core.download.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.QueueOperationsCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("队列操作运行期能力适配器")
class QueueOperationsCapabilityAdapterTest {

    @Test
    @DisplayName("从外置插件子上下文注册队列操作并按 owner 精准注销")
    void registersChildContextOperationsAndUnregistersOnlyTheirOwner() {
        QueueOperations parent = operations("parent");
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of(parent));
        QueueOperationsCapabilityAdapter adapter = new QueueOperationsCapabilityAdapter(registry);
        PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                List.<PluginCapabilityContributionAdapter<?>>of(adapter));

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("externalQueueOperations", QueueOperations.class, () -> operations("external"));
            child.refresh();
            registrar.register("external-plugin", child);
        }

        assertThat(registry.resolve("external")).isPresent();
        assertThat(registry.resolveHost("parent")).isPresent();

        registrar.unregister("external-plugin");

        assertThat(registry.resolve("external")).isEmpty();
        assertThat(registry.resolveHost("parent")).isPresent();
    }

    @Test
    @DisplayName("同一 owner 重新注册时原子替换旧队列类型")
    void replacesOwnedOperationsAtomically() {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        registry.register("external-plugin", List.of(operations("old")));

        registry.register("external-plugin", List.of(operations("new")));

        assertThat(registry.resolve("old")).isEmpty();
        assertThat(registry.resolve("new")).isPresent();
    }

    @Test
    @DisplayName("runtime adapter 只捕获一次 queueType，HTTP proxy 撤回后 fail closed 而 lifecycle 仍持有 raw")
    void capturesQueueTypeOnceAndSeparatesInvocationProxyFromLifecycleRaw() {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        ExternalCapabilityInvocationRegistry invocationRegistry = new ExternalCapabilityInvocationRegistry();
        QueueOperationsCapabilityAdapter adapter =
                new QueueOperationsCapabilityAdapter(registry, invocationRegistry);
        PluginCapabilityContributionRegistrar registrar = runtimeRegistrar(adapter, invocationRegistry);
        CountingOperations raw = new CountingOperations("external", true);

        try (AnnotationConfigApplicationContext child = childWith(raw)) {
            PluginCapabilityContributionRegistrar.PreparedOwner prepared =
                    registrar.allocateOwner("plugin", "package", 3L);
            registrar.prepareInto(prepared, child);
            ExternalCapabilityPublication publication = registrar.publish(prepared);
            QueueOperationRegistry.OwnedQueueCommands resolved = registry
                    .resolveOwned("external", "plugin", "package", 3L).orElseThrow();

            assertThat(raw.queueTypeCalls).hasValue(1);
            registry.cancel("external", resolved.commands(), "work", "owner", false);
            assertThat(raw.cancelCalls).hasValue(1);

            ExternalCapabilityDrain drain = registrar.withdraw(publication).orElseThrow();
            assertThat(registry.operationsForOwner("plugin")).singleElement().satisfies(owned ->
                    assertThat(owned.operations()).isSameAs(raw));
            assertThatThrownBy(() -> registry.cancel(
                    "external", resolved.commands(), "stale", "owner", false))
                    .isInstanceOf(QueueOperationUnavailableException.class);
            assertThat(raw.cancelCalls).hasValue(1);

            assertThat(drain.isDrained()).isTrue();
            registrar.retireDrained(drain);
            registrar.acknowledgeRetired(drain);
            assertThat(registrar.releaseRetirementProof(drain)).isTrue();
            assertThat(registry.resolve("external")).isEmpty();
        }
    }

    @Test
    @DisplayName("reload 后旧命令不能触达新代 raw target，新 generation 命令仍可调用")
    void staleProxyCannotReachReplacementGeneration() {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        ExternalCapabilityInvocationRegistry invocationRegistry = new ExternalCapabilityInvocationRegistry();
        QueueOperationsCapabilityAdapter adapter =
                new QueueOperationsCapabilityAdapter(registry, invocationRegistry);
        PluginCapabilityContributionRegistrar registrar = runtimeRegistrar(adapter, invocationRegistry);
        CountingOperations oldRaw = new CountingOperations("external", false);
        QueueOperationCommands staleCommands;

        try (AnnotationConfigApplicationContext child = childWith(oldRaw)) {
            PluginCapabilityContributionRegistrar.PreparedOwner oldPrepared =
                    registrar.allocateOwner("plugin", "package", 1L);
            registrar.prepareInto(oldPrepared, child);
            ExternalCapabilityPublication oldPublication = registrar.publish(oldPrepared);
            staleCommands = registry.resolveOwned(
                    "external", "plugin", "package", 1L).orElseThrow().commands();
            ExternalCapabilityDrain oldDrain = registrar.withdraw(oldPublication).orElseThrow();
            registrar.retireDrained(oldDrain);
            registrar.acknowledgeRetired(oldDrain);
            assertThat(registrar.releaseRetirementProof(oldDrain)).isTrue();
        }

        CountingOperations currentRaw = new CountingOperations("external", false);
        try (AnnotationConfigApplicationContext child = childWith(currentRaw)) {
            PluginCapabilityContributionRegistrar.PreparedOwner currentPrepared =
                    registrar.allocateOwner("plugin", "package", 2L);
            registrar.prepareInto(currentPrepared, child);
            ExternalCapabilityPublication currentPublication = registrar.publish(currentPrepared);
            QueueOperationCommands currentCommands = registry.resolveOwned(
                    "external", "plugin", "package", 2L).orElseThrow().commands();

            assertThatThrownBy(() -> registry.cancel(
                    "external", staleCommands, "old-request", "owner", false))
                    .isInstanceOf(QueueOperationUnavailableException.class);
            assertThat(currentRaw.cancelCalls).hasValue(0);
            registry.cancel("external", currentCommands, "current-request", "owner", false);
            assertThat(currentRaw.cancelCalls).hasValue(1);

            ExternalCapabilityDrain currentDrain = registrar.withdraw(currentPublication).orElseThrow();
            registrar.retireDrained(currentDrain);
            registrar.acknowledgeRetired(currentDrain);
            assertThat(registrar.releaseRetirementProof(currentDrain)).isTrue();
        }
    }

    private static PluginCapabilityContributionRegistrar runtimeRegistrar(
            QueueOperationsCapabilityAdapter adapter,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        return new PluginCapabilityContributionRegistrar(
                List.<PluginCapabilityContributionAdapter<?>>of(adapter),
                List.of(),
                List.<ExternalRuntimeCapabilityAdapter>of(adapter),
                invocationRegistry);
    }

    private static AnnotationConfigApplicationContext childWith(QueueOperations operations) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.registerBean("externalQueueOperations", QueueOperations.class, () -> operations);
        child.refresh();
        return child;
    }

    private static final class CountingOperations implements QueueOperations {
        private final String type;
        private final boolean rejectSecondQueueTypeRead;
        private final AtomicInteger queueTypeCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final QueueTaskTracker tracker;

        private CountingOperations(String type, boolean rejectSecondQueueTypeRead) {
            this.type = type;
            this.rejectSecondQueueTypeRead = rejectSecondQueueTypeRead;
            this.tracker = new QueueTaskTracker(type);
        }

        @Override
        public String queueType() {
            int calls = queueTypeCalls.incrementAndGet();
            if (rejectSecondQueueTypeRead && calls > 1) {
                throw new AssertionError("queueType read more than once");
            }
            return type;
        }

        @Override public QueueGenerationDrain prepareQuiesce(String registeredQueueType) {
            return tracker.prepareQuiesce();
        }
        @Override public void cancelQuiescedTasks() { tracker.cancelQuiescedTasks(); }
        @Override public void cancel(String workKey, String ownerUuid, boolean admin) {
            cancelCalls.incrementAndGet();
        }
        @Override public int clearAll() { return 0; }
        @Override public int clearForOwner(String ownerUuid) { return 0; }
    }

    private static QueueOperations operations(String type) {
        return new QueueOperations() {
            private final QueueTaskTracker tracker = new QueueTaskTracker(type);

            @Override
            public String queueType() {
                return type;
            }

            @Override
            public QueueGenerationDrain prepareQuiesce(String registeredQueueType) {
                return tracker.prepareQuiesce();
            }

            @Override
            public void cancelQuiescedTasks() {
                tracker.cancelQuiescedTasks();
            }

            @Override
            public int clearAll() {
                return 0;
            }

            @Override
            public int clearForOwner(String ownerUuid) {
                return 0;
            }
        };
    }
}
