package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.guiswing.SwingHost;

public final class GuiTokenHolder {
    private GuiTokenHolder() {}
    public static String get() { return SwingHost.host().guiToken(); }
    public static String headerName() { return SwingHost.host().guiTokenHeader(); }
}
