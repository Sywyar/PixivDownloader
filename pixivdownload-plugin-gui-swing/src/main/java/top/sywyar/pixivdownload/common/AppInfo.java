package top.sywyar.pixivdownload.common;

import top.sywyar.pixivdownload.guiswing.SwingHost;

public final class AppInfo {
    public static final String NAME = "PixivDownloader";
    public static final String EXECUTABLE_NAME = "PixivDownload.exe";
    public static final String SHORTCUT_NAME = "PixivDownload.lnk";
    public static final String GITHUB_URL = "https://github.com/Sywyar/PixivDownloader";
    public static final String RELEASES_URL = GITHUB_URL + "/releases";
    private AppInfo() {}
    public static boolean isLaunchedFromExe() { return SwingHost.installed() && SwingHost.host().launchedFromExecutable(); }
}
