package top.sywyar.pixivdownload.plugin.web;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginQuiesceGate;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.lifecycle.quiesce.PluginRuntimeTaskQuiescer;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("插件请求 drain 与真实 Web 分发交叉验证")
class PluginRequestDrainLifecycleIntegrationTest {

    private static final String PLUGIN_ID = "request-probe";
    private static final List<WebRouteContribution> CONTROLLER_ROUTES = List.of(
            WebRouteContribution.admin("/api/request-probe/**"));
    private static final List<WebRouteContribution> STATIC_ROUTES = List.of(
            WebRouteContribution.admin("/request-probe-static/**"));

    @Test
    @DisplayName("真实阻塞 controller 持有旧 serving：stop 先撤回准入并等请求退出后才注销")
    void stopWaitsForBlockingControllerBeforeRetiringServing() throws Exception {
        BlockingProbeController controller = new BlockingProbeController();
        try (RequestLifecycleHarness harness = new RequestLifecycleHarness(CONTROLLER_ROUTES)) {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                    .addFilters(harness.gate)
                    .build();
            AtomicReference<Throwable> requestFailure = new AtomicReference<>();
            Thread requestThread = daemonThread("blocking-controller-request", () -> {
                try {
                    mvc.perform(get("/api/request-probe/blocking"))
                            .andExpect(status().isOk())
                            .andExpect(content().string("controller-done"));
                } catch (Throwable failure) {
                    requestFailure.set(failure);
                }
            });
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stopThread = daemonThread(
                    "blocking-controller-stop",
                    () -> runCapturing(stopFailure, () -> harness.service.stop(PLUGIN_ID)));
            try {
                requestThread.start();
                assertThat(controller.blockingEntered.await(5, TimeUnit.SECONDS)).isTrue();

                stopThread.start();
                harness.awaitRequestWithdrawal();

                assertThat(stopThread.isAlive()).isTrue();
                assertThat(harness.lastRequestDrain.get().activeLeaseCount()).isOne();
                assertThat(harness.unregisterCount).hasValue(0);
                assertThat(harness.lifecycleState.phase(PLUGIN_ID)).contains(PluginRuntimePhase.QUIESCED);
            } finally {
                controller.releaseBlocking.countDown();
                join(requestThread);
                join(stopThread);
            }

            assertThat(requestFailure.get()).isNull();
            assertThat(stopFailure.get()).isNull();
            assertThat(harness.unregisterObservedDrained).isTrue();
            assertThat(harness.lifecycleState.phase(PLUGIN_ID)).contains(PluginRuntimePhase.STOPPED);
        }
    }

    @Test
    @DisplayName("真实阻塞静态资源持有旧 serving：reload 等读取结束后才撤旧资源并发布新 serving")
    void reloadWaitsForBlockingStaticResourceBeforeReplacement() throws Exception {
        BlockingStaticLocation location = new BlockingStaticLocation();
        try (RequestLifecycleHarness harness = new RequestLifecycleHarness(STATIC_ROUTES);
             StaticServing serving = new StaticServing(harness, location)) {
            AtomicReference<Throwable> requestFailure = new AtomicReference<>();
            Thread requestThread = daemonThread("blocking-static-request", () -> {
                try {
                    serving.mvc.perform(get("/request-probe-static/blocking.txt"))
                            .andExpect(status().isOk())
                            .andExpect(content().string("static-done"));
                } catch (Throwable failure) {
                    requestFailure.set(failure);
                }
            });
            AtomicReference<Throwable> reloadFailure = new AtomicReference<>();
            Thread reloadThread = daemonThread(
                    "blocking-static-reload",
                    () -> runCapturing(reloadFailure, () -> harness.service.reload(PLUGIN_ID)));
            try {
                requestThread.start();
                assertThat(location.file.readEntered.await(5, TimeUnit.SECONDS)).isTrue();

                reloadThread.start();
                harness.awaitRequestWithdrawal();

                assertThat(reloadThread.isAlive()).isTrue();
                assertThat(serving.resources.get()).isNotEmpty();
                assertThat(harness.unregisterCount).hasValue(0);
            } finally {
                location.file.releaseRead.countDown();
                join(requestThread);
                join(reloadThread);
            }

            assertThat(requestFailure.get()).isNull();
            assertThat(reloadFailure.get()).isNull();
            assertThat(harness.unregisterObservedDrained).isTrue();
            assertThat(harness.currentHandle.get().servingId()).isGreaterThan(1L);
            assertThat(harness.lifecycleState.phase(PLUGIN_ID)).contains(PluginRuntimePhase.STARTED);
            serving.mvc.perform(get("/request-probe-static/blocking.txt"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("static-done"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(AsyncSignal.class)
    @DisplayName("Servlet async 的 complete/error/timeout 均保留旧 serving 直到最终 complete，reload 才能发布新代")
    void reloadWaitsForServletAsyncFinalCompletion(AsyncSignal signal) throws Exception {
        BlockingProbeController controller = new BlockingProbeController();
        try (RequestLifecycleHarness harness = new RequestLifecycleHarness(CONTROLLER_ROUTES)) {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                    .addFilters(harness.gate)
                    .build();
            MvcResult result = mvc.perform(get("/api/request-probe/async"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            MockAsyncContext async = (MockAsyncContext) result.getRequest().getAsyncContext();
            AtomicReference<Throwable> reloadFailure = new AtomicReference<>();
            Thread reloadThread = daemonThread(
                    "servlet-async-reload",
                    () -> runCapturing(reloadFailure, () -> harness.service.reload(PLUGIN_ID)));
            try {
                reloadThread.start();
                harness.awaitRequestWithdrawal();
                assertThat(reloadThread.isAlive()).isTrue();
                assertThat(harness.lastRequestDrain.get().activeLeaseCount()).isOne();

                signal.beforeFinalComplete(async);
                if (signal != AsyncSignal.COMPLETE) {
                    assertThat(reloadThread.isAlive()).isTrue();
                    assertThat(harness.lastRequestDrain.get().activeLeaseCount()).isOne();
                    async.complete();
                }
                join(reloadThread);
            } finally {
                if (result.getRequest().isAsyncStarted()) {
                    async.complete();
                }
                join(reloadThread);
            }

            assertThat(reloadFailure.get()).isNull();
            assertThat(harness.unregisterObservedDrained).isTrue();
            assertThat(harness.lifecycleState.phase(PLUGIN_ID)).contains(PluginRuntimePhase.STARTED);
            mvc.perform(get("/api/request-probe/ping"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("pong"));
        }
    }

    private enum AsyncSignal {
        COMPLETE {
            @Override
            void beforeFinalComplete(MockAsyncContext context) {
                context.complete();
            }
        },
        ERROR {
            @Override
            void beforeFinalComplete(MockAsyncContext context) throws IOException {
                for (AsyncListener listener : List.copyOf(context.getListeners())) {
                    listener.onError(new AsyncEvent(context));
                }
            }
        },
        TIMEOUT {
            @Override
            void beforeFinalComplete(MockAsyncContext context) throws IOException {
                for (AsyncListener listener : List.copyOf(context.getListeners())) {
                    listener.onTimeout(new AsyncEvent(context));
                }
            }
        };

        abstract void beforeFinalComplete(MockAsyncContext context) throws IOException;
    }

    @RestController
    @RequestMapping("/api/request-probe")
    private static final class BlockingProbeController {
        private final CountDownLatch blockingEntered = new CountDownLatch(1);
        private final CountDownLatch releaseBlocking = new CountDownLatch(1);

        @GetMapping("/blocking")
        String blocking() {
            blockingEntered.countDown();
            await(releaseBlocking);
            return "controller-done";
        }

        @GetMapping("/async")
        void asynchronous(HttpServletRequest request) {
            request.startAsync();
        }

        @GetMapping("/ping")
        String ping() {
            return "pong";
        }
    }

    private static final class RequestLifecycleHarness implements AutoCloseable {
        private final PluginRequestLeaseRegistry requestLeases = new PluginRequestLeaseRegistry();
        private final RouteAccessRegistry routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        private final PluginLifecycleState lifecycleState = new PluginLifecycleState();
        private final PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                new ProbePlugin(), PluginSource.EXTERNAL,
                PluginRequestDrainLifecycleIntegrationTest.class.getClassLoader(), PLUGIN_ID, 1L);
        private final PluginWebContributionRegistrar webRegistrar = mock(PluginWebContributionRegistrar.class);
        private final AtomicReference<PluginWebContributionHandle> currentHandle = new AtomicReference<>();
        private final AtomicReference<PluginRequestGenerationDrain> lastRequestDrain = new AtomicReference<>();
        private final AtomicLong nextServingId = new AtomicLong(1L);
        private final AtomicInteger unregisterCount = new AtomicInteger();
        private final CountDownLatch requestWithdrawn = new CountDownLatch(1);
        private volatile boolean unregisterObservedDrained;
        private volatile Runnable afterUnregister = () -> { };
        private volatile Consumer<PluginWebContributionHandle> afterCommit = ignored -> { };
        private final PluginQuiesceGate gate;
        private final PluginLifecycleService service;

        private RequestLifecycleHarness(List<WebRouteContribution> routeContributions) {
            PluginWebContributionHandle bootHandle = new PluginWebContributionHandle(registered, 1L);
            currentHandle.set(bootHandle);
            requestLeases.publish(bootHandle.requestOwner());
            routes.register(bootHandle.requestOwner(), routeContributions);

            when(webRegistrar.currentHandle(same(registered)))
                    .thenAnswer(invocation -> Optional.ofNullable(currentHandle.get()));
            when(webRegistrar.withdrawRequests(any(PluginWebContributionHandle.class)))
                    .thenAnswer(invocation -> {
                        PluginWebContributionHandle handle = invocation.getArgument(0);
                        if (currentHandle.get() != handle) {
                            throw new IllegalStateException("stale request withdrawal: " + handle);
                        }
                        PluginRequestGenerationDrain drain = requestLeases.withdraw(handle.requestOwner())
                                .orElseThrow();
                        lastRequestDrain.set(drain);
                        requestWithdrawn.countDown();
                        return Optional.of(drain);
                    });
            when(webRegistrar.unregister(any(PluginWebContributionHandle.class)))
                    .thenAnswer(invocation -> {
                        PluginWebContributionHandle handle = invocation.getArgument(0);
                        if (!currentHandle.compareAndSet(handle, null)) {
                            return false;
                        }
                        PluginRequestGenerationDrain drain = requestLeases.withdraw(handle.requestOwner())
                                .orElseThrow();
                        unregisterObservedDrained = drain.isDrained();
                        if (!unregisterObservedDrained) {
                            throw new IllegalStateException("registrar retired an active request serving");
                        }
                        requestLeases.retire(handle.requestOwner());
                        routes.unregister(handle.requestOwner());
                        unregisterCount.incrementAndGet();
                        afterUnregister.run();
                        return true;
                    });
            when(webRegistrar.isCurrent(any(PluginWebContributionHandle.class)))
                    .thenAnswer(invocation -> currentHandle.get() == invocation.getArgument(0));
            PluginWebContributionRegistrar.PreparedWebContribution prepared =
                    mock(PluginWebContributionRegistrar.PreparedWebContribution.class);
            when(webRegistrar.prepare(same(registered))).thenReturn(prepared);
            when(webRegistrar.commit(same(prepared))).thenAnswer(invocation -> {
                PluginWebContributionHandle replacement = new PluginWebContributionHandle(
                        registered, nextServingId.incrementAndGet());
                requestLeases.publish(replacement.requestOwner());
                routes.register(replacement.requestOwner(), routeContributions);
                currentHandle.set(replacement);
                afterCommit.accept(replacement);
                return replacement;
            });

            PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
            when(runtime.inspectContextModules()).thenReturn(List.of());
            PluginRegistry pluginRegistry = mock(PluginRegistry.class);
            when(pluginRegistry.registeredPlugins()).thenReturn(List.of(registered));
            when(pluginRegistry.startFeature(same(registered))).thenReturn(true);
            PluginRuntimeTaskQuiescer quiescer = mock(PluginRuntimeTaskQuiescer.class);
            when(quiescer.withdrawSchedule(
                    any(), nullable(ScheduleCapabilityPublication.class)))
                    .thenReturn(new PluginRuntimeTaskQuiescer.QuiesceResult(Optional.empty()));
            service = new PluginLifecycleService(
                    mock(ApplicationContext.class), runtime, new PluginApplicationContextFactory(),
                    mock(PluginControllerRegistrar.class), webRegistrar,
                    mock(PluginScheduleContributionRegistrar.class), quiescer,
                    mock(PluginCapabilityContributionRegistrar.class), pluginRegistry, lifecycleState);

            AppLocaleResolver localeResolver = mock(AppLocaleResolver.class);
            AppMessages messages = mock(AppMessages.class);
            when(messages.getOrDefault(any(), any(), any())).thenReturn("plugin unavailable");
            gate = new PluginQuiesceGate(routes, lifecycleState, requestLeases, localeResolver, messages);
            service.startAll();
        }

        private void awaitRequestWithdrawal() throws InterruptedException {
            assertThat(requestWithdrawn.await(5, TimeUnit.SECONDS)).isTrue();
        }

        @Override
        public void close() {
            if (lifecycleState.phase(PLUGIN_ID).orElse(null) == PluginRuntimePhase.STARTED) {
                service.stop(PLUGIN_ID);
            }
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(StaticResourceConfig.class)
    private static class StaticMvcConfig {
    }

    private static final class StaticServing implements AutoCloseable {
        private final AtomicReference<List<StaticResourceRegistry.RegisteredStaticResource>> resources =
                new AtomicReference<>();
        private final AnnotationConfigWebApplicationContext context;
        private final MockMvc mvc;

        private StaticServing(RequestLifecycleHarness harness, Resource location) {
            StaticResourceContribution contribution = new StaticResourceContribution(
                    "classpath:/request-probe-static/", "/request-probe-static/", false);
            StaticResourceRegistry.RegisteredStaticResource registeredResource =
                    new StaticResourceRegistry.RegisteredStaticResource(
                            harness.registered, contribution, location);
            resources.set(List.of(registeredResource));
            StaticResourceRegistry registry = mock(StaticResourceRegistry.class);
            when(registry.resources()).thenAnswer(invocation -> resources.get());

            context = new AnnotationConfigWebApplicationContext();
            context.setServletContext(new MockServletContext());
            context.addBeanFactoryPostProcessor(
                    beanFactory -> beanFactory.registerSingleton("staticResourceRegistry", registry));
            context.register(StaticMvcConfig.class);
            context.refresh();
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(harness.gate)
                    .build();
            harness.afterUnregister = () -> resources.set(List.of());
            harness.afterCommit = ignored -> resources.set(List.of(registeredResource));
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private static final class BlockingStaticLocation extends AbstractResource {
        private static final URL ROOT = url("file:/request-probe-static-root/");
        private static final URL FILE = url("file:/request-probe-static-root/blocking.txt");
        private static final byte[] BODY = "static-done".getBytes(StandardCharsets.UTF_8);
        private final boolean directory;
        private final BlockingStaticLocation file;
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);

        private BlockingStaticLocation() {
            this.directory = true;
            this.file = new BlockingStaticLocation(false);
        }

        private BlockingStaticLocation(boolean directory) {
            this.directory = directory;
            this.file = null;
        }

        @Override
        public String getDescription() {
            return directory ? "blocking static root" : "blocking static file";
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (directory) {
                throw new FileNotFoundException(getDescription());
            }
            readEntered.countDown();
            await(releaseRead);
            return new ByteArrayInputStream(BODY);
        }

        @Override
        public Resource createRelative(String relativePath) {
            return directory ? file : this;
        }

        @Override
        public URL getURL() {
            return directory ? ROOT : FILE;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public String getFilename() {
            return directory ? null : "blocking.txt";
        }

        @Override
        public boolean isReadable() {
            return true;
        }

        @Override
        public long contentLength() {
            return directory ? 0L : BODY.length;
        }

        @Override
        public long lastModified() {
            return 1L;
        }
    }

    private static final class ProbePlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return PLUGIN_ID;
        }

        @Override
        public String displayName() {
            return "plugin.name";
        }

        @Override
        public String description() {
            return "plugin.description";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }
    }

    private static Thread daemonThread(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void runCapturing(AtomicReference<Throwable> failure, Runnable action) {
        try {
            action.run();
        } catch (Throwable caught) {
            failure.set(caught);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        if (thread == null || !thread.isAlive()) {
            return;
        }
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(thread.isAlive()).as("thread must finish: " + thread.getName()).isFalse();
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static URL url(String value) {
        try {
            return URI.create(value).toURL();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
