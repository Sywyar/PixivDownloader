package top.sywyar.pixivdownload.plugin.api.http;

/** 单个宿主提供的 HTTP 客户端所采用的重定向策略。 */
public enum OutboundHttpRedirectPolicy {
    /** 按传输实现的安全规则跟随重定向。 */
    FOLLOW,
    /** 禁止跟随重定向。 */
    NEVER
}
