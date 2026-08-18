package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.gui.config.TestDesktopConfigFile;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;

import java.nio.file.Path;
import java.util.List;

/** Installs the real app desktop host for GUI tests migrated from the app module. */
public final class DesktopUiTestHost {
    private DesktopUiTestHost() {
    }

    public static synchronized void ensureInstalled() {
        if (SwingHost.installed()) {
            return;
        }
        install(Path.of("config.yaml"));
    }

    public static synchronized void install(Path configPath) {
        SwingHost.install(new DesktopUiContext(
                1, ".", configPath, false, List.of(), List::of,
                new AppDesktopUiHost(1, new TestDesktopConfigFile(configPath))));
    }
}
