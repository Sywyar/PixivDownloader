package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrarTestAccess;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter.PreparedContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("外置能力 publication 批次边界")
class PluginCapabilityPublicationBoundaryTest {

    @Test
    @DisplayName("发布失败的中央 cleanup 遇普通或致命错误时保留 exact batch 并可重试后复用 owner")
    void failedCentralCleanupRetainsExactBatchUntilRetryCompletes() {
        for (Throwable cleanupFailure : List.of(
                new IllegalStateException("ordinary-cleanup"),
                new OutOfMemoryError("fatal-cleanup"),
                new ThreadDeath())) {
            ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
            AtomicReference<Throwable> nextCleanupFailure = new AtomicReference<>(cleanupFailure);
            List<String> events = new ArrayList<>();
            CapturingAdapter first = new CapturingAdapter("a.capture", invocation, events);
            PartialPublishAdapter second = new PartialPublishAdapter(
                    "z.partial", events,
                    new AtomicReference<>(new IllegalStateException("downstream-cleanup")));
            PluginCapabilityContributionRegistrar registrar =
                    PluginCapabilityContributionRegistrarTestAccess.withCentralCleanupProbe(
                            List.of(), List.of(), List.of(first, second), invocation,
                            () -> throwPending(nextCleanupFailure));
            PluginCapabilityContributionRegistrar.PreparedOwner prepared;
            try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                child.refresh();
                prepared = prepareOwner(registrar, "demo", "demo", 1L, child);
            }

            Throwable observed = catchThrowable(() -> registrar.publish(prepared));

            if (cleanupFailure instanceof Error) {
                assertThat(observed).isSameAs(cleanupFailure);
            } else {
                assertThat(observed).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("cleanupFailureTypes");
            }
            try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                child.refresh();
                assertThatThrownBy(() -> prepareOwner(registrar, "demo", "demo", 1L, child))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("cleanup is still tracked");
            }

            assertThat(registrar.discardUnpublished(prepared)).isTrue();
            assertThat(events).containsExactly(
                    "a.capture:publish",
                    "z.partial:publish",
                    "z.partial:withdraw",
                    "a.capture:withdraw",
                    "z.partial:withdraw",
                    "a.capture:withdraw");
            try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                child.refresh();
                PluginCapabilityContributionRegistrar.PreparedOwner replacement =
                        prepareOwner(registrar, "demo", "demo", 1L, child);
                assertThat(registrar.discardUnpublished(replacement)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("部分 prepare 失败会丢弃同批已保存 raw target 且异常不携带 cause")
    void partialPreparationFailureDiscardsRawTargets() {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        CapturingAdapter first = new CapturingAdapter("a.capture", invocation);
        FailingPrepareAdapter second = new FailingPrepareAdapter("z.failure", new AssertionError("child graph"));
        PluginCapabilityContributionRegistrar registrar = registrar(invocation, first, second);
        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.refresh();

            assertThatThrownBy(() -> prepareOwner(registrar, "demo", "demo", 1L, child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause()
                    .hasMessageNotContaining("child graph");
        }

        assertThat(first.proxy.get()).isNotNull();
        assertThatThrownBy(first.proxy.get()::call)
                .isInstanceOf(ExternalCapabilityUnavailableException.class);
        ExternalCapabilityPreparation replacement = prepareOwner(invocation, "demo", "demo", 1L);
        assertThat(invocation.discardUnpublished(replacement)).isTrue();
    }

    @Test
    @DisplayName("部分下游 publish 失败会逆序撤回所有 adapter 并丢弃中央批次")
    void partialPublicationFailureRollsBackEveryAdapter() {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        List<String> events = new ArrayList<>();
        CapturingAdapter first = new CapturingAdapter("a.capture", invocation, events);
        PartialPublishAdapter second = new PartialPublishAdapter("z.partial", events);
        PluginCapabilityContributionRegistrar registrar = registrar(invocation, first, second);
        PluginCapabilityContributionRegistrar.PreparedOwner prepared;
        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.refresh();
            prepared = prepareOwner(registrar, "demo", "demo", 2L, child);
        }

        assertThatThrownBy(() -> registrar.publish(prepared))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
        assertThat(events).containsExactly(
                "a.capture:publish",
                "z.partial:publish",
                "z.partial:withdraw",
                "a.capture:withdraw");
        assertThatThrownBy(first.proxy.get()::call)
                .isInstanceOf(ExternalCapabilityUnavailableException.class);
        ExternalCapabilityPreparation replacement = prepareOwner(invocation, "demo", "demo", 2L);
        invocation.discardUnpublished(replacement);
    }

    @Test
    @DisplayName("cleanup 中先遇普通 Error 后仍执行全部清理并让后续 VM fatal 保持身份优先")
    void cleanupAllLetsLaterFatalTakePriorityWithoutSkippingCentralRetirement() {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        List<String> events = new ArrayList<>();
        OutOfMemoryError fatal = new OutOfMemoryError("fatal identity");
        CleanupFailureAdapter fatalAdapter = new CleanupFailureAdapter("a.fatal", fatal, events);
        CleanupFailureAdapter ordinaryAdapter =
                new CleanupFailureAdapter("z.ordinary", new AssertionError("ordinary"), events);
        PluginCapabilityContributionRegistrar registrar = registrar(
                invocation, fatalAdapter, ordinaryAdapter);
        ExternalCapabilityPublication publication;
        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.refresh();
            publication = registrar.publish(prepareOwner(registrar, "demo", "demo", 3L, child));
        }
        ExternalCapabilityDrain drain = registrar.withdraw(publication).orElseThrow();
        Throwable observed = null;
        try {
            registrar.retireDrained(drain);
        } catch (Throwable failure) {
            observed = failure;
        }

        assertThat(observed).isSameAs(fatal);
        assertThat(events).containsExactly(
                "a.fatal:publish",
                "z.ordinary:publish",
                "z.ordinary:withdraw",
                "a.fatal:withdraw");
        ExternalCapabilityPreparation replacement = prepareOwner(invocation, "demo", "demo", 3L);
        invocation.discardUnpublished(replacement);
    }

    @Test
    @DisplayName("prepare 期间 VM fatal 在丢弃已准备 target 后仍保持原对象身份")
    void preparationFatalIdentityIsPreservedAfterDiscard() {
        ExternalCapabilityInvocationRegistry invocation = new ExternalCapabilityInvocationRegistry();
        CapturingAdapter first = new CapturingAdapter("a.capture", invocation);
        ThreadDeath fatal = new ThreadDeath();
        FailingPrepareAdapter second = new FailingPrepareAdapter("z.fatal", fatal);
        PluginCapabilityContributionRegistrar registrar = registrar(invocation, first, second);
        Throwable observed = null;
        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.refresh();
            try {
                prepareOwner(registrar, "demo", "demo", 4L, child);
            } catch (Throwable failure) {
                observed = failure;
            }
        }

        assertThat(observed).isSameAs(fatal);
        assertThatThrownBy(first.proxy.get()::call)
                .isInstanceOf(ExternalCapabilityUnavailableException.class);
    }

    private static PluginCapabilityContributionRegistrar.PreparedOwner prepareOwner(
            PluginCapabilityContributionRegistrar registrar,
            String pluginId,
            String packageId,
            long generation,
            ConfigurableApplicationContext context) {
        PluginCapabilityContributionRegistrar.PreparedOwner prepared = registrar.allocateOwner(
                pluginId, packageId, generation);
        registrar.prepareInto(prepared, context);
        return prepared;
    }

    private static ExternalCapabilityPreparation prepareOwner(
            ExternalCapabilityInvocationRegistry invocation,
            String pluginId,
            String packageId,
            long generation) {
        ExternalCapabilityPreparation prepared = invocation.allocatePreparation(
                pluginId, packageId, generation);
        invocation.installPreparation(prepared);
        return prepared;
    }

    private static PluginCapabilityContributionRegistrar registrar(
            ExternalCapabilityInvocationRegistry invocation,
            ExternalRuntimeCapabilityAdapter... adapters) {
        return new PluginCapabilityContributionRegistrar(
                List.of(), List.of(), List.of(adapters), invocation);
    }

    private static void throwPending(AtomicReference<Throwable> pending) {
        Throwable failure = pending.getAndSet(null);
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    public interface Echo {
        String call();
    }

    private record Prepared(ExternalCapabilityOwner owner) implements PreparedContribution {
    }

    private static class CapturingAdapter implements ExternalRuntimeCapabilityAdapter {
        private final String name;
        private final ExternalCapabilityInvocationRegistry invocation;
        private final List<String> events;
        private final AtomicReference<Echo> proxy = new AtomicReference<>();

        private CapturingAdapter(String name, ExternalCapabilityInvocationRegistry invocation) {
            this(name, invocation, new ArrayList<>());
        }

        private CapturingAdapter(
                String name,
                ExternalCapabilityInvocationRegistry invocation,
                List<String> events) {
            this.name = name;
            this.invocation = invocation;
            this.events = events;
        }

        @Override
        public String capabilityName() {
            return name;
        }

        @Override
        public PreparedContribution prepare(
                ExternalCapabilityPreparation preparation,
                ConfigurableApplicationContext context) {
            proxy.set(invocation.prepareProxy(preparation, Echo.class, () -> "ok"));
            return new Prepared(preparation.owner());
        }

        @Override
        public void publish(PreparedContribution contribution) {
            events.add(name + ":publish");
        }

        @Override
        public void withdraw(ExternalCapabilityOwner owner) {
            events.add(name + ":withdraw");
        }
    }

    private record FailingPrepareAdapter(String capabilityName, Error failure)
            implements ExternalRuntimeCapabilityAdapter {
        @Override
        public PreparedContribution prepare(
                ExternalCapabilityPreparation preparation,
                ConfigurableApplicationContext context) {
            throw failure;
        }

        @Override
        public void publish(PreparedContribution contribution) {
        }

        @Override
        public void withdraw(ExternalCapabilityOwner owner) {
        }
    }

    private static final class PartialPublishAdapter implements ExternalRuntimeCapabilityAdapter {
        private final String name;
        private final List<String> events;
        private final AtomicReference<Throwable> withdrawFailure;

        private PartialPublishAdapter(String name, List<String> events) {
            this(name, events, new AtomicReference<>());
        }

        private PartialPublishAdapter(
                String name,
                List<String> events,
                AtomicReference<Throwable> withdrawFailure) {
            this.name = name;
            this.events = events;
            this.withdrawFailure = withdrawFailure;
        }

        @Override
        public String capabilityName() {
            return name;
        }

        @Override
        public PreparedContribution prepare(
                ExternalCapabilityPreparation preparation,
                ConfigurableApplicationContext context) {
            return new Prepared(preparation.owner());
        }

        @Override
        public void publish(PreparedContribution contribution) {
            events.add(name + ":publish");
            throw new IllegalStateException("partial publish");
        }

        @Override
        public void withdraw(ExternalCapabilityOwner owner) {
            events.add(name + ":withdraw");
            throwPending(withdrawFailure);
        }
    }

    private static final class CleanupFailureAdapter implements ExternalRuntimeCapabilityAdapter {
        private final String name;
        private final Error failure;
        private final List<String> events;
        private final AtomicInteger withdrawals = new AtomicInteger();

        private CleanupFailureAdapter(String name, Error failure, List<String> events) {
            this.name = name;
            this.failure = failure;
            this.events = events;
        }

        @Override
        public String capabilityName() {
            return name;
        }

        @Override
        public PreparedContribution prepare(
                ExternalCapabilityPreparation preparation,
                ConfigurableApplicationContext context) {
            return new Prepared(preparation.owner());
        }

        @Override
        public void publish(PreparedContribution contribution) {
            events.add(name + ":publish");
        }

        @Override
        public void withdraw(ExternalCapabilityOwner owner) {
            withdrawals.incrementAndGet();
            events.add(name + ":withdraw");
            throw failure;
        }
    }
}
