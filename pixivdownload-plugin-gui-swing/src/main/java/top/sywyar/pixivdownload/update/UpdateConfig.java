package top.sywyar.pixivdownload.update;

import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.guiswing.SwingHost;

public final class UpdateConfig {
    public static final String DEFAULT_MANIFEST_URL = AppInfo.RELEASES_URL + "/latest/download/update.json";
    public static final String DEFAULT_NIGHTLY_MANIFEST_URL = AppInfo.RELEASES_URL + "/download/nightly/update.json";
    private UpdateConfig() {}
    public static boolean isCurrentVersionNightly() { return SwingHost.host().currentVersionNightly(); }
}
