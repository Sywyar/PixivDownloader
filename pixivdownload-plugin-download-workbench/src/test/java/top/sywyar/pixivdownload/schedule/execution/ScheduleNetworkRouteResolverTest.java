package top.sywyar.pixivdownload.schedule.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("计划任务网络路由优先级")
class ScheduleNetworkRouteResolverTest {

    @Test
    @DisplayName("任务单独代理优先于来源默认路由和宿主全局代理")
    void taskProxyHasHighestPriority() {
        ScheduleNetworkRouteResolver resolver = new ScheduleNetworkRouteResolver(
                settings(true, "host.proxy", 7890));

        ScheduledNetworkRoute route = resolver.resolve(
                "task.proxy:9080",
                ScheduledNetworkRoute.proxy("source.proxy", 8080, null));

        assertThat(route.mode()).isEqualTo(ScheduledNetworkRoute.Mode.PROXY);
        assertThat(route.proxyHost()).isEqualTo("task.proxy");
        assertThat(route.proxyPort()).isEqualTo(9080);
    }

    @Test
    @DisplayName("来源直连与来源代理均优先于宿主全局代理")
    void sourceDefaultRouteOverridesHostGlobalProxy() {
        ScheduleNetworkRouteResolver resolver = new ScheduleNetworkRouteResolver(
                settings(true, "host.proxy", 7890));
        ScheduledNetworkRoute sourceDirect = ScheduledNetworkRoute.direct();
        ScheduledNetworkRoute sourceProxy = ScheduledNetworkRoute.proxy(
                "source.proxy", 8080, "source-reference");

        assertThat(resolver.resolve(null, sourceDirect)).isSameAs(sourceDirect);
        assertThat(resolver.resolve(null, sourceProxy)).isSameAs(sourceProxy);
    }

    @Test
    @DisplayName("来源继承或未声明时回落宿主全局路由")
    void inheritedSourceRouteFallsBackToHostGlobalRoute() {
        ScheduleNetworkRouteResolver resolver = new ScheduleNetworkRouteResolver(
                settings(true, "host.proxy", 7890));

        ScheduledNetworkRoute inherited = resolver.resolve(
                null, ScheduledNetworkRoute.inherit());
        ScheduledNetworkRoute unspecified = resolver.resolve(null, null);
        ScheduledNetworkRoute legacy = resolver.resolve(null);

        assertThat(inherited.proxyHost()).isEqualTo("host.proxy");
        assertThat(unspecified).isEqualTo(inherited);
        assertThat(legacy).isEqualTo(inherited);
    }

    @Test
    @DisplayName("非法任务单独代理不会静默回落来源或宿主路由")
    void invalidTaskProxyFailsClosed() {
        ScheduleNetworkRouteResolver resolver = new ScheduleNetworkRouteResolver(
                settings(false, null, 0));

        assertThatThrownBy(() -> resolver.resolve(
                "bad proxy", ScheduledNetworkRoute.direct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid task proxy snapshot");
    }

    @Test
    @DisplayName("非法来源默认代理不会静默回落宿主路由")
    void invalidSourceProxyFailsClosed() {
        ScheduleNetworkRouteResolver resolver = new ScheduleNetworkRouteResolver(
                settings(false, null, 0));

        assertThatThrownBy(() -> resolver.resolve(
                null, ScheduledNetworkRoute.proxy("http://source.proxy", 8080, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid source default proxy route");
    }

    @Test
    @DisplayName("合法任务单独代理在非法来源默认代理校验前胜出")
    void taskProxyWinsBeforeInvalidSourceProxyValidation() {
        ScheduleNetworkRouteResolver resolver = new ScheduleNetworkRouteResolver(
                settings(false, null, 0));

        ScheduledNetworkRoute route = resolver.resolve(
                "task.proxy:9080",
                ScheduledNetworkRoute.proxy("http://source.proxy", 8080, null));

        assertThat(route.mode()).isEqualTo(ScheduledNetworkRoute.Mode.PROXY);
        assertThat(route.proxyHost()).isEqualTo("task.proxy");
        assertThat(route.proxyPort()).isEqualTo(9080);
    }

    @ParameterizedTest
    @CsvSource({"' proxy.example:1 ', proxy.example, 1", "'local_host:+65535', local_host, 65535"})
    @DisplayName("任务代理保留首尾空白容错、主机字符和端口边界")
    void acceptsTaskProxyBoundaries(String value, String host, int port) {
        ScheduledNetworkRoute route = new ScheduleNetworkRouteResolver(settings(false, null, 0)).resolve(value);
        assertThat(route.proxyHost()).isEqualTo(host);
        assertThat(route.proxyPort()).isEqualTo(port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"proxy", "proxy:", ":8080", "proxy:0", "proxy:65536", "proxy:2147483648",
            "proxy:abc", "proxy: 8080", "proxy :8080", "http://proxy:8080", "user:pass@proxy:8080",
            "proxy/path:8080", "[::1]:8080", "::1:8080"})
    @DisplayName("选中的非法任务代理不会回落到有效来源")
    void rejectsMalformedTaskProxy(String value) {
        ScheduleNetworkRouteResolver resolver = new ScheduleNetworkRouteResolver(settings(true, "host.proxy", 7890));
        assertThatThrownBy(() -> resolver.resolve(value, ScheduledNetworkRoute.proxy("source.proxy", 8080, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid task proxy snapshot");
    }

    private static OutboundProxySettings settings(boolean enabled, String host, int port) {
        return new OutboundProxySettings() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public String getHost() {
                return host;
            }

            @Override
            public int getPort() {
                return port;
            }
        };
    }
}
