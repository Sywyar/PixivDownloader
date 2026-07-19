package top.sywyar.pixivdownload.core.pixiv;

/**
 * 与 Servlet 和 HTTP 响应类型无关的 Pixiv 代理访问判定。
 *
 * @param outcome      判定结果
 * @param errorMessage 已本地化的拒绝文案；放行时为 {@code null}
 * @param maxRequests  限流窗口内允许的最大请求数；非限流结果为 {@code 0}
 * @param windowHours  限流窗口小时数；非限流结果为 {@code 0}
 */
public record PixivProxyAccessDecision(
        PixivProxyAccessOutcome outcome,
        String errorMessage,
        int maxRequests,
        int windowHours
) {
}
