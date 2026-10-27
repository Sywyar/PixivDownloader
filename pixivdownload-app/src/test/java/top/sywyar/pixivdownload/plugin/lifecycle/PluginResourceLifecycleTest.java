package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextRefreshedEvent;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResourceException;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResources;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

@DisplayName("插件资源归属与生命周期回滚")
class PluginResourceLifecycleTest extends PluginLifecycleServiceTestSupport {

    @Test
    @DisplayName("资源关闭失败保留原 context，重试成功后才能重启且旧句柄永久失效")
    void closeFailureKeepsContextAndRestartCreatesNewResources() {
        try (ContextHarness h = new ContextHarness()) {
            h.service.startAll();
            var child = h.service.contextFor("ext-demo").orElseThrow();
            var resources = child.getBean(PluginResources.class);
            var scope = resources.openScope();
            AtomicInteger closes = new AtomicInteger();
            scope.acquire(() -> () -> {
                if (closes.incrementAndGet() == 1) throw new Exception("private-failure");
            });
            assertThatThrownBy(() -> h.service.stop("ext-demo"))
                    .isInstanceOf(PluginResourceException.class).hasMessage("PLUGIN_RESOURCE_CLOSE_FAILED");
            assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.QUIESCED);
            assertThat(child.isActive()).isTrue();
            assertThat(h.service.contextFor("ext-demo")).contains(child);
            h.service.stop("ext-demo");
            assertThat(child.isActive()).isFalse();
            assertThat(closes).hasValue(2);

            h.service.start("ext-demo");
            var replacement = h.service.contextFor("ext-demo").orElseThrow().getBean(PluginResources.class);
            AtomicInteger newCloses = new AtomicInteger();
            replacement.openScope().acquire(() -> newCloses::incrementAndGet);
            assertThatThrownBy(resources::openScope).hasMessage("PLUGIN_RESOURCE_CLOSED");
            scope.close();
            assertThat(newCloses).hasValue(0);
            h.service.stop("ext-demo");
            assertThat(newCloses).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"early", "circular", "late"})
    @DisplayName("刷新失败先排空已启动回调，再释放资源与销毁已建 Bean")
    void failedRefreshDefersBeanDestructionUntilRealDrain(String mode) throws Exception {
        try (ContextHarness h = new ContextHarness()) {
            Probe probe = new Probe();
            h.parent.getBeanFactory().registerSingleton("resourceProbe", probe);
            Class<?> configuration = switch (mode) {
                case "circular" -> CircularFailureConfiguration.class;
                case "late" -> LateFailureConfiguration.class;
                default -> FailingConfiguration.class;
            };
            when(h.runtime.inspectContextModules()).thenReturn(List.of(new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(
                    configuration))));
            CountDownLatch withdrawn = new CountDownLatch(1);
            when(h.webRegistrar.withdrawRequests(same(h.bootWebHandle))).thenAnswer(invocation -> {
                withdrawn.countDown();
                return Optional.empty();
            });
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread start = new Thread(() -> {
                try { h.service.startAll(); } catch (Throwable thrown) { failure.set(thrown); }
            });
            start.start();
            try {
                assertThat(probe.entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(withdrawn.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(probe.events).isEmpty();
                assertThat(probe.lifecycleRunning.get()).isEqualTo(mode.equals("late"));
                assertThat(start.isAlive()).isTrue();
                probe.release.countDown();
                start.join(5000);
                assertThat(start.isAlive()).isFalse();
                assertThat(failure.get()).isNull();
                assertThat(probe.failure.get()).isNull();
                if (mode.equals("late")) {
                    assertThat(probe.events).containsExactly("callback-exit", "resource-close", "lifecycle-stop", "bean-close");
                } else {
                    assertThat(probe.events).containsExactly("callback-exit", "resource-close", "bean-close");
                }
                assertThat(probe.lifecycleRunning).isFalse();
                assertThat(h.service.phase("ext-demo")).contains(PluginRuntimePhase.STOPPED);
                assertThat(h.service.contextFor("ext-demo")).isEmpty();
            } finally {
                probe.release.countDown();
                start.join(5000);
                if (probe.worker != null) probe.worker.join(5000);
            }
        }
    }

    static final class Probe {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean lifecycleRunning = new AtomicBoolean();
        Thread worker;
    }

    @Configuration(proxyBeanMethods = false)
    static class LateFailureConfiguration {
        @Bean AutoCloseable ownedBean(PluginResources resources, Probe probe) throws Exception {
            return startCallback(resources, probe);
        }
        @Bean SmartLifecycle lifecycle(Probe probe) {
            return new SmartLifecycle() {
                @Override public void start() { probe.lifecycleRunning.set(true); }
                @Override public void stop() { probe.events.add("lifecycle-stop"); probe.lifecycleRunning.set(false); }
                @Override public boolean isRunning() { return probe.lifecycleRunning.get(); }
            };
        }
        @Bean ApplicationListener<ContextRefreshedEvent> failAfterStart() {
            return event -> { throw new IllegalStateException("fixture-late-refresh"); };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingConfiguration {
        @Bean
        AutoCloseable ownedBean(PluginResources resources, Probe probe) throws Exception {
            return startCallback(resources, probe);
        }

        @Bean
        Object failsAfterOwnedBean(AutoCloseable ownedBean) {
            throw new IllegalStateException("fixture-refresh-failure");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CircularFailureConfiguration {
        @Bean CircularA circularA() { return new CircularA(); }
        @Bean CircularB circularB() { return new CircularB(); }
    }

    static class CircularA implements InitializingBean {
        @Autowired CircularB dependency;
        @Override public void afterPropertiesSet() { throw new IllegalStateException("fixture-circular-failure"); }
    }

    static class CircularB implements InitializingBean, AutoCloseable {
        @Autowired CircularA dependency;
        @Autowired PluginResources resources;
        @Autowired Probe probe;
        AutoCloseable owned;

        @Override public void afterPropertiesSet() throws Exception { owned = startCallback(resources, probe); }
        @Override public void close() throws Exception { owned.close(); }
    }

    private static AutoCloseable startCallback(PluginResources resources, Probe probe) throws Exception {
        var scope = resources.openScope();
        scope.acquire(() -> () -> probe.events.add("resource-close"));
        Runnable callback = scope.guard(() -> {
            probe.entered.countDown();
            try {
                if (!probe.release.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback timeout");
                probe.events.add("callback-exit");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        probe.worker = new Thread(() -> {
            try { callback.run(); } catch (Throwable thrown) { probe.failure.set(thrown); }
        });
        probe.worker.start();
        if (!probe.entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback did not enter");
        return () -> probe.events.add("bean-close");
    }
}
