package top.sywyar.pixivdownload.plugin.lifecycle;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginQuiesceGate;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

/**
 * 插件静默请求网关测试：quiesce 态插件的已声明路由被转为 503「插件不可用」，其它插件 / 核心路由、未声明 URL、
 * OPTIONS 预检、以及无 quiesce 插件时全部透明放行——金标准鉴权不受影响。
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
    private PluginQuiesceGate gate;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        routeAccessRegistry = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routeAccessRegistry.register("stats", List.of(
                WebRouteContribution.admin("/api/stats/**"),
                WebRouteContribution.admin("/pixiv-stats.html")));
        routeAccessRegistry.register("gallery", List.of(
                WebRouteContribution.visitor("/api/gallery/list")));
        lifecycleState = new PluginLifecycleState();
        gate = new PluginQuiesceGate(routeAccessRegistry, lifecycleState, localeResolver, messages);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        lenient().when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        lenient().when(messages.getOrDefault(nullable(Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private void quiesceStats() {
        lifecycleState.initialize("stats", PluginRuntimePhase.QUIESCED);
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
    @DisplayName("OPTIONS 预检放行：即便命中 quiesce 插件路由也不拦截")
    void optionsPreflightPassesThrough() throws Exception {
        quiesceStats();
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/stats/dashboard");

        gate.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
