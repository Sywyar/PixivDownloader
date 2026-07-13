package top.sywyar.pixivdownload.download.schedule.network;

import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;

import java.util.Objects;

/** Pixiv HTTP 客户端把中性 resolved route 映射到既有线程级代理覆盖的唯一边界。 */
public final class PixivScheduledRouteScope {

    private PixivScheduledRouteScope() {
    }

    public static <T> T call(ScheduledNetworkRoute route, ThrowingSupplier<T> operation)
            throws Exception {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(operation, "operation");
        if (!route.isResolved()) {
            throw new IllegalArgumentException("Pixiv schedule route must be resolved");
        }
        switch (route.mode()) {
            case DIRECT -> OutboundProxyOverride.setDirect();
            case PROXY -> OutboundProxyOverride.set(route.proxyHost() + ":" + route.proxyPort());
            case INHERIT -> throw new IllegalArgumentException("unresolved Pixiv schedule route");
        }
        try {
            return operation.get();
        } finally {
            OutboundProxyOverride.clear();
        }
    }

    public static void run(ScheduledNetworkRoute route, ThrowingRunnable operation)
            throws Exception {
        call(route, () -> {
            operation.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
