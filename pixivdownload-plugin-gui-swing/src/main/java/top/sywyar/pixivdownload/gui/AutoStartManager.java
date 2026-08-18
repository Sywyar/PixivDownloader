package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.guiswing.SwingHost;

import java.io.IOException;

public final class AutoStartManager {
    private AutoStartManager() {}
    public static boolean isSupported() { return SwingHost.host().autoStartSupported(); }
    public static boolean isEnabled() { return SwingHost.host().autoStartEnabled(); }
    public static void setEnabled(boolean enabled) throws IOException, InterruptedException {
        SwingHost.host().setAutoStartEnabled(enabled);
    }
}
