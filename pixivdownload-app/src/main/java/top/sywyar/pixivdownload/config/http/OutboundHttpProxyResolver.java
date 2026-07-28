package top.sywyar.pixivdownload.config.http;

import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpProxyProvider;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * App-owned conversion from stable route semantics to one Apache proxy target.
 */
final class OutboundHttpProxyResolver {

    private final ProxyConfig proxyConfig;

    OutboundHttpProxyResolver(ProxyConfig proxyConfig) {
        this.proxyConfig = Objects.requireNonNull(proxyConfig, "proxyConfig");
    }

    HttpHost resolve(OutboundHttpRoute route) throws HttpException {
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

    private HttpHost enabledGlobalProxy() {
        return proxyConfig.isEnabled() ? validGlobalProxy() : null;
    }

    private HttpHost configuredGlobalProxy(boolean required) throws HttpException {
        HttpHost proxy = validGlobalProxy();
        if (proxy == null && required) {
            throw new HttpException("A valid configured outbound proxy is required");
        }
        return proxy;
    }

    private HttpHost validGlobalProxy() {
        return toHttpHost(proxyConfig.getHost(), proxyConfig.getPort());
    }

    private static HttpHost explicitProxy(OutboundHttpProxyProvider provider) throws HttpException {
        URI uri;
        try {
            uri = provider == null ? null : provider.resolveProxyUri();
        } catch (RuntimeException e) {
            throw new HttpException("Explicit outbound proxy resolution failed", e);
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
            throw new HttpException("A valid explicit outbound proxy is required");
        }
        return new HttpHost("http", uri.getHost(), uri.getPort());
    }

    private static boolean scopedOverrideIsActive() {
        return OutboundProxyOverride.isActive();
    }

    private static HttpHost scopedProxy() {
        OutboundProxyEndpoint endpoint = OutboundProxyOverride.current();
        return endpoint == null
                ? null
                : new HttpHost("http", endpoint.hostName(), endpoint.port());
    }

    private static HttpHost toHttpHost(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65_535) {
            return null;
        }
        return new HttpHost("http", host.trim(), port);
    }
}
