package top.sywyar.pixivdownload.gui.theme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeListenerSession;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Window;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Swing-only GUI theme manager. The core owns only theme id persistence, contribution selection, listener lifetime,
 * and system/JDK fallback. Concrete theme engines live in startup plugins.
 */
public final class GuiThemeManager {

    public static final String DEFAULT_THEME_ID = "system";

    private static final Logger log = LoggerFactory.getLogger(GuiThemeManager.class);
    private static final Pattern APP_THEME_PATTERN =
            Pattern.compile("^(\\s*app\\.theme\\s*:\\s*)([^#]*?)(\\s*(?:#.*)?)$");
    private static final Object LOCK = new Object();

    private static Path configPath;
    private static String configuredThemeId = DEFAULT_THEME_ID;
    private static String activeThemeId = DEFAULT_THEME_ID;
    private static GuiThemeAppearance currentAppearance = GuiThemeAppearance.UNKNOWN;
    private static boolean currentDark;
    private static Map<String, RegisteredTheme> themes = Map.of();
    private static GuiThemeListenerSession activeContributionListener = GuiThemeListenerSession.none();
    private static final List<Runnable> changeListeners = new ArrayList<>();

    private GuiThemeManager() {
    }

    public static String readPersistedThemeId(Path configPath) {
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return DEFAULT_THEME_ID;
        }
        try {
            for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
                Matcher matcher = APP_THEME_PATTERN.matcher(line);
                if (matcher.matches()) {
                    return normalizeThemeId(stripYamlScalar(matcher.group(2)));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read app.theme from {}: {}", configPath, e.toString());
        }
        return DEFAULT_THEME_ID;
    }

    public static void applyBeforeFirstWindow(Path configPath, String configuredThemeId,
                                              Collection<ThemePluginSource> activePlugins) {
        Runnable task = () -> {
            synchronized (LOCK) {
                GuiThemeManager.configPath = configPath;
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
        Map<String, RegisteredTheme> snapshot;
        synchronized (LOCK) {
            configured = configuredThemeId;
            snapshot = themes;
        }
        if (!snapshot.containsKey(configured) && !DEFAULT_THEME_ID.equals(configured)) {
            result.add(new ThemeChoice(configured, unavailableLabel + " (" + configured + ")",
                    GuiThemeAppearance.UNKNOWN, true));
        }
        for (RegisteredTheme theme : snapshot.values()) {
            result.add(new ThemeChoice(theme.id(), safeDisplayName(theme.contribution(), locale),
                    theme.contribution().appearance(), false));
        }
        boolean hasAvailable = result.stream().anyMatch(choice -> !choice.unavailable());
        if (!hasAvailable) {
            result.add(new ThemeChoice(DEFAULT_THEME_ID, fallbackLabel, GuiThemeAppearance.UNKNOWN, false));
        }
        return List.copyOf(result);
    }

    public static boolean applyUserSelection(Path configPath, String themeId) {
        String normalized = normalizeThemeId(themeId);
        boolean persisted = persistThemeId(configPath, normalized);
        synchronized (LOCK) {
            GuiThemeManager.configPath = configPath;
            GuiThemeManager.configuredThemeId = normalized;
        }
        Runnable task = () -> applyConfiguredTheme(true);
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
        return persisted;
    }

    public static void applyThemeId(String themeId) {
        String normalized = normalizeThemeId(themeId);
        synchronized (LOCK) {
            configuredThemeId = normalized;
        }
        Runnable task = () -> applyConfiguredTheme(true);
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    public static boolean persistThemeId(Path configPath, String themeId) {
        if (configPath == null || !Files.exists(configPath)) {
            return false;
        }
        String normalized = normalizeThemeId(themeId);
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8));
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = APP_THEME_PATTERN.matcher(lines.get(i));
                if (matcher.matches()) {
                    lines.set(i, matcher.group(1) + normalized + matcher.group(3));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add("app.theme: " + normalized);
            }
            Files.write(configPath, lines, StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to write app.theme to {}: {}", configPath, e.toString());
            return false;
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

    static void resetForTests() {
        synchronized (LOCK) {
            closeContributionListener();
            configPath = null;
            configuredThemeId = DEFAULT_THEME_ID;
            activeThemeId = DEFAULT_THEME_ID;
            currentAppearance = GuiThemeAppearance.UNKNOWN;
            currentDark = false;
            themes = Map.of();
            changeListeners.clear();
        }
    }

    private static void applyConfiguredTheme(boolean refreshExistingWindows) {
        RegisteredTheme registered;
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
            registered.contribution().applyOnEventDispatchThread();
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            log.error("GUI theme '{}' failed to apply; using system/JDK fallback", target, failure);
            applySystemFallback("theme apply failed: " + target, failure);
            return;
        }

        synchronized (LOCK) {
            closeContributionListener();
            activeThemeId = registered.id();
            currentAppearance = registered.contribution().appearance();
            currentDark = deriveDark(currentAppearance);
            try {
                activeContributionListener = registered.contribution().openListener(
                        appearance -> SwingUtilities.invokeLater(() -> applyAppearance(appearance)));
            } catch (Throwable failure) {
                throwIfJvmFatal(failure);
                activeContributionListener = GuiThemeListenerSession.none();
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
            currentAppearance = GuiThemeAppearance.UNKNOWN;
            currentDark = deriveDarkFromSwing();
        }
        notifyListeners();
    }

    private static void applyAppearance(GuiThemeAppearance appearance) {
        synchronized (LOCK) {
            currentAppearance = appearance == null ? GuiThemeAppearance.UNKNOWN : appearance;
            currentDark = deriveDark(currentAppearance);
        }
        notifyListeners();
    }

    private static Map<String, RegisteredTheme> collectThemes(Collection<ThemePluginSource> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return Map.of();
        }
        Map<String, RegisteredTheme> collected = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (ThemePluginSource source : plugins) {
            if (source == null) {
                continue;
            }
            String pluginId = source.pluginId();
            PixivFeaturePlugin plugin = source.plugin();
            List<GuiThemeContribution> contributions;
            try {
                contributions = plugin.guiThemes();
                if (contributions != null) {
                    contributions = new ArrayList<>(contributions);
                }
            } catch (Throwable failure) {
                throwIfJvmFatal(failure);
                log.warn("Plugin '{}' failed to expose GUI themes: {}",
                        pluginId, failure.toString(), failure);
                continue;
            }
            if (contributions == null) {
                log.warn("Plugin '{}' returned null GUI themes; ignoring it", pluginId);
                continue;
            }
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
                collected.put(id, new RegisteredTheme(id, pluginId, contribution));
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(collected));
    }

    /** 启动发现桥已校验并盖章的主题插件来源；主题聚合不得重新读取插件自报 id。 */
    public record ThemePluginSource(String pluginId, PixivFeaturePlugin plugin) {
        public ThemePluginSource {
            if (pluginId == null || pluginId.isBlank()) {
                throw new IllegalArgumentException("pluginId must not be blank");
            }
            Objects.requireNonNull(plugin, "plugin");
        }
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
        try {
            activeContributionListener.close();
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            log.warn("Failed to close GUI theme listener: {}", failure.toString(), failure);
        } finally {
            activeContributionListener = GuiThemeListenerSession.none();
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

    private static String stripYamlScalar(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        int comment = trimmed.indexOf('#');
        return comment >= 0 ? trimmed.substring(0, comment).trim() : trimmed;
    }

    public record ThemeChoice(String id, String displayName, GuiThemeAppearance appearance, boolean unavailable) {
        public ThemeChoice {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(appearance, "appearance");
        }
    }

    private record RegisteredTheme(String id, String pluginId, GuiThemeContribution contribution) {
        private RegisteredTheme {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(contribution, "contribution");
        }
    }
}
