package top.sywyar.pixivdownload.plugin.api.http;

import java.net.URI;

/**
 * Resolves a plugin-owned explicit HTTP proxy endpoint at request time.
 */
@FunctionalInterface
public interface OutboundHttpProxyProvider {

    /**
     * Returns an {@code http://host:port} proxy URI, or {@code null} when no valid endpoint is available.
     */
    URI resolveProxyUri();
}
