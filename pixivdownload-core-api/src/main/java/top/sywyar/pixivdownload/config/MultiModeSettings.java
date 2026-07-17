package top.sywyar.pixivdownload.config;

/**
 * Read-only multi-mode settings exposed across the plugin boundary.
 */
public interface MultiModeSettings {

    int getLimitPage();

    String getPostDownloadMode();

    boolean isQuotaEnabled();

    int getArchiveExpireMinutes();

    int getMaxProxyRequests();

    int getResetPeriodHours();
}
