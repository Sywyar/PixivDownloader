package top.sywyar.pixivdownload.core.pixiv;

/**
 * Pixiv 代理访问策略判定结果。
 */
public enum PixivProxyAccessOutcome {
    ALLOWED,
    OWNER_REQUIRED,
    RATE_LIMITED
}
