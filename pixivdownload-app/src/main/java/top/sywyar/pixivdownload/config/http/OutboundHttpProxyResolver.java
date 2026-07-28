package top.sywyar.pixivdownload.config.http;

import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpProxyProvider;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** App-owned resolution from stable route semantics to one neutral proxy endpoint. */
final class OutboundHttpProxyResolver {

    private final ProxyConfig proxyConfig;

    OutboundHttpProxyResolver(ProxyConfig proxyConfig) {
        this.proxyConfig = Objects.requireNonNull(proxyConfig, "proxyConfig");
    }

    OutboundProxyEndpoint resolve(OutboundHttpRoute route)
            throws OutboundProxyResolutionException {
        Objects.requireNonNull(route, "route");
        return switch (route.policy()) {
            case DIRECT -> null;
            case SCOPED_OR_DIRECT -> scopedOverrideIsActive()
                    ? scopedProxy()
                    : null;
            case SCOPED_OR_GLOBAL_IF_ENABLED -> scopedOverrideIsActive()
                    ? scopedProxy()
                    : enabledGlobalProxy();
            case GLOBAL_IF_CONFIGURED -> configuredGlobalProxy(false);
            case SCOPED_OR_GLOBAL_REQUIRED -> scopedOverrideIsActive()
                    ? scopedProxy()
                    : configuredGlobalProxy(true);
            case SCOPED_OR_EXPLICIT_REQUIRED -> scopedOverrideIsActive()
                    ? scopedProxy()
                    : explicitProxy(route.explicitProxyProvider());
        };
    }

    private OutboundProxyEndpoint enabledGlobalProxy() {
        return proxyConfig.isEnabled() ? validGlobalProxy() : null;
    }

    private OutboundProxyEndpoint configuredGlobalProxy(boolean required)
            throws OutboundProxyResolutionException {
        OutboundProxyEndpoint proxy = validGlobalProxy();
        if (proxy == null && required) {
            throw new OutboundProxyResolutionException(
                    "A valid configured outbound proxy is required");
        }
        return proxy;
    }

    private OutboundProxyEndpoint validGlobalProxy() {
        return toEndpoint(proxyConfig.getHost(), proxyConfig.getPort());
    }

    private static OutboundProxyEndpoint explicitProxy(OutboundHttpProxyProvider provider)
            throws OutboundProxyResolutionException {
        URI uri;
        try {
            uri = provider == null ? null : provider.resolveProxyUri();
        } catch (RuntimeException e) {
            throw new OutboundProxyResolutionException(
                    "Explicit outbound proxy resolution failed", e);
        }
        if (uri == null
                || uri.getScheme() == null
                || !"http".equals(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getPort() < 1
                || uri.getPort() > 65_535
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null
                && !uri.getRawPath().isEmpty()
                && !"/".equals(uri.getRawPath()))) {
            throw new OutboundProxyResolutionException(
                    "A valid explicit outbound proxy is required");
        }
        return new OutboundProxyEndpoint(uri.getHost(), uri.getPort());
    }

    private static boolean scopedOverrideIsActive() {
        return OutboundProxyOverride.isActive();
    }

    private static OutboundProxyEndpoint scopedProxy() {
        return OutboundProxyOverride.current();
    }

    private static OutboundProxyEndpoint toEndpoint(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65_535) {
            return null;
        }
        return new OutboundProxyEndpoint(host, port);
    }

    static final class OutboundProxyResolutionException extends Exception {

        private OutboundProxyResolutionException(String message) {
            super(message);
        }

        private OutboundProxyResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
