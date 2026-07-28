package top.sywyar.pixivdownload.plugin.api.http;

import java.util.Objects;

/**
 * Neutral proxy-routing request interpreted by the host transport implementation.
 */
public record OutboundHttpRoute(
        OutboundHttpRoutePolicy policy,
        OutboundHttpProxyProvider explicitProxyProvider
) {

    public OutboundHttpRoute {
        policy = Objects.requireNonNull(policy, "policy");
        boolean explicitRequired = policy == OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED;
        if (explicitRequired != (explicitProxyProvider != null)) {
            throw new IllegalArgumentException(
                    "explicitProxyProvider is required only for SCOPED_OR_EXPLICIT_REQUIRED");
        }
    }

    public static OutboundHttpRoute direct() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.DIRECT, null);
    }

    public static OutboundHttpRoute scopedOrDirect() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.SCOPED_OR_DIRECT, null);
    }

    public static OutboundHttpRoute inherit() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_IF_ENABLED, null);
    }

    public static OutboundHttpRoute configuredProxy() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.GLOBAL_IF_CONFIGURED, null);
    }

    public static OutboundHttpRoute requiredGlobalProxy() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_REQUIRED, null);
    }

    public static OutboundHttpRoute requiredExplicitProxy(OutboundHttpProxyProvider provider) {
        return new OutboundHttpRoute(
                OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED,
                Objects.requireNonNull(provider, "provider"));
    }
}
