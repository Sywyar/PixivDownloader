package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;

import java.util.Objects;
import java.util.regex.Pattern;

/** 把任务级代理、来源默认路由与宿主全局代理解析成单个不可变、已解析 route。 */
public final class ScheduleNetworkRouteResolver {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private final OutboundProxySettings settings;

    public ScheduleNetworkRouteResolver(OutboundProxySettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public ScheduledNetworkRoute resolve(String taskProxySnapshot) {
        return resolve(taskProxySnapshot, ScheduledNetworkRoute.inherit());
    }

    public ScheduledNetworkRoute resolve(
            String taskProxySnapshot,
            ScheduledNetworkRoute sourceDefaultRoute) {
        if (taskProxySnapshot != null && !taskProxySnapshot.isBlank()) {
            ProxyAddress address = parse(taskProxySnapshot);
            if (address == null) {
                throw new IllegalArgumentException("invalid task proxy snapshot");
            }
            return ScheduledNetworkRoute.proxy(address.host(), address.port(), null);
        }
        ScheduledNetworkRoute sourceRoute = sourceDefaultRoute == null
                ? ScheduledNetworkRoute.inherit()
                : sourceDefaultRoute;
        if (sourceRoute.mode() == ScheduledNetworkRoute.Mode.PROXY
                && parse(sourceRoute.proxyHost() + ":" + sourceRoute.proxyPort()) == null) {
            throw new IllegalArgumentException("invalid source default proxy route");
        }
        return sourceRoute.resolveAgainst(hostDefaultRoute());
    }

    private ScheduledNetworkRoute hostDefaultRoute() {
        String host = settings.getHost();
        int port = settings.getPort();
        if (settings.isEnabled() && host != null && !host.isBlank()
                && HOST_PATTERN.matcher(host.trim()).matches()
                && port >= 1 && port <= 65_535) {
            return ScheduledNetworkRoute.proxy(host.trim(), port, null);
        }
        return ScheduledNetworkRoute.direct();
    }

    private static ProxyAddress parse(String value) {
        String trimmed = value.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            return null;
        }
        String host = trimmed.substring(0, colon);
        if (!HOST_PATTERN.matcher(host).matches()) {
            return null;
        }
        try {
            int port = Integer.parseInt(trimmed.substring(colon + 1));
            return port >= 1 && port <= 65_535 ? new ProxyAddress(host, port) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record ProxyAddress(String host, int port) {
    }
}
