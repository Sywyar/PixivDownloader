package top.sywyar.pixivdownload.plugin.lifecycle;

import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestGenerationDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistryTestAccess;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 插件静默请求网关测试：quiesce 态插件的已声明路由被转为 503「插件不可用」；外置 serving 请求持有代际租约，
 * 覆盖同步 / Servlet async 完成边界；核心路由和未声明 URL 仍透明交给既有鉴权链路。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("插件静默请求网关：quiesce 插件路由转 503，其余透明放行")
class PluginQuiesceGateTest {

    @Mock
    private AppLocaleResolver localeResolver;
    @Mock
    private AppMessages messages;
    @Mock
    private FilterChain chain;

    private RouteAccessRegistry routeAccessRegistry;
    private PluginLifecycleState lifecycleState;
    private PluginRequestLeaseRegistry requestLeaseRegistry;
    private PluginQuiesceGate gate;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private PluginRequestOwner statsOwner;
    private PluginRequestOwner galleryOwner;

    @BeforeEach
    void setUp() {
        routeAccessRegistry = new RouteAccessRegistry(new PluginRegistry(List.of()));
        statsOwner = new PluginRequestOwner("stats", 4L, 9L);
        galleryOwner = new PluginRequestOwner("gallery", 2L, 10L);
        routeAccessRegistry.register(statsOwner, List.of(
                WebRouteContribution.admin("/api/stats/**"),
                WebRouteContribution.admin("/pixiv-stats.html")));
        routeAccessRegistry.register(galleryOwner, List.of(
                WebRouteContribution.visitor("/api/gallery/list")));
        lifecycleState = new PluginLifecycleState();
        lifecycleState.initialize("stats", PluginRuntimePhase.STARTED);
        lifecycleState.initialize("gallery", PluginRuntimePhase.STARTED);
        requestLeaseRegistry = new PluginRequestLeaseRegistry();
        requestLeaseRegistry.publish(statsOwner);
        requestLeaseRegistry.publish(galleryOwner);
        gate = new PluginQuiesceGate(
                routeAccessRegistry, lifecycleState, requestLeaseRegistry, localeResolver, messages);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        lenient().when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        lenient().when(messages.getOrDefault(nullable(Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private void quiesceStats() {
        lifecycleState.transition("stats", PluginRuntimePhase.QUIESCED);
    }

    @Test
    @DisplayName("路由租约取得后的致命错误释放精确 serving 代且不进入下游")
    void fatalAfterRouteLeaseAcquireReleasesExactServingGeneration() throws Exception {
        AtomicReference<Error> nextFailure = new AtomicReference<>();
        requestLeaseRegistry = PluginRequestLeaseRegistryTestAccess.withAcquireProbe(() -> {
            Error failure = nextFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
        });
        requestLeaseRegistry.publish(statsOwner);
        requestLeaseRegistry.publish(galleryOwner);
        gate = new PluginQuiesceGate(
                routeAccessRegistry, lifecycleState, requestLeaseRegistry, localeResolver, messages);
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        for (Error expected : List.of(new OutOfMemoryError("route-lease"), new ThreadDeath())) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> gate.doFilterInternal(request, response, chain)))
                    .isSameAs(expected);
        }

        PluginRequestGenerationDrain drain = requestLeaseRegistry.withdraw(statsOwner).orElseThrow();
        assertThat(drain.activeLeaseCount()).isZero();
        assertThat(drain.awaitDrained()).isTrue();
        assertThat(requestLeaseRegistry.retire(statsOwner)).isTrue();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("路由决策返回与下游调用边界的致命错误不会遗失 serving 租约")
    void fatalRouteCallBoundariesLeaveExactServingGenerationDrained() throws Exception {
        AtomicReference<String> failurePoint = new AtomicReference<>();
        AtomicReference<ThreadDeath> expectedFailure = new AtomicReference<>();
        gate = new PluginQuiesceGate(
                routeAccessRegistry, lifecycleState, requestLeaseRegistry, localeResolver, messages,
                () -> throwAt("decision-return", failurePoint, expectedFailure),
                () -> throwAt("after-activate", failurePoint, expectedFailure),
                () -> throwAt("before-downstream", failurePoint, expectedFailure),
                () -> {
                });
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        for (String point : List.of("decision-return", "after-activate", "before-downstream")) {
            ThreadDeath expected = new ThreadDeath();
            failurePoint.set(point);
            expectedFailure.set(expected);

            assertThat(catchThrowable(() -> gate.doFilterInternal(request, response, chain)))
                    .isSameAs(expected);
        }

        PluginRequestGenerationDrain drain = requestLeaseRegistry.withdraw(statsOwner).orElseThrow();
        assertThat(drain.activeLeaseCount()).isZero();
        assertThat(drain.awaitDrained()).isTrue();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("async listener 已注册但返回前遇到致命错误时重试同一 listener 且不提前释放")
    void fatalAsyncListenerSideEffectWindowRetainsObservableLease() throws Exception {
        ThreadDeath expected = new ThreadDeath();
        AtomicBoolean firstAttach = new AtomicBoolean(true);
        MockAsyncContext ambiguousContext = new MockAsyncContext(request, response) {
            @Override
            public void addListener(AsyncListener listener) {
                super.addListener(listener);
                if (firstAttach.compareAndSet(true, false)) {
                    throw expected;
                }
            }
        };
        request.setAsyncSupported(true);
        request.setAsyncContext(ambiguousContext);
        request.setAsyncStarted(true);
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        assertThat(catchThrowable(() -> gate.doFilterInternal(request, response, chain)))
                .isSameAs(expected);

        PluginRequestGenerationDrain drain = requestLeaseRegistry.withdraw(statsOwner).orElseThrow();
        assertThat(ambiguousContext.getListeners()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(drain.activeLeaseCount()).isOne();
        ambiguousContext.complete();
        assertThat(drain.activeLeaseCount()).isZero();
        assertThat(drain.awaitDrained()).isTrue();
    }

    @Test
    @DisplayName("无 quiesce 插件时透明：quiesce 态空集，命中 stats 路由也照常放行")
    void transparentWhenNothingQuiesced() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("quiesce 插件的 API 路由被拦截：503 JSON、不进入后续过滤链")
    void blocksQuiescedPluginApi() throws Exception {
        quiesceStats();
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).isNotBlank();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("带矩阵参数的 quiesce 插件路由仍按规范路径拦截")
    void blocksQuiescedPluginRouteWithMatrixParameters() throws Exception {
        quiesceStats();
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard;trace=1");

        gate.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("存在受管外置生命周期时无法安全规范化的路径 fail closed")
    void unsafePathIsFailClosedWhenExternalLifecycleExists() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/stats%3Bignored/dashboard");

        gate.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("quiesce 插件的页面路由被拦截：503 提示页")
    void blocksQuiescedPluginPage() throws Exception {
        quiesceStats();
        request.setMethod("GET");
        request.setRequestURI("/pixiv-stats.html");

        gate.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).contains("text/html");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("其它（未 quiesce）插件的路由不受影响：照常放行")
    void otherPluginRoutesPassThrough() throws Exception {
        quiesceStats();
        request.setMethod("GET");
        request.setRequestURI("/api/gallery/list");

        gate.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("未声明 URL 透明放行（交由 AuthFilter「未声明即 404」）：网关不误拦")
    void undeclaredUrlPassesThrough() throws Exception {
        quiesceStats();
        request.setMethod("GET");
        request.setRequestURI("/api/totally-unknown");

        gate.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("显式命中插件声明的 OPTIONS 也受 quiesce gate 保护")
    void optionsRequestForQuiescedPluginIsBlocked() throws Exception {
        quiesceStats();
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("同步请求在过滤链执行期间持有 serving 租约并在返回后释放")
    void synchronousRequestHoldsLeaseUntilChainReturns() throws Exception {
        PluginRequestOwner owner = statsOwner;
        AtomicReference<PluginRequestGenerationDrain> observedDrain = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            PluginRequestGenerationDrain drain = requestLeaseRegistry.withdraw(owner).orElseThrow();
            observedDrain.set(drain);
            assertThat(drain.activeLeaseCount()).isOne();
            assertThat(drain.isDrained()).isFalse();
            return null;
        }).when(chain).doFilter(request, response);
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);

        assertThat(observedDrain.get()).isNotNull();
        assertThat(observedDrain.get().isDrained()).isTrue();
        assertThat(observedDrain.get().activeLeaseCount()).isZero();
    }

    @Test
    @DisplayName("Servlet async 请求在过滤线程返回后仍持有租约直至 async complete")
    void asynchronousRequestHoldsLeaseUntilCompletion() throws Exception {
        PluginRequestOwner owner = statsOwner;
        request.setAsyncSupported(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            request.startAsync();
            return null;
        }).when(chain).doFilter(request, response);
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);
        PluginRequestGenerationDrain drain = requestLeaseRegistry.withdraw(owner).orElseThrow();

        assertThat(drain.activeLeaseCount()).isOne();
        assertThat(drain.isDrained()).isFalse();
        MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
        context.getListeners().get(0).onTimeout(new jakarta.servlet.AsyncEvent(context));
        assertThat(drain.isDrained()).isFalse();
        request.getAsyncContext().complete();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("active async context 拒绝 listener 时保留租约而不假报 drain")
    void asyncListenerAttachmentFailureRemainsBusyWhenCompletionCannotBeConfirmed() throws Exception {
        PluginRequestOwner owner = statsOwner;
        request.setAsyncSupported(true);
        MockAsyncContext hostileContext = new MockAsyncContext(request, response) {
            @Override
            public void addListener(AsyncListener listener) {
                throw new IllegalStateException("listener rejected");
            }

            @Override
            public void complete() {
                // Deliberately remain active to prove the gate fails closed.
            }
        };
        request.setAsyncContext(hostileContext);
        request.setAsyncStarted(true);
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> gate.doFilterInternal(request, response, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listener rejected");
        PluginRequestGenerationDrain drain = requestLeaseRegistry.withdraw(owner).orElseThrow();

        assertThat(request.isAsyncStarted()).isTrue();
        assertThat(drain.activeLeaseCount()).isOne();
        assertThat(drain.isDrained()).isFalse();
    }

    @Test
    @DisplayName("serving 已撤回时即使阶段读取仍为 STARTED 也拒绝迟到请求")
    void withdrawnServingRejectsLateRequest() throws Exception {
        PluginRequestOwner owner = statsOwner;
        requestLeaseRegistry.withdraw(owner).orElseThrow();
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("旧路由快照只尝试旧 owner，不会借用同插件的新 serving 租约")
    void staleRouteSnapshotCannotBorrowReplacementServing() throws Exception {
        RouteAccessRegistry staleRoutes = mock(RouteAccessRegistry.class);
        RouteAccessRegistry.RegisteredRoute staleRoute = new RouteAccessRegistry.RegisteredRoute(
                "stats", WebRouteContribution.admin("/api/stats/**"), statsOwner);
        when(staleRoutes.resolve(anyString(), any())).thenReturn(java.util.Optional.of(staleRoute));
        PluginRequestGenerationDrain oldDrain = requestLeaseRegistry.withdraw(statsOwner).orElseThrow();
        assertThat(oldDrain.isDrained()).isTrue();
        assertThat(requestLeaseRegistry.retire(statsOwner)).isTrue();
        PluginRequestOwner replacement = new PluginRequestOwner("stats", 4L, 11L);
        requestLeaseRegistry.publish(replacement);
        gate = new PluginQuiesceGate(
                staleRoutes, lifecycleState, requestLeaseRegistry, localeResolver, messages);
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
        var replacementLease = requestLeaseRegistry.prepareLease(replacement).orElseThrow();
        try (replacementLease) {
            assertThat(requestLeaseRegistry.activate(replacementLease)).isTrue();
            assertThat(replacementLease.owner()).isEqualTo(replacement);
        }
    }

    @Test
    @DisplayName("受管外置插件若出现 owner-null 路由快照则 fail closed")
    void ownerlessManagedRouteIsBlocked() throws Exception {
        RouteAccessRegistry ownerlessRoutes = mock(RouteAccessRegistry.class);
        RouteAccessRegistry.RegisteredRoute ownerless = new RouteAccessRegistry.RegisteredRoute(
                "stats", WebRouteContribution.admin("/api/stats/**"));
        when(ownerlessRoutes.resolve(anyString(), any())).thenReturn(java.util.Optional.of(ownerless));
        gate = new PluginQuiesceGate(
                ownerlessRoutes, lifecycleState, requestLeaseRegistry, localeResolver, messages);
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("带外置 owner 的路由缺少生命周期状态时 fail closed")
    void requestOwnedRouteWithoutLifecycleStateIsBlocked() throws Exception {
        PluginRequestOwner orphan = new PluginRequestOwner("orphan", 1L, 12L);
        routeAccessRegistry.register(orphan, List.of(
                WebRouteContribution.admin("/api/orphan/**")));
        requestLeaseRegistry.publish(orphan);
        request.setMethod("GET");
        request.setRequestURI("/api/orphan/status");

        gate.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("路由归属解析异常时只要存在受管外置 serving 就 fail closed")
    void routeResolutionFailureIsFailClosedForManagedPlugins() throws Exception {
        RouteAccessRegistry brokenRoutes = mock(RouteAccessRegistry.class);
        when(brokenRoutes.resolve(anyString(), any())).thenThrow(new IllegalStateException("ambiguous route"));
        gate = new PluginQuiesceGate(
                brokenRoutes, lifecycleState, requestLeaseRegistry, localeResolver, messages);
        request.setMethod("GET");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);

        assertThat(requestLeaseRegistry.currentOwner("stats")).contains(statsOwner);
        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
    }

    private static void throwAt(String expectedPoint,
                                AtomicReference<String> failurePoint,
                                AtomicReference<ThreadDeath> expectedFailure) {
        if (expectedPoint.equals(failurePoint.get())
                && failurePoint.compareAndSet(expectedPoint, null)) {
            throw expectedFailure.getAndSet(null);
        }
    }

}
