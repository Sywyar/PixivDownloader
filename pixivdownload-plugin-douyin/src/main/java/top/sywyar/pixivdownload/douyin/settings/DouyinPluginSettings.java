package top.sywyar.pixivdownload.douyin.settings;

public record DouyinPluginSettings(
        String downloadDirectory,
        DouyinProxyMode proxyMode,
        String proxyHost,
        String proxyPort,
        boolean includeCover) {

    public DouyinPluginSettings {
        downloadDirectory = downloadDirectory == null ? "" : downloadDirectory.trim();
        proxyMode = proxyMode == null ? DouyinProxyMode.INHERIT : proxyMode;
        proxyHost = proxyHost == null ? "" : proxyHost.trim();
        proxyPort = proxyPort == null ? "" : proxyPort.trim();
    }

    public DouyinPluginSettings(String downloadDirectory, DouyinProxyMode proxyMode) {
        this(downloadDirectory, proxyMode, "", "", false);
    }

    public DouyinPluginSettings(String downloadDirectory,
                                DouyinProxyMode proxyMode,
                                String proxyHost,
                                String proxyPort) {
        this(downloadDirectory, proxyMode, proxyHost, proxyPort, false);
    }

    public static DouyinPluginSettings defaults() {
        return new DouyinPluginSettings("", DouyinProxyMode.INHERIT, "", "", false);
    }
}
