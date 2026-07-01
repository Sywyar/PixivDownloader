package top.sywyar.pixivdownload.guitheme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMoonlightIJTheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeChangeListener;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeListenerSession;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class FlatLafSetup {

    private static final Logger log = LoggerFactory.getLogger(FlatLafSetup.class);

    private static final String[] FONT_PRIORITY = {
            "Microsoft YaHei UI",
            "PingFang SC",
            "Noto Sans CJK SC",
            "Source Han Sans SC",
            "SimSun",
            "Dialog"
    };
    private static final int SYSTEM_POLL_INTERVAL_MS = 30_000;

    private static volatile ThemePreference currentPreference = ThemePreference.SYSTEM;
    private static volatile boolean currentDark;
    private static Timer systemFallbackWatcher;
    private static SystemThemeChangeMonitor systemThemeChangeMonitor;
    private static final AtomicBoolean systemProbeInFlight = new AtomicBoolean(false);
    private static final List<GuiThemeChangeListener> changeListeners = new ArrayList<>();

    private FlatLafSetup() {
    }

    static void apply(ThemePreference preference) {
        ThemePreference next = preference == null ? ThemePreference.SYSTEM : preference;
        currentPreference = next;
        boolean dark = resolveDarkFor(next);
        installLaf(dark);
        currentDark = dark;
        applyChineseFont();
        updateSystemWatcher();
        notifyChange(appearanceFor(dark));
    }

    static GuiThemeListenerSession openListener(ThemePreference preference, GuiThemeChangeListener listener) {
        if (listener == null) {
            return GuiThemeListenerSession.none();
        }
        synchronized (changeListeners) {
            changeListeners.add(listener);
        }
        listener.appearanceChanged(appearanceFor(currentDark));
        return new ListenerSession(listener);
    }

    private static boolean resolveDarkFor(ThemePreference preference) {
        return switch (preference) {
            case DARK, MOONLIGHT -> true;
            case LIGHT -> false;
            case SYSTEM -> SystemThemeDetector.isSystemDark();
        };
    }

    private static void installLaf(boolean dark) throws IllegalStateException {
        try {
            if (currentPreference.isNamedTheme()) {
                FlatLaf.setGlobalExtraDefaults(null);
                if (currentPreference == ThemePreference.MOONLIGHT) {
                    FlatMoonlightIJTheme.setup();
                }
                return;
            }
            FlatLaf.setGlobalExtraDefaults(dark ? buildFrontendDarkExtras() : null);
            if (dark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
        } catch (RuntimeException e) {
            log.warn("FlatLaf initialization failed; leaving fallback LAF in place: {}", e.toString());
            throw e;
        }
    }

    private static Map<String, String> buildFrontendDarkExtras() {
        final String bg = "#101216";
        final String surface = "#171a21";
        final String text = "#f4f7fb";
        final String muted = "#8d98aa";
        final String brand = "#4bb3ff";
        final String line = "#2a303b";

        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("Panel.background", bg);
        extras.put("Panel.foreground", text);
        extras.put("TabbedPane.background", bg);
        extras.put("TabbedPane.contentAreaColor", bg);
        extras.put("TabbedPane.foreground", text);
        extras.put("Viewport.background", bg);
        extras.put("ScrollPane.background", bg);
        extras.put("SplitPane.background", bg);
        extras.put("RootPane.background", bg);

        extras.put("Label.foreground", text);
        extras.put("Label.disabledForeground", muted);
        extras.put("TitledBorder.titleColor", text);
        extras.put("ToolTip.background", surface);
        extras.put("ToolTip.foreground", text);
        extras.put("Separator.foreground", line);
        extras.put("Component.borderColor", line);

        extras.put("MenuBar.background", surface);
        extras.put("MenuBar.foreground", text);
        extras.put("PopupMenu.background", surface);
        extras.put("PopupMenu.foreground", text);
        extras.put("PopupMenu.borderColor", line);
        extras.put("PopupMenuSeparator.background", surface);
        extras.put("Menu.background", surface);
        extras.put("Menu.foreground", text);
        extras.put("Menu.disabledForeground", muted);
        extras.put("Menu.selectionBackground", brand);
        extras.put("Menu.selectionForeground", "#ffffff");
        extras.put("MenuItem.background", surface);
        extras.put("MenuItem.foreground", text);
        extras.put("MenuItem.disabledForeground", muted);
        extras.put("MenuItem.acceleratorForeground", muted);
        extras.put("MenuItem.selectionBackground", brand);
        extras.put("MenuItem.selectionForeground", "#ffffff");
        extras.put("CheckBoxMenuItem.background", surface);
        extras.put("CheckBoxMenuItem.foreground", text);
        extras.put("CheckBoxMenuItem.selectionBackground", brand);
        extras.put("CheckBoxMenuItem.selectionForeground", "#ffffff");
        extras.put("RadioButtonMenuItem.background", surface);
        extras.put("RadioButtonMenuItem.foreground", text);
        extras.put("RadioButtonMenuItem.selectionBackground", brand);
        extras.put("RadioButtonMenuItem.selectionForeground", "#ffffff");

        extras.put("ComboBox.background", surface);
        extras.put("ComboBox.foreground", text);
        extras.put("ComboBox.buttonBackground", surface);
        extras.put("ComboBox.selectionBackground", brand);
        extras.put("ComboBox.selectionForeground", "#ffffff");
        extras.put("List.background", surface);
        extras.put("List.foreground", text);
        extras.put("List.selectionBackground", brand);
        extras.put("List.selectionForeground", "#ffffff");
        return extras;
    }

    private static void applyDarkInternal(boolean dark) {
        if (dark == currentDark) {
            notifyChange(appearanceFor(dark));
            return;
        }
        installLaf(dark);
        applyChineseFont();
        currentDark = dark;
        try {
            FlatLaf.updateUI();
        } catch (RuntimeException e) {
            log.warn("Failed to refresh UI after theme change: {}", e.toString());
        }
        notifyChange(appearanceFor(dark));
    }

    private static void notifyChange(GuiThemeAppearance appearance) {
        List<GuiThemeChangeListener> snapshot;
        synchronized (changeListeners) {
            snapshot = new ArrayList<>(changeListeners);
        }
        for (GuiThemeChangeListener listener : snapshot) {
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    listener.appearanceChanged(appearance);
                } else {
                    SwingUtilities.invokeLater(() -> listener.appearanceChanged(appearance));
                }
            } catch (RuntimeException e) {
                log.debug("GUI theme listener failed: {}", e.toString());
            }
        }
    }

    private static void updateSystemWatcher() {
        if (currentPreference == ThemePreference.SYSTEM) {
            startSystemWatcher();
        } else {
            stopSystemWatcher();
        }
    }

    private static void startSystemWatcher() {
        if (systemThemeChangeMonitor == null) {
            systemThemeChangeMonitor = new SystemThemeChangeMonitor(FlatLafSetup::requestSystemThemeRefresh);
        }
        systemThemeChangeMonitor.start();

        if (systemFallbackWatcher == null) {
            systemFallbackWatcher = new Timer(SYSTEM_POLL_INTERVAL_MS, e -> requestSystemThemeRefresh());
            systemFallbackWatcher.setInitialDelay(SYSTEM_POLL_INTERVAL_MS);
            systemFallbackWatcher.start();
        } else if (!systemFallbackWatcher.isRunning()) {
            systemFallbackWatcher.start();
        }
    }

    private static void stopSystemWatcher() {
        if (systemThemeChangeMonitor != null) {
            systemThemeChangeMonitor.stop();
        }
        if (systemFallbackWatcher != null && systemFallbackWatcher.isRunning()) {
            systemFallbackWatcher.stop();
        }
    }

    private static void requestSystemThemeRefresh() {
        if (currentPreference != ThemePreference.SYSTEM) {
            return;
        }
        if (!systemProbeInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                boolean dark = SystemThemeDetector.isSystemDark();
                SwingUtilities.invokeLater(() -> {
                    if (currentPreference == ThemePreference.SYSTEM && dark != currentDark) {
                        applyDarkInternal(dark);
                    }
                });
            } finally {
                systemProbeInFlight.set(false);
            }
        }, "gui-theme-watcher");
        worker.setDaemon(true);
        worker.start();
    }

    private static void applyChineseFont() {
        String[] available = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        var availableSet = new HashSet<>(Arrays.asList(available));

        for (String name : FONT_PRIORITY) {
            if (availableSet.contains(name)) {
                UIManager.put("defaultFont", new Font(name, Font.PLAIN, 13));
                log.debug("GUI font set to: {}", name);
                return;
            }
        }
        log.warn("No preset CJK font was found; using the system default font");
    }

    private static GuiThemeAppearance appearanceFor(boolean dark) {
        return dark ? GuiThemeAppearance.DARK : GuiThemeAppearance.LIGHT;
    }

    private record ListenerSession(GuiThemeChangeListener listener) implements GuiThemeListenerSession {
        @Override
        public void close() {
            synchronized (changeListeners) {
                changeListeners.remove(listener);
            }
        }
    }
}
