package top.sywyar.pixivdownload.guiswing;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.util.Objects;

/** Process-lifetime context installed before any Swing component is created. */
public final class SwingHost {
    private static volatile DesktopUiContext context;

    private SwingHost() {}

    public static void install(DesktopUiContext value) {
        context = Objects.requireNonNull(value, "value");
    }

    public static boolean installed() {
        return context != null;
    }

    public static void uninstall() {
        context = null;
    }

    public static DesktopUiContext context() {
        DesktopUiContext value = context;
        if (value == null) throw new IllegalStateException("Swing desktop UI host is not installed");
        return value;
    }

    public static DesktopUiHost host() {
        return context().host();
    }
}
