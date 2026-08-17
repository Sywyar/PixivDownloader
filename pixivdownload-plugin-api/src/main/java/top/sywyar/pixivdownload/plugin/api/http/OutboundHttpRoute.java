package top.sywyar.pixivdownload.plugin.api.http;

import java.util.Objects;

/**
 * 由宿主传输实现解释的中性代理路由请求。
 *
 * @param policy 代理路由策略
 * @param explicitProxyProvider 仅显式代理策略使用的端点提供者
 */
public record OutboundHttpRoute(
        OutboundHttpRoutePolicy policy,
        OutboundHttpProxyProvider explicitProxyProvider
) {

    /**
     * 校验路由策略与显式代理提供者的一致性。
     *
     * @param policy 策略
     * @param explicitProxyProvider 显式代理提供器
     */
    public OutboundHttpRoute {
        policy = Objects.requireNonNull(policy, "policy");
        boolean explicitRequired = policy == OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED;
        if (explicitRequired != (explicitProxyProvider != null)) {
            throw new IllegalArgumentException(
                    "explicitProxyProvider is required only for SCOPED_OR_EXPLICIT_REQUIRED");
        }
    }

    /**
     * 创建始终直连的路由。
     *
     * @return 直连路由
     */
    public static OutboundHttpRoute direct() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.DIRECT, null);
    }

    /**
     * 创建优先使用请求作用域代理、否则直连的路由。
     *
     * @return 请求作用域代理或直连路由
     */
    public static OutboundHttpRoute scopedOrDirect() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.SCOPED_OR_DIRECT, null);
    }

    /**
     * 创建继承请求作用域代理和已启用全局代理的路由。
     *
     * @return 继承宿主代理设置的路由
     */
    public static OutboundHttpRoute inherit() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_IF_ENABLED, null);
    }

    /**
     * 创建使用已配置全局代理、否则直连的路由。
     *
     * @return 已配置全局代理路由
     */
    public static OutboundHttpRoute configuredProxy() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.GLOBAL_IF_CONFIGURED, null);
    }

    /**
     * 创建要求请求作用域代理或有效全局代理的路由。
     *
     * @return 要求全局代理可用的路由
     */
    public static OutboundHttpRoute requiredGlobalProxy() {
        return new OutboundHttpRoute(OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_REQUIRED, null);
    }

    /**
     * 创建要求请求作用域代理或指定显式代理的路由。
     *
     * @param provider 显式代理端点提供者
     * @return 要求显式代理可用的路由
     */
    public static OutboundHttpRoute requiredExplicitProxy(OutboundHttpProxyProvider provider) {
        return new OutboundHttpRoute(
                OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED,
                Objects.requireNonNull(provider, "provider"));
    }

    @Override
    public String toString() {
        return "OutboundHttpRoute[policy=" + policy
                + ", explicitProxyProviderPresent=" + (explicitProxyProvider != null) + "]";
    }
}
