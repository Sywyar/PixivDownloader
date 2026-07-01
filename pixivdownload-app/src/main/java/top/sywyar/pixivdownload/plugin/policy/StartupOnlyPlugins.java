package top.sywyar.pixivdownload.plugin.policy;

import java.util.Set;

public final class StartupOnlyPlugins {

    public static final String GUI_THEME = "gui-theme";

    private static final Set<String> STARTUP_ONLY = Set.of(GUI_THEME);

    private StartupOnlyPlugins() {
    }

    public static boolean isStartupOnly(String pluginId) {
        return STARTUP_ONLY.contains(pluginId);
    }
}
