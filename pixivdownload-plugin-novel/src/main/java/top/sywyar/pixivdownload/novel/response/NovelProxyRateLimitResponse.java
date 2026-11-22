package top.sywyar.pixivdownload.novel.response;

import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

/**
 * 小说 Pixiv 代理端点的限流响应。
 */
public record NovelProxyRateLimitResponse(
        @Schema(allowableValues = "pixiv.proxy.rate-limited") String code,
        String error,
        int maxRequests,
        int windowHours
) implements ApiErrorResponse {

    public NovelProxyRateLimitResponse(String error, int maxRequests, int windowHours) {
        this("pixiv.proxy.rate-limited", error, maxRequests, windowHours);
    }
}
