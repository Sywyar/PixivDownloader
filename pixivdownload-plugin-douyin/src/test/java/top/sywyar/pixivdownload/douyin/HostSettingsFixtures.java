package top.sywyar.pixivdownload.douyin;

import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.config.OutboundProxySettings;

/** Douyin tests use shared read-only contracts instead of host implementation classes. */
public final class HostSettingsFixtures {

    private HostSettingsFixtures() {
    }

    public static DownloadSettings downloadSettings(String rootFolder, int maxConcurrent) {
        return new DownloadSettings() {
            @Override
            public String getRootFolder() {
                return rootFolder;
            }

            @Override
            public boolean isUserFlatFolder() {
                return false;
            }

            @Override
            public int getMaxConcurrent() {
                return maxConcurrent;
            }

            @Override
            public int getNovelMaxConcurrent() {
                return 1;
            }

            @Override
            public int getNovelTranslateMaxConcurrent() {
                return 1;
            }
        };
    }

    public static MultiModeSettings multiModeSettings(int limitPage) {
        return new MultiModeSettings() {
            @Override
            public int getLimitPage() {
                return limitPage;
            }

            @Override
            public String getPostDownloadMode() {
                return "pack-and-delete";
            }

            @Override
            public boolean isQuotaEnabled() {
                return true;
            }

            @Override
            public int getArchiveExpireMinutes() {
                return 60;
            }

            @Override
            public int getMaxProxyRequests() {
                return 200;
            }

            @Override
            public int getResetPeriodHours() {
                return 24;
            }
        };
    }

    public static OutboundProxySettings proxySettings(boolean enabled, String host, int port) {
        return new OutboundProxySettings() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public String getHost() {
                return host;
            }

            @Override
            public int getPort() {
                return port;
            }
        };
    }
}
