package top.sywyar.pixivdownload.plugin.api.http;

/**
 * Host-neutral routing semantics for outbound HTTP.
 */
public enum OutboundHttpRoutePolicy {
    /**
     * Always connect directly and ignore any request-scoped override.
     */
    DIRECT,
    /**
     * Honor a request-scoped override when present, otherwise connect directly.
     */
    SCOPED_OR_DIRECT,
    /**
     * Honor a request-scoped override, otherwise use the enabled global proxy or connect directly.
     */
    SCOPED_OR_GLOBAL_IF_ENABLED,
    /**
     * Use a valid configured global endpoint regardless of its enabled flag, otherwise connect directly.
     */
    GLOBAL_IF_CONFIGURED,
    /**
     * Honor a request-scoped override, otherwise require a valid configured global endpoint.
     */
    SCOPED_OR_GLOBAL_REQUIRED,
    /**
     * Honor a request-scoped override, otherwise require the explicit provider to return a valid endpoint.
     */
    SCOPED_OR_EXPLICIT_REQUIRED
}
