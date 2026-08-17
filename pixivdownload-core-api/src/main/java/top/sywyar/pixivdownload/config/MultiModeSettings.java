package top.sywyar.pixivdownload.config;

/**
 * 跨插件边界公开的只读多用户模式设置。
 */
public interface MultiModeSettings {

    /**
     * 返回限制值页码。
     *
     * @return 方法返回的数值
     */
    int getLimitPage();

    /**
     * 返回完成后操作下载模式。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    String getPostDownloadMode();

    /**
     * 判断配额启用状态是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isQuotaEnabled();

    /**
     * 返回归档过期时间分钟数。
     *
     * @return 方法返回的数值
     */
    int getArchiveExpireMinutes();

    /**
     * 返回对应值。
     *
     * @return 方法返回的数值
     */
    int getMaxProxyRequests();

    /**
     * 返回对应值。
     *
     * @return 方法返回的数值
     */
    int getResetPeriodHours();
}
