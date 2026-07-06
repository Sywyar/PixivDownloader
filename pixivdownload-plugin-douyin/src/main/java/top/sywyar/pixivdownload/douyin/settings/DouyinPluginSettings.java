package top.sywyar.pixivdownload.douyin.settings;

public record DouyinPluginSettings(
        String downloadDirectory,
        DouyinProxyMode proxyMode,
        String proxyHost,
        String proxyPort) {

    public DouyinPluginSettings {
        downloadDirectory = downloadDirectory == null ? "" : downloadDirectory.trim();
        proxyMode = proxyMode == null ? DouyinProxyMode.INHERIT : proxyMode;
        proxyHost = proxyHost == null ? "" : proxyHost.trim();
        proxyPort = proxyPort == null ? "" : proxyPort.trim();
    }

    public DouyinPluginSettings(String downloadDirectory, DouyinProxyMode proxyMode) {
        this(downloadDirectory, proxyMode, "", "");
    }

    public static DouyinPluginSettings defaults() {
        return new DouyinPluginSettings("", DouyinProxyMode.INHERIT, "", "");
    }
}
