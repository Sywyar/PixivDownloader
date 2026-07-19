package top.sywyar.pixivdownload.novel.response;

/**
 * 小说 Pixiv 代理端点的限流响应。
 */
public record NovelProxyRateLimitResponse(
        String error,
        int maxRequests,
        int windowHours
) {
}
