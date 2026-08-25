package top.sywyar.pixivdownload.download.response.error;

import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

/** Pixiv 代理限流响应；字段名保持既有 HTTP 契约。 */
public record ProxyRateLimitResponse(String code, String error, int maxRequests, int windowHours)
        implements ApiErrorResponse {

    public ProxyRateLimitResponse(String error, int maxRequests, int windowHours) {
        this("pixiv.proxy.rate-limited", error, maxRequests, windowHours);
    }
}
