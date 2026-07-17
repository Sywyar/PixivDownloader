package top.sywyar.pixivdownload.douyin.schedule.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceSupport;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static top.sywyar.pixivdownload.douyin.HostSettingsFixtures.proxySettings;

@DisplayName("抖音计划来源默认路由")
class DouyinScheduledSourceRouteResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("四种插件代理模式映射为来源默认路由且强制代理忽略宿主启用开关")
    void mapsAllPluginProxyModesToSourceDefaultRoutes() throws Exception {
        OutboundProxySettings hostProxy = proxySettings(false, "127.0.0.8", 7897);

        assertThat(resolve(DouyinProxyMode.INHERIT, "", 0, hostProxy))
                .isEqualTo(ScheduledNetworkRoute.inherit());
        assertThat(resolve(DouyinProxyMode.DIRECT, "", 0, hostProxy))
                .isEqualTo(ScheduledNetworkRoute.direct());
        assertThat(resolve(DouyinProxyMode.PROXY, "", 0, hostProxy))
                .isEqualTo(ScheduledNetworkRoute.proxy("127.0.0.8", 7897, null));
        assertThat(resolve(DouyinProxyMode.CUSTOM, "127.0.0.9", 7898, hostProxy))
                .isEqualTo(ScheduledNetworkRoute.proxy("127.0.0.9", 7898, null));
    }

    @Test
    @DisplayName("宿主强制代理与插件自定义代理的非法端点保留为 fail-closed 标记")
    void mapsInvalidProxyEndpointsToFailClosedMarker() {
        DouyinScheduledSourceRouteResolver invalidHostProxy = resolver(
                DouyinProxyMode.PROXY, "", 0,
                proxySettings(false, "https://127.0.0.1", 7890));
        DouyinScheduledSourceRouteResolver invalidCustomProxy = resolver(
                DouyinProxyMode.CUSTOM, "127.0.0.1", 0,
                proxySettings(true, "127.0.0.2", 7891));

        assertInvalidMarker(invalidHostProxy.resolve());
        assertInvalidMarker(invalidCustomProxy.resolve());
    }

    @Test
    @DisplayName("非法代理配置在来源计划中保留标记且不提前访问凭据或网络")
    void invalidProxyConfigurationIsPlannedWithoutNetworkAccess() throws Exception {
        DouyinClient client = mock(DouyinClient.class);
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        DouyinScheduledSourceSupport support = new DouyinScheduledSourceSupport(
                client,
                codec,
                resolver(DouyinProxyMode.CUSTOM, "", 0,
                        proxySettings(true, "127.0.0.1", 7890)));
        var task = support.prepare(new ScheduledTaskDraft(
                1L,
                DouyinSourceTypes.USER,
                DouyinScheduleCodec.DEFINITION_SCHEMA,
                DouyinScheduleCodec.DEFINITION_VERSION,
                "{\"source\":{\"userId\":\"MS4w.LjAB-user\"},\"fetchLimit\":1}",
                ScheduledTaskPresentation.empty()));

        assertInvalidMarker(support.plan(task, DouyinSourceTypes.USER)
                .sourceDefaultRoute());
        verifyNoInteractions(client);
    }

    private ScheduledNetworkRoute resolve(
            DouyinProxyMode mode,
            String customHost,
            int customPort,
            OutboundProxySettings hostProxy) {
        return resolver(mode, customHost, customPort, hostProxy).resolve();
    }

    private DouyinScheduledSourceRouteResolver resolver(
            DouyinProxyMode mode,
            String customHost,
            int customPort,
            OutboundProxySettings hostProxy) {
        return new DouyinScheduledSourceRouteResolver(
                DouyinPluginSettingsService.fixed(
                        tempDir, mode, customHost, customPort),
                hostProxy);
    }

    private static void assertInvalidMarker(ScheduledNetworkRoute route) {
        assertThat(route.mode()).isEqualTo(ScheduledNetworkRoute.Mode.PROXY);
        assertThat(route.proxyHost()).isEqualTo(
                DouyinScheduledSourceRouteResolver.INVALID_PROXY_ROUTE_MARKER);
        assertThat(OutboundProxyOverride.parse(
                route.proxyHost() + ":" + route.proxyPort())).isNull();
    }
}
