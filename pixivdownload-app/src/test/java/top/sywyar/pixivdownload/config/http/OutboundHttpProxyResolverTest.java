package top.sywyar.pixivdownload.config.http;

import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("宿主出站 HTTP 代理路由转换")
class OutboundHttpProxyResolverTest {

    private final ProxyConfig proxyConfig = new ProxyConfig();
    private final OutboundHttpProxyResolver resolver =
            new OutboundHttpProxyResolver(proxyConfig);

    @AfterEach
    void clearScopedOverride() {
        OutboundProxyOverride.clear();
    }

    @Test
    @DisplayName("直连路由忽略 scoped override，scoped-or-direct 精确采用覆盖")
    void distinguishesStrictDirectFromScopedDirect() throws Exception {
        OutboundProxyOverride.set("127.0.0.2:7891");

        assertThat(resolver.resolve(OutboundHttpRoute.direct())).isNull();
        assertProxy(
                resolver.resolve(OutboundHttpRoute.scopedOrDirect()),
                "127.0.0.2",
                7891);

        OutboundProxyOverride.setDirect();
        assertThat(resolver.resolve(OutboundHttpRoute.scopedOrDirect())).isNull();
    }

    @Test
    @DisplayName("继承路由优先 scoped override，再按 enabled 读取全局代理")
    void inheritedRouteHonorsScopedOverrideBeforeGlobalEnabledState() throws Exception {
        proxyConfig.setHost("127.0.0.3");
        proxyConfig.setPort(7892);
        proxyConfig.setEnabled(true);

        assertProxy(
                resolver.resolve(OutboundHttpRoute.inherit()),
                "127.0.0.3",
                7892);

        proxyConfig.setEnabled(false);
        assertThat(resolver.resolve(OutboundHttpRoute.inherit())).isNull();

        OutboundProxyOverride.set("127.0.0.4:7893");
        assertProxy(
                resolver.resolve(OutboundHttpRoute.inherit()),
                "127.0.0.4",
                7893);

        OutboundProxyOverride.setDirect();
        assertThat(resolver.resolve(OutboundHttpRoute.inherit())).isNull();
    }

    @Test
    @DisplayName("configured 路由忽略 enabled 和 scoped override，无效端点回退直连")
    void configuredRouteUsesGlobalEndpointIndependently() throws Exception {
        proxyConfig.setEnabled(false);
        proxyConfig.setHost("127.0.0.5");
        proxyConfig.setPort(7894);
        OutboundProxyOverride.set("127.0.0.6:7895");

        assertProxy(
                resolver.resolve(OutboundHttpRoute.configuredProxy()),
                "127.0.0.5",
                7894);

        proxyConfig.setPort(70_000);
        assertThat(resolver.resolve(OutboundHttpRoute.configuredProxy())).isNull();
    }

    @Test
    @DisplayName("required global 路由无有效端点时 fail-closed，scoped direct 仍优先")
    void requiredGlobalRouteFailsClosedWithoutEndpoint() {
        proxyConfig.setHost(" ");
        proxyConfig.setPort(0);

        assertThatThrownBy(() -> resolver.resolve(OutboundHttpRoute.requiredGlobalProxy()))
                .isInstanceOf(HttpException.class)
                .hasMessageContaining("required");

        OutboundProxyOverride.setDirect();
        assertThatCodeDoesNotThrowAndReturnsDirect();
    }

    @Test
    @DisplayName("显式代理按请求动态解析，并由 scoped override 抢占")
    void explicitProxyIsDynamicAndScopedOverrideHasPriority() throws Exception {
        AtomicBoolean providerCalled = new AtomicBoolean();
        OutboundHttpRoute route = OutboundHttpRoute.requiredExplicitProxy(() -> {
            providerCalled.set(true);
            return URI.create("http://127.0.0.7:7896");
        });

        assertProxy(resolver.resolve(route), "127.0.0.7", 7896);
        assertThat(providerCalled).isTrue();

        providerCalled.set(false);
        OutboundProxyOverride.set("127.0.0.8:7897");
        assertProxy(resolver.resolve(route), "127.0.0.8", 7897);
        assertThat(providerCalled).isFalse();

        OutboundProxyOverride.clear();
        assertThatThrownBy(() -> resolver.resolve(
                OutboundHttpRoute.requiredExplicitProxy(
                        () -> URI.create("https://127.0.0.7:7896/path"))))
                .isInstanceOf(HttpException.class)
                .hasMessageContaining("valid explicit");

        IllegalStateException providerFailure =
                new IllegalStateException("dynamic proxy unavailable");
        assertThatThrownBy(() -> resolver.resolve(
                OutboundHttpRoute.requiredExplicitProxy(() -> {
                    throw providerFailure;
                })))
                .isInstanceOf(HttpException.class)
                .hasMessageContaining("resolution failed")
                .hasCause(providerFailure);
    }

    private void assertThatCodeDoesNotThrowAndReturnsDirect() {
        try {
            assertThat(resolver.resolve(OutboundHttpRoute.requiredGlobalProxy())).isNull();
        } catch (HttpException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertProxy(HttpHost proxy, String host, int port) {
        assertThat(proxy).isNotNull();
        assertThat(proxy.getHostName()).isEqualTo(host);
        assertThat(proxy.getPort()).isEqualTo(port);
        assertThat(proxy.getSchemeName()).isEqualTo("http");
    }
}
