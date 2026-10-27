package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.service.PluginServices;
import top.sywyar.pixivdownload.plugin.api.service.ServiceAccessException;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("服务消费方与提供方共享精确租约")
class PluginServicesTest {
    public interface Echo { String echo(String input); }
    public sealed interface SealedEcho permits OpenEcho { String echo(String input); }
    public non-sealed interface OpenEcho extends SealedEcho { }
    private static final ServiceKey<Echo> KEY = new ServiceKey<>("test-contract", Echo.class);

    @Test
    @DisplayName("sealed 接口在准备时拒绝，非 sealed 子接口可发布和调用")
    void rejectsSealedBeforePublication() {
        var registry = new ExternalCapabilityInvocationRegistry();
        var provider = prepare(registry, "provider");
        OpenEcho target = input -> input;
        var sealed = new ServiceKey<>("test-contract", SealedEcho.class);
        assertThatThrownBy(() -> registry.prepareService(provider, sealed, target))
                .isInstanceOf(IllegalArgumentException.class);
        var open = new ServiceKey<>("test-contract", OpenEcho.class);
        var consumer = prepare(registry, "consumer");
        var services = registry.servicesFor(consumer, List.of(open));
        registry.prepareService(provider, open, target);
        registry.publish(provider);
        registry.publish(consumer);
        assertThat(services.require(open).echo("ready")).isEqualTo("ready");
    }

    @Test
    @DisplayName("异常诊断绑定提供方发布且有界，不保留消息、参数、cause 或插件对象")
    void boundedFailureDiagnostics() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ExternalCapabilityInvocationRegistry.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var registry = new ExternalCapabilityInvocationRegistry();
            var provider = prepare(registry, "provider");
            var consumer = prepare(registry, "consumer");
            var services = registry.servicesFor(consumer, List.of(KEY));
            registry.prepareService(provider, KEY, value -> {
                if (value.equals("fatal")) throw new InternalError("fatal secret");
                throw new IllegalArgumentException("private message", new RuntimeException("private cause"));
            });
            var published = registry.publish(provider);
            var consuming = registry.publish(consumer);
            var proxy = services.require(KEY);
            assertThatThrownBy(() -> proxy.echo("fatal")).isInstanceOf(InternalError.class);
            assertThat(appender.list).isEmpty();
            for (int i = 0; i < 70; i++) {
                assertThatThrownBy(() -> proxy.echo("private argument"))
                        .isInstanceOfSatisfying(ServiceAccessException.class, failure -> {
                            assertThat(failure.reason()).isEqualTo(ServiceAccessException.Reason.INVOCATION_FAILED);
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.getSuppressed()).isEmpty();
                            assertThat(failure.getStackTrace()).isEmpty();
                        });
            }
            assertThat(appender.list).hasSize(1);
            var event = appender.list.get(0);
            assertThat(event.getFormattedMessage()).contains("plugin=provider", "package=provider", "generation=1",
                    "publication=" + published.owner().publicationId(), Echo.class.getName() + "#echo",
                    "failureType=java.lang.IllegalArgumentException").doesNotContain("private", "fatal secret");
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getArgumentArray()).allMatch(value -> value instanceof String || value instanceof Long);
            assertThat(registry.withdraw(published).orElseThrow().isDrained()).isTrue();
            assertThat(registry.withdraw(consuming).orElseThrow().isDrained()).isTrue();
            assertThat(registry.serviceInvocationOnCurrentThread()).isFalse();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static ExternalCapabilityPreparation prepare(ExternalCapabilityInvocationRegistry registry, String id) {
        ExternalCapabilityPreparation preparation = registry.allocatePreparation(id, id, 1);
        registry.installPreparation(preparation);
        return preparation;
    }

    @Test
    @DisplayName("准备期拒绝调用、缺席为空、多提供者冲突且撤回不转接")
    void exactResolution() {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation q = prepare(registry, "consumer");
        PluginServices services = registry.servicesFor(q, List.of(KEY));
        assertReason(() -> services.find(KEY), ServiceAccessException.Reason.NOT_ACTIVE);
        ExternalCapabilityPublication consumer = registry.publish(q);
        assertThat(services.find(KEY)).isEmpty();
        ExternalCapabilityPreparation p = prepare(registry, "provider");
        registry.prepareService(p, KEY, input -> "p:" + input);
        ExternalCapabilityPublication provider = registry.publish(p);
        Echo old = services.require(KEY);
        assertThat(old.echo("值")).isEqualTo("p:值");
        ExternalCapabilityPreparation p2 = prepare(registry, "provider-two");
        registry.prepareService(p2, KEY, input -> "replacement");
        registry.publish(p2);
        assertReason(() -> services.find(KEY), ServiceAccessException.Reason.AMBIGUOUS);
        registry.withdraw(provider);
        assertThat(services.require(KEY).echo("x")).isEqualTo("replacement");
        assertReason(() -> old.echo("x"), ServiceAccessException.Reason.UNAVAILABLE);
        Echo current = services.require(KEY);
        registry.withdraw(consumer);
        assertReason(() -> current.echo("x"), ServiceAccessException.Reason.NOT_ACTIVE);
    }

    @Test
    @DisplayName("阻塞调用同时占用双方租约，撤回与中断不等于退出")
    void drainsBothOwners() throws Exception {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation q = prepare(registry, "consumer");
        PluginServices services = registry.servicesFor(q, List.of(KEY));
        ExternalCapabilityPublication consumer = registry.publish(q);
        ExternalCapabilityPreparation p = prepare(registry, "provider");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        registry.prepareService(p, KEY, value -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { done = release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { }
            }
            return value;
        });
        ExternalCapabilityPublication provider = registry.publish(p);
        Echo proxy = services.require(KEY);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread call = new Thread(() -> { try { proxy.echo("x"); } catch (Throwable error) { failure.set(error); } });
        call.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            ExternalCapabilityDrain qDrain = registry.withdraw(consumer).orElseThrow();
            ExternalCapabilityDrain pDrain = registry.withdraw(provider).orElseThrow();
            call.interrupt();
            assertThat(qDrain.isDrained()).isFalse();
            assertThat(pDrain.isDrained()).isFalse();
            assertThatThrownBy(() -> registry.retireDrained(qDrain)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> proxy.echo("new")).isInstanceOf(ServiceAccessException.class);
            release.countDown();
            call.join(5000);
            assertThat(call.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(qDrain.isDrained()).isTrue();
            assertThat(pDrain.isDrained()).isTrue();
            registry.retireDrained(qDrain);
            registry.retireDrained(pDrain);
        } finally {
            release.countDown();
            call.join(5000);
        }
    }

    @Test
    @DisplayName("嵌套往返不死锁，普通异常不泄露对象图，致命错误保留")
    void nestedCallsAndFailures() {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation a = prepare(registry, "a");
        ExternalCapabilityPreparation b = prepare(registry, "b");
        ServiceKey<Echo> reverse = new ServiceKey<>("reverse-contract", Echo.class);
        PluginServices fromA = registry.servicesFor(a, List.of(KEY));
        PluginServices fromB = registry.servicesFor(b, List.of(reverse));
        registry.prepareService(a, reverse, input -> "a:" + input);
        registry.prepareService(b, KEY, input -> fromB.require(reverse).echo(input));
        registry.publish(a);
        registry.publish(b);
        assertThat(fromA.require(KEY).echo("b")).isEqualTo("a:b");
        assertReason(() -> fromA.find(reverse), ServiceAccessException.Reason.UNDECLARED);
        ExternalCapabilityPreparation bad = prepare(registry, "bad");
        ServiceKey<Echo> failureKey = new ServiceKey<>("failure-contract", Echo.class);
        PluginServices fromBad = registry.servicesFor(bad, List.of(failureKey));
        registry.prepareService(bad, failureKey, input -> {
            if (input.equals("fatal")) throw new InternalError("test fatal");
            throw new IllegalStateException("private secret", new RuntimeException("child graph"));
        });
        registry.publish(bad);
        assertThatThrownBy(() -> fromBad.require(failureKey).echo("ordinary"))
                .isInstanceOf(ServiceAccessException.class).hasNoCause().hasMessage("INVOCATION_FAILED");
        assertThatThrownBy(() -> fromBad.require(failureKey).echo("fatal")).isInstanceOf(InternalError.class);
        assertThat(registry.serviceInvocationOnCurrentThread()).isFalse();
        for (int i = 0; i < 65; i++) {
            assertReason(() -> fromBad.require(failureKey).echo("ordinary"), ServiceAccessException.Reason.INVOCATION_FAILED);
        }
    }

    @Test
    @DisplayName("发布到 STARTED 及 QUIESCED 到撤回之间均拒绝新服务调用")
    void requiresHostAdmissionForBothOwners() {
        var state = new top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState();
        var started = top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase.STARTED;
        var quiesced = top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase.QUIESCED;
        var registry = new ExternalCapabilityInvocationRegistry(state);
        var p = prepare(registry, "provider");
        var q = prepare(registry, "consumer");
        var services = registry.servicesFor(q, List.of(KEY));
        registry.prepareService(p, KEY, value -> value);
        registry.publish(p);
        registry.publish(q);
        assertReason(() -> services.find(KEY), ServiceAccessException.Reason.NOT_ACTIVE);
        state.set("consumer", started);
        assertThat(services.find(KEY)).isEmpty();
        state.set("provider", started);
        Echo proxy = services.require(KEY);
        assertThat(proxy.echo("ready")).isEqualTo("ready");
        state.set("provider", quiesced);
        assertReason(() -> proxy.echo("closed"), ServiceAccessException.Reason.UNAVAILABLE);
        state.set("provider", started);
        state.set("consumer", quiesced);
        assertReason(() -> proxy.echo("closed"), ServiceAccessException.Reason.NOT_ACTIVE);
    }

    private static void assertReason(Runnable action, ServiceAccessException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceAccessException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
