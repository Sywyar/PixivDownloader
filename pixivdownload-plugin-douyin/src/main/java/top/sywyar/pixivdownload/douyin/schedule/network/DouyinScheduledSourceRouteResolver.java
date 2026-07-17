package top.sywyar.pixivdownload.douyin.schedule.network;

import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettings;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;

import java.util.Objects;

/** 把抖音插件代理模式映射为计划来源的中性默认路由。 */
public final class DouyinScheduledSourceRouteResolver {

    static final String INVALID_PROXY_ROUTE_MARKER =
            "<invalid-douyin-source-proxy>";
    private static final int INVALID_PROXY_ROUTE_PORT = 1;

    private final DouyinPluginSettingsService settingsService;
    private final OutboundProxySettings hostProxySettings;

    public DouyinScheduledSourceRouteResolver(
            DouyinPluginSettingsService settingsService,
            OutboundProxySettings hostProxySettings) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.hostProxySettings = Objects.requireNonNull(
                hostProxySettings, "hostProxySettings");
    }

    public ScheduledNetworkRoute resolve() {
        try {
            DouyinPluginSettings settings = settingsService.load();
            return switch (settings.proxyMode()) {
                case INHERIT -> ScheduledNetworkRoute.inherit();
                case DIRECT -> ScheduledNetworkRoute.direct();
                case PROXY -> proxyRoute(
                        hostProxySettings.getHost(), hostProxySettings.getPort());
                case CUSTOM -> proxyRoute(
                        settings.proxyHost(), parsePort(settings.proxyPort()));
            };
        } catch (RuntimeException failure) {
            return invalidProxyRoute();
        }
    }

    private static ScheduledNetworkRoute proxyRoute(String host, int port) {
        if (host == null || host.isBlank()) {
            return invalidProxyRoute();
        }
        OutboundProxyEndpoint parsed = OutboundProxyOverride.parse(host.trim() + ":" + port);
        if (parsed == null) {
            return invalidProxyRoute();
        }
        return ScheduledNetworkRoute.proxy(
                parsed.hostName(), parsed.port(), null);
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    /**
     * 保留一个无法通过宿主代理端点校验的来源路由。宿主先解析合法任务代理，只有实际选中来源层时才会拒绝该标记，
     * 从而同时满足任务覆盖优先与无效来源配置 fail-closed。
     */
    private static ScheduledNetworkRoute invalidProxyRoute() {
        return ScheduledNetworkRoute.proxy(
                INVALID_PROXY_ROUTE_MARKER, INVALID_PROXY_ROUTE_PORT, null);
    }
}
