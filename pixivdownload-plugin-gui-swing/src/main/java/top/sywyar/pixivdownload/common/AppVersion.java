package top.sywyar.pixivdownload.common;

import top.sywyar.pixivdownload.guiswing.SwingHost;

public final class AppVersion {
    private AppVersion() {}
    public static String getDisplayVersionOrDefault(String fallback) {
        String value = SwingHost.installed() ? SwingHost.host().applicationVersion() : null;
        return value == null || value.isBlank() ? fallback : value;
    }
}
