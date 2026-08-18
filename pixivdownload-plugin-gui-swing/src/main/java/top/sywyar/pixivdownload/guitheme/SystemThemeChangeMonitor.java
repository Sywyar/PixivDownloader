package top.sywyar.pixivdownload.guitheme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Toolkit;
import java.beans.PropertyChangeListener;
import java.util.Locale;

final class SystemThemeChangeMonitor {

    private static final Logger log = LoggerFactory.getLogger(SystemThemeChangeMonitor.class);

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
                    log.debug("OS theme listener callback failed: {}", e.toString());
                }
            };
            for (String propertyName : propertyNames) {
                toolkit.addPropertyChangeListener(propertyName, listener);
            }
            started = true;
            log.debug("Listening for OS theme desktop properties: {}", String.join(", ", propertyNames));
        } catch (RuntimeException e) {
            log.debug("Failed to start OS theme listener; using polling fallback: {}", e.toString());
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
                    log.debug("Failed to stop OS theme listener: {}", e.toString());
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
}
