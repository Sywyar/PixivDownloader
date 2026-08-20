package top.sywyar.pixivdownload.guiswing;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;

import java.util.Objects;

/** 在创建任何 Swing 组件前安装的进程生命周期上下文。 */
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

}
