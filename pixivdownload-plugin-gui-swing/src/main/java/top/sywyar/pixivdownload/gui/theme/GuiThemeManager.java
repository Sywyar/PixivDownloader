package top.sywyar.pixivdownload.gui.theme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeChangeListener;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeListenerSession;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Window;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Swing 的主题应用、贡献监听与系统回退；主题持久化由设置页负责。
 */
public final class GuiThemeManager {

    public static final String DEFAULT_THEME_ID = "system";

    private static final Logger log = LoggerFactory.getLogger(GuiThemeManager.class);
    private static final Object LOCK = new Object();

    private static String configuredThemeId = DEFAULT_THEME_ID;
    private static String activeThemeId = DEFAULT_THEME_ID;
    private static boolean currentDark;
    private static Map<String, GuiThemeContribution> themes;
    private static ActiveThemeListener activeContributionListener;
    private static final List<Runnable> changeListeners = new ArrayList<>();

    private GuiThemeManager() {
    }

    public static String readPersistedThemeId(DesktopUiHost.ConfigFile configFile) {
        if (configFile == null) return DEFAULT_THEME_ID;
        try {
            return normalizeThemeId(configFile.read("app.theme"));
        } catch (IOException e) {
            log.warn("Failed to read app.theme: {}", e.toString());
        }
        return DEFAULT_THEME_ID;
    }

    public static void applyBeforeFirstWindow(
            String configuredThemeId,
            Collection<DesktopUiPluginSnapshot> activePlugins
    ) {
        Runnable task = () -> {
            synchronized (LOCK) {
                GuiThemeManager.configuredThemeId = normalizeThemeId(configuredThemeId);
                themes = collectThemes(activePlugins);
            }
            applyConfiguredTheme(false);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while applying GUI theme before first window; using fallback: {}",
                    failure.toString(), failure);
            SwingUtilities.invokeLater(() -> applySystemFallback("theme manager dispatch interrupted", failure));
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throwIfJvmFatal(cause);
            log.error("Failed to apply GUI theme before first window; using fallback: {}",
                    cause.toString(), cause);
            SwingUtilities.invokeLater(() -> applySystemFallback("theme manager dispatch failure", cause));
        }
    }

    public static List<ThemeChoice> choices(Locale locale, String unavailableLabel, String fallbackLabel) {
        List<ThemeChoice> result = new ArrayList<>();
        String configured;
        Map<String, GuiThemeContribution> snapshot;
        synchronized (LOCK) {
            configured = configuredThemeId;
            snapshot = themes == null ? Map.of() : themes;
        }
        if (!snapshot.containsKey(configured) && !DEFAULT_THEME_ID.equals(configured)) {
            result.add(new ThemeChoice(configured, unavailableLabel + " (" + configured + ")",
                    GuiThemeAppearance.UNKNOWN, true));
        }
        for (var theme : snapshot.entrySet()) {
            result.add(new ThemeChoice(theme.getKey(), safeDisplayName(theme.getValue(), locale),
                    theme.getValue().appearance(), false));
        }
        boolean hasAvailable = result.stream().anyMatch(choice -> !choice.unavailable());
        if (!hasAvailable) {
            result.add(new ThemeChoice(DEFAULT_THEME_ID, fallbackLabel, GuiThemeAppearance.UNKNOWN, false));
        }
        return List.copyOf(result);
    }

    public static void applyThemeId(String themeId) {
        String normalized = normalizeThemeId(themeId);
        Map<String, GuiThemeContribution> snapshot;
        synchronized (LOCK) {
            if (themes == null) return;
            snapshot = themes;
            configuredThemeId = normalized;
        }
        Runnable task = () -> {
            synchronized (LOCK) {
                if (themes != snapshot) return;
            }
            applyConfiguredTheme(true);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    public static GuiThemeListenerSession addChangeListener(Runnable listener) {
        if (listener == null) {
            return GuiThemeListenerSession.none();
        }
        synchronized (LOCK) {
            changeListeners.add(listener);
        }
        return () -> {
            synchronized (LOCK) {
                changeListeners.remove(listener);
            }
        };
    }

    public static String configuredThemeId() {
        synchronized (LOCK) {
            return configuredThemeId;
        }
    }

    public static String activeThemeId() {
        synchronized (LOCK) {
            return activeThemeId;
        }
    }

    public static boolean isCurrentDark() {
        synchronized (LOCK) {
            return currentDark;
        }
    }

    public static void close() {
        synchronized (LOCK) {
            closeContributionListener();
            themes = null;
            changeListeners.clear();
        }
    }

    static void resetForTests() {
        close();
        synchronized (LOCK) {
            configuredThemeId = DEFAULT_THEME_ID;
            activeThemeId = DEFAULT_THEME_ID;
            currentDark = false;
        }
    }

    private static void applyConfiguredTheme(boolean refreshExistingWindows) {
        GuiThemeContribution registered;
        String target;
        synchronized (LOCK) {
            target = configuredThemeId;
            registered = themes.get(target);
        }
        if (registered == null) {
            log.warn("Configured GUI theme '{}' is unavailable; using system/JDK fallback without changing app.theme",
                    target);
            applySystemFallback("theme unavailable: " + target, null);
            return;
        }
        try {
            registered.applyOnEventDispatchThread();
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            log.error("GUI theme '{}' failed to apply; using system/JDK fallback", target, failure);
            applySystemFallback("theme apply failed: " + target, failure);
            return;
        }

        synchronized (LOCK) {
            closeContributionListener();
            activeThemeId = target;
            currentDark = deriveDark(registered.appearance());
            try {
                activeContributionListener = new ActiveThemeListener(registered);
            } catch (Throwable failure) {
                throwIfJvmFatal(failure);
                log.warn("GUI theme '{}' listener failed to start: {}",
                        target, failure.toString(), failure);
            }
        }
        if (refreshExistingWindows) {
            refreshWindows();
        }
        notifyListeners();
    }

    private static void applySystemFallback(String reason, Throwable cause) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            log.info("Applied system LookAndFeel fallback for GUI theme: {}", reason);
            refreshWindows();
        } catch (Throwable fallbackFailure) {
            throwIfJvmFatal(fallbackFailure);
            log.error("System LookAndFeel fallback failed; keeping current JDK LookAndFeel: {}",
                    fallbackFailure.toString(), fallbackFailure);
            if (cause != null) {
                log.debug("Original GUI theme failure before fallback", cause);
            }
        }
        synchronized (LOCK) {
            closeContributionListener();
            activeThemeId = DEFAULT_THEME_ID;
            currentDark = deriveDarkFromSwing();
        }
        notifyListeners();
    }

    private static final class ActiveThemeListener implements GuiThemeChangeListener {
        private final GuiThemeListenerSession session;

        private ActiveThemeListener(GuiThemeContribution contribution) {
            session = contribution.openListener(this);
        }

        @Override
        public void appearanceChanged(GuiThemeAppearance appearance) {
            SwingUtilities.invokeLater(() -> {
                synchronized (LOCK) {
                    if (activeContributionListener != this) return;
                    currentDark = deriveDark(appearance);
                }
                notifyListeners();
            });
        }
    }

    private static Map<String, GuiThemeContribution> collectThemes(
            Collection<DesktopUiPluginSnapshot> plugins
    ) {
        // 空集合也承担本次会话身份，不能跨会话复用空单例。
        Map<String, GuiThemeContribution> collected = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (DesktopUiPluginSnapshot source : plugins == null ? List.<DesktopUiPluginSnapshot>of() : plugins) {
            if (source == null) {
                continue;
            }
            String pluginId = source.id();
            List<GuiThemeContribution> contributions = source.themes();
            for (GuiThemeContribution contribution : contributions) {
                if (contribution == null) {
                    continue;
                }
                String id = normalizeThemeId(contribution.themeId());
                if (collected.containsKey(id)) {
                    collected.remove(id);
                    duplicates.add(id);
                    log.error("Duplicate GUI theme id '{}' from plugin '{}' ignored", id, pluginId);
                    continue;
                }
                if (duplicates.contains(id)) {
                    log.error("Duplicate GUI theme id '{}' from plugin '{}' ignored", id, pluginId);
                    continue;
                }
                collected.put(id, contribution);
            }
        }
        return Collections.unmodifiableMap(collected);
    }

    private static String safeDisplayName(GuiThemeContribution contribution, Locale locale) {
        try {
            return contribution.displayName(locale);
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            log.warn("GUI theme '{}' display name failed: {}",
                    contribution.themeId(), failure.toString(), failure);
            return normalizeThemeId(contribution.themeId());
        }
    }

    private static void closeContributionListener() {
        ActiveThemeListener previous = activeContributionListener;
        activeContributionListener = null;
        if (previous == null) return;
        try {
            previous.session.close();
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            log.warn("Failed to close GUI theme listener: {}", failure.toString(), failure);
        }
    }

    private static void throwIfJvmFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError virtualMachineError) {
            throw virtualMachineError;
        }
        if (failure instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
    }

    private static void notifyListeners() {
        List<Runnable> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(changeListeners);
        }
        for (Runnable listener : snapshot) {
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    listener.run();
                } else {
                    SwingUtilities.invokeLater(listener);
                }
            } catch (RuntimeException e) {
                log.warn("GUI theme listener failed: {}", e.toString(), e);
            }
        }
    }

    private static void refreshWindows() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(GuiThemeManager::refreshWindows);
            return;
        }
        for (Window window : Window.getWindows()) {
            try {
                SwingUtilities.updateComponentTreeUI(window);
                GuiInputStyleNormalizer.apply(window);
                window.invalidate();
                window.validate();
                window.repaint();
            } catch (RuntimeException e) {
                log.warn("Failed to refresh Swing window after theme change: {}", e.toString(), e);
            }
        }
    }

    private static boolean deriveDark(GuiThemeAppearance appearance) {
        return switch (appearance == null ? GuiThemeAppearance.UNKNOWN : appearance) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM, UNKNOWN -> deriveDarkFromSwing();
        };
    }

    private static boolean deriveDarkFromSwing() {
        Color color = UIManager.getColor("Panel.background");
        if (color == null) {
            color = UIManager.getColor("control");
        }
        if (color == null) {
            return false;
        }
        return (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) < 128_000;
    }

    private static String normalizeThemeId(String value) {
        if (value == null) {
            return DEFAULT_THEME_ID;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? DEFAULT_THEME_ID : trimmed;
    }

    public record ThemeChoice(String id, String displayName, GuiThemeAppearance appearance, boolean unavailable) {
        public ThemeChoice {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(appearance, "appearance");
        }
    }
}
