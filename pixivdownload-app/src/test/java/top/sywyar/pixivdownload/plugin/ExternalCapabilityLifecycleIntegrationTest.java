package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.push.PushChannelRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PushChannelCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;
import top.sywyar.pixivdownload.plugin.lifecycle.quiesce.PluginRuntimeTaskQuiescer;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.web.PluginControllerRegistrar;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@DisplayName("外置 capability invocation 与插件生命周期交叉验证")
class ExternalCapabilityLifecycleIntegrationTest {

    private static final String PLUGIN_ID = "capability-probe";

    @Test
    @DisplayName("stop 撤回新调用后不可中断地排空旧调用，再停止插件并关闭 child context")
    void stopDrainsCapabilityInvocationBeforeClosingContextAndRestoresInterrupt() throws Exception {
        BlockingControl control = new BlockingControl();
        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext()) {
            parent.registerBean(BlockingControl.class, () -> control);
            parent.refresh();

            ProbePlugin plugin = new ProbePlugin();
            PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                    plugin, PluginSource.EXTERNAL, getClass().getClassLoader(), PLUGIN_ID, 9L);
            PluginRegistry pluginRegistry = mock(PluginRegistry.class);
            when(pluginRegistry.registeredPlugins()).thenReturn(List.of(registered));

            PluginContextModule module = new PluginContextModule(
                    PLUGIN_ID, getClass().getClassLoader(), List.of(BlockingCapabilityConfiguration.class));
            PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
            when(runtime.inspectContextModules()).thenReturn(List.of(module));

            PluginScheduleContributionRegistrar scheduleRegistrar = mock(PluginScheduleContributionRegistrar.class);
            when(scheduleRegistrar.register(any(), same(registered), any())).thenReturn(Optional.empty());
            PluginRuntimeTaskQuiescer quiescer = new PluginRuntimeTaskQuiescer(
                    scheduleRegistrar, new PluginStreamRegistry(), new QueueOperationRegistry(List.of()));

            ExternalCapabilityInvocationRegistry invocationRegistry = new ExternalCapabilityInvocationRegistry();
            PushChannelRegistry pushRegistry = new PushChannelRegistry(List.of());
            PushChannelCapabilityAdapter pushAdapter =
                    new PushChannelCapabilityAdapter(pushRegistry, invocationRegistry);
            PluginCapabilityContributionRegistrar realRegistrar = new PluginCapabilityContributionRegistrar(
                    List.<PluginCapabilityContributionAdapter<?>>of(pushAdapter),
                    List.of(),
                    List.<ExternalRuntimeCapabilityAdapter>of(pushAdapter),
                    invocationRegistry);
            PluginCapabilityContributionRegistrar capabilityRegistrar = spy(realRegistrar);
            CountDownLatch withdrawn = new CountDownLatch(1);
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                withdrawn.countDown();
                return result;
            }).when(capabilityRegistrar).withdraw(nullable(ExternalCapabilityPublication.class));

            PluginLifecycleService service = new PluginLifecycleService(
                    parent, runtime, new PluginApplicationContextFactory(),
                    mock(PluginControllerRegistrar.class), mock(PluginWebContributionRegistrar.class),
                    scheduleRegistrar, quiescer, capabilityRegistrar, pluginRegistry,
                    new PluginLifecycleState());
            service.startAll();
            PushChannel proxy = pushRegistry.byType(PushChannelType.BARK).orElseThrow();
            ConfigurableApplicationContext child = service.contextFor(PLUGIN_ID).orElseThrow();

            AtomicReference<Throwable> invocationFailure = new AtomicReference<>();
            Thread invocationThread = daemonThread("blocking-capability", () -> {
                try {
                    assertThat(proxy.isConfigured()).isTrue();
                } catch (Throwable failure) {
                    invocationFailure.set(failure);
                }
            });
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            AtomicBoolean interruptedAtReturn = new AtomicBoolean();
            Thread stopThread = daemonThread("capability-stop", () -> {
                try {
                    service.stop(PLUGIN_ID);
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                } finally {
                    interruptedAtReturn.set(Thread.currentThread().isInterrupted());
                }
            });

            invocationThread.start();
            assertThat(control.entered.await(5, TimeUnit.SECONDS)).isTrue();
            stopThread.start();
            assertThat(withdrawn.await(5, TimeUnit.SECONDS)).isTrue();
            stopThread.interrupt();

            assertThat(stopThread.isAlive()).isTrue();
            assertThat(service.phase(PLUGIN_ID)).contains(PluginRuntimePhase.QUIESCED);
            assertThat(child.isActive()).isTrue();
            assertThat(plugin.stopCount).hasValue(0);
            assertThatThrownBy(proxy::isConfigured)
                    .isInstanceOf(ExternalCapabilityUnavailableException.class);

            control.release.countDown();
            invocationThread.join(5_000L);
            stopThread.join(5_000L);

            assertThat(invocationThread.isAlive()).isFalse();
            assertThat(stopThread.isAlive()).isFalse();
            assertThat(invocationFailure.get()).isNull();
            assertThat(stopFailure.get()).isNull();
            assertThat(interruptedAtReturn).isTrue();
            assertThat(pushRegistry.byType(PushChannelType.BARK)).isEmpty();
            assertThat(plugin.stopCount).hasValue(1);
            assertThat(child.isActive()).isFalse();
            assertThat(service.contextFor(PLUGIN_ID)).isEmpty();
            assertThat(service.phase(PLUGIN_ID)).contains(PluginRuntimePhase.STOPPED);
        } finally {
            control.release.countDown();
        }
    }

    private static Thread daemonThread(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class BlockingControl {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
    }

    @Configuration(proxyBeanMethods = false)
    static class BlockingCapabilityConfiguration {

        @Bean
        PushChannel blockingPushChannel(BlockingControl control) {
            return new PushChannel() {
                @Override
                public PushChannelType type() {
                    return PushChannelType.BARK;
                }

                @Override
                public boolean isConfigured() {
                    control.entered.countDown();
                    try {
                        if (!control.release.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("timed out waiting to release capability invocation");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("capability invocation interrupted", interrupted);
                    }
                    return true;
                }

                @Override
                public List<PushFormat> supportedFormats() {
                    return List.of(PushFormat.PLAIN_TEXT);
                }

                @Override
                public PushResult send(RenderedMessage message) {
                    return PushResult.ok(type());
                }

                @Override
                public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) {
                    return PushResult.ok(type());
                }
            };
        }
    }

    private static final class ProbePlugin implements PixivFeaturePlugin {
        private final AtomicInteger stopCount = new AtomicInteger();

        @Override
        public String id() {
            return PLUGIN_ID;
        }

        @Override
        public String displayName() {
            return "capability-probe.name";
        }

        @Override
        public String description() {
            return "capability-probe.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public void stop() {
            stopCount.incrementAndGet();
        }
    }
}
