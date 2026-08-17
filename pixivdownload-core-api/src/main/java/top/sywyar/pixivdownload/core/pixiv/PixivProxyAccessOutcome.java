package top.sywyar.pixivdownload.core.pixiv;

/**
 * Pixiv 代理访问策略判定结果。
 */
public enum PixivProxyAccessOutcome {
    /**
     * 表示 {@code ALLOWED} 状态。
     */
    ALLOWED,
    /**
     * 表示 {@code OWNER_REQUIRED} 状态。
     */
    OWNER_REQUIRED,
    /**
     * 表示 {@code RATE_LIMITED}。
     */
    RATE_LIMITED
}
