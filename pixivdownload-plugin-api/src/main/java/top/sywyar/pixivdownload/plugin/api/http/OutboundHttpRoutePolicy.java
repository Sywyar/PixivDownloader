package top.sywyar.pixivdownload.plugin.api.http;

/** 宿主中立的出站 HTTP 路由语义。 */
public enum OutboundHttpRoutePolicy {
    /**
     * 始终直连，并忽略请求作用域代理覆盖。
     */
    DIRECT,
    /**
     * 存在请求作用域代理覆盖时使用该覆盖，否则直连。
     */
    SCOPED_OR_DIRECT,
    /**
     * 优先使用请求作用域代理覆盖，否则使用已启用的全局代理；全局代理未启用时直连。
     */
    SCOPED_OR_GLOBAL_IF_ENABLED,
    /**
     * 忽略启用标记并使用有效的已配置全局端点；没有有效端点时直连。
     */
    GLOBAL_IF_CONFIGURED,
    /**
     * 优先使用请求作用域代理覆盖，否则要求存在有效的已配置全局端点。
     */
    SCOPED_OR_GLOBAL_REQUIRED,
    /**
     * 优先使用请求作用域代理覆盖，否则要求显式提供者返回有效端点。
     */
    SCOPED_OR_EXPLICIT_REQUIRED
}
