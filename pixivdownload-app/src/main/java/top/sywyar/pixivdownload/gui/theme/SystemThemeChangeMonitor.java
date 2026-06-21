package top.sywyar.pixivdownload.gui.theme;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.awt.Toolkit;
import java.beans.PropertyChangeListener;
import java.util.Locale;

/**
 * 监听操作系统桌面属性变化，用于在"跟随系统"主题模式下尽快触发一次真实主题重探测。
 * <p>JDK 对系统浅 / 深色没有统一事件，Windows / macOS / GTK 暴露的属性名也不完全一致；
 * 因此这里监听一组相关桌面属性，收到事件后只发出"可能变化"信号，最终仍由
 * {@link SystemThemeDetector} 读取当前系统状态决定是否重涂。
 */
@Slf4j
final class SystemThemeChangeMonitor {

    private static final String[] WINDOWS_DESKTOP_PROPERTIES = {
            "win.dark.theme",
            "win.highContrast.on",
            "win.xpstyle.themeActive",
            "win.xpstyle.colorName",
            "win.xpstyle.sizeName",
            "win.3d.backgroundColor",
            "win.desktop.backgroundColor",
            "win.frame.activeCaptionColor",
            "win.menu.backgroundColor"
    };

    private static final String[] MAC_DESKTOP_PROPERTIES = {
            "apple.awt.application.appearance",
            "apple.awt.brushMetalLook"
    };

    private static final String[] GTK_DESKTOP_PROPERTIES = {
            "gnome.Gtk/Settings/gtk-application-prefer-dark-theme",
            "gnome.Gtk/Settings/gtk-theme-name"
    };

    private final Runnable onPotentialThemeChange;
    private final String[] propertyNames;
    private final WindowsThemeRegistryMonitor windowsThemeRegistryMonitor;
    private Toolkit toolkit;
    private PropertyChangeListener listener;
    private boolean started;

    SystemThemeChangeMonitor(Runnable onPotentialThemeChange) {
        this.onPotentialThemeChange = onPotentialThemeChange;
        this.propertyNames = resolvePropertyNames();
        this.windowsThemeRegistryMonitor = new WindowsThemeRegistryMonitor(onPotentialThemeChange);
    }

    synchronized void start() {
        if (started) {
            return;
        }
        try {
            toolkit = Toolkit.getDefaultToolkit();
            listener = event -> {
                try {
                    onPotentialThemeChange.run();
                } catch (RuntimeException e) {
                    log.debug(logMessage("gui.theme.log.desktop-listener.callback-failed", e.getMessage()));
                }
            };
            for (String propertyName : propertyNames) {
                toolkit.addPropertyChangeListener(propertyName, listener);
            }
            started = true;
            log.debug(logMessage("gui.theme.log.desktop-listener.started", String.join(", ", propertyNames)));
        } catch (RuntimeException e) {
            log.debug(logMessage("gui.theme.log.desktop-listener.start-failed", e.getMessage()));
            stop();
        }
        windowsThemeRegistryMonitor.start();
    }

    synchronized void stop() {
        windowsThemeRegistryMonitor.stop();
        if (!started && toolkit == null) {
            return;
        }
        if (toolkit != null && listener != null) {
            for (String propertyName : propertyNames) {
                try {
                    toolkit.removePropertyChangeListener(propertyName, listener);
                } catch (RuntimeException e) {
                    log.debug(logMessage("gui.theme.log.desktop-listener.stop-failed", e.getMessage()));
                }
            }
        }
        toolkit = null;
        listener = null;
        started = false;
    }

    private static String[] resolvePropertyNames() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return WINDOWS_DESKTOP_PROPERTIES;
        }
        if (os.contains("mac")) {
            return MAC_DESKTOP_PROPERTIES;
        }
        return GTK_DESKTOP_PROPERTIES;
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
