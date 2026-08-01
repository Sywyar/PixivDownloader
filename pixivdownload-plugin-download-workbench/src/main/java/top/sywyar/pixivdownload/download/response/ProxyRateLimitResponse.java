package top.sywyar.pixivdownload.download.response;

/** Pixiv 代理限流响应；字段名保持既有 HTTP 契约。 */
public record ProxyRateLimitResponse(String error, int maxRequests, int windowHours) {
}
