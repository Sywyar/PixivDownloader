package top.sywyar.pixivdownload.douyin.settings;

import java.nio.file.Path;

public record DouyinRuntimeSettings(
        Path downloadDirectory,
        DouyinProxyMode proxyMode,
        String proxyHost,
        int proxyPort) {

    public DouyinRuntimeSettings {
        if (downloadDirectory == null) {
            throw new IllegalArgumentException("downloadDirectory must not be null");
        }
        proxyMode = proxyMode == null ? DouyinProxyMode.INHERIT : proxyMode;
        proxyHost = proxyHost == null ? "" : proxyHost.trim();
        proxyPort = proxyPort >= 1 && proxyPort <= 65535 ? proxyPort : 0;
    }

    public DouyinRuntimeSettings(Path downloadDirectory, DouyinProxyMode proxyMode) {
        this(downloadDirectory, proxyMode, "", 0);
    }

    public boolean hasCustomProxyEndpoint() {
        return !proxyHost.isBlank() && proxyPort > 0;
    }
}
