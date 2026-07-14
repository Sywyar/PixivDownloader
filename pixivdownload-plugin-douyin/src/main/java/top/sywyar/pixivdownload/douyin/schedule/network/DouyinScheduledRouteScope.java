package top.sywyar.pixivdownload.douyin.schedule.network;

import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;

import java.util.Objects;

/** 把调度宿主解析后的中性路由应用到抖音 API、重定向与媒体客户端。 */
public final class DouyinScheduledRouteScope {

    private DouyinScheduledRouteScope() {
    }

    public static <T> T call(ScheduledNetworkRoute route, ThrowingSupplier<T> operation)
            throws Exception {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(operation, "operation");
        if (!route.isResolved()) {
            throw new IllegalArgumentException("Douyin schedule route must be resolved");
        }
        switch (route.mode()) {
            case DIRECT -> OutboundProxyOverride.setDirect();
            case PROXY -> OutboundProxyOverride.set(route.proxyHost() + ":" + route.proxyPort());
            case INHERIT -> throw new IllegalArgumentException("unresolved Douyin schedule route");
        }
        try {
            return operation.get();
        } finally {
            OutboundProxyOverride.clear();
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
