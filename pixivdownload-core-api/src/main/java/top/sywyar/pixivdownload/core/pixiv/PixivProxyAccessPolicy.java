package top.sywyar.pixivdownload.core.pixiv;

/**
 * Pixiv 代理访问配额与搜索补页限制的请求无关策略端口。
 */
public interface PixivProxyAccessPolicy {

    /**
     * 检查并在需要时预留一次代理请求配额。
     *
     * @param existingOwnerUuid 请求已携带的 owner UUID；不存在时为 {@code null}
     * @param adminAuthenticated 当前请求是否已经通过管理员认证
     */
    PixivProxyAccessDecision evaluate(String existingOwnerUuid, boolean adminAuthenticated);

    /**
     * 返回搜索自动补页上限；{@code 0} 表示不限制。
     */
    int resolveSearchFillLimitPage(boolean adminAuthenticated);
}
