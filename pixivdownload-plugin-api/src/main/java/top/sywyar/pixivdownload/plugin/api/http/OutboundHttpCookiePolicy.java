package top.sywyar.pixivdownload.plugin.api.http;

/** 单个宿主提供的 HTTP 客户端所采用的 Cookie 存储策略。 */
public enum OutboundHttpCookiePolicy {
    /** 启用客户端私有 Cookie 存储。 */
    ENABLED,
    /** 禁用 Cookie 存储。 */
    DISABLED
}
