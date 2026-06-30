package top.sywyar.pixivdownload.plugin.gui;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Collects GUI theme contributions from active plugins and indexes them by global theme id.
 */
@Component
public class GuiThemeContributionRegistry {

    /** A registered GUI theme and its declaring plugin id. */
    public record RegisteredTheme(String pluginId, GuiThemeContribution contribution) {
    }

    private final Object lock = new Object();

    private volatile List<RegisteredTheme> snapshot = List.of();
    private volatile Map<String, RegisteredTheme> byThemeId = Map.of();

    public GuiThemeContributionRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            List<GuiThemeContribution> themes = plugin.guiThemes();
            if (themes == null) {
                throw new IllegalStateException("GUI theme contribution list is null (plugin: "
                        + plugin.id() + ")");
            }
            if (!themes.isEmpty()) {
                register(plugin.id(), themes);
            }
        }
    }

    /**
     * Registers all GUI themes declared by one plugin. Theme ids are global; duplicates are rejected with a diagnostic
     * naming the conflicting plugins.
     */
    public void register(String pluginId, List<GuiThemeContribution> themes) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalStateException("GUI theme contribution without pluginId");
        }
        if (themes == null || themes.isEmpty()) {
            throw new IllegalStateException("empty GUI theme contribution (plugin: " + pluginId + ")");
        }
        synchronized (lock) {
            if (snapshot.stream().anyMatch(registered -> registered.pluginId().equals(pluginId))) {
                throw new IllegalStateException("GUI themes already registered for plugin: " + pluginId);
            }
            Map<String, RegisteredTheme> nextById = new LinkedHashMap<>(byThemeId);
            List<RegisteredTheme> next = new ArrayList<>(snapshot);
            for (GuiThemeContribution theme : themes) {
                validate(theme, pluginId);
                RegisteredTheme existing = nextById.get(theme.themeId());
                if (existing != null) {
                    throw duplicateThemeId(theme.themeId(), existing.pluginId(), pluginId);
                }
                RegisteredTheme registered = new RegisteredTheme(pluginId, theme);
                next.add(registered);
                nextById.put(theme.themeId(), registered);
            }
            snapshot = List.copyOf(next);
            byThemeId = immutableCopy(nextById);
        }
    }

    /**
     * Removes all GUI themes declared by one plugin. Unknown plugin ids are ignored to keep unified teardown idempotent.
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            List<RegisteredTheme> next = snapshot.stream()
                    .filter(registered -> !registered.pluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
            snapshot = next;
            LinkedHashMap<String, RegisteredTheme> nextById = new LinkedHashMap<>();
            for (RegisteredTheme registered : next) {
                nextById.put(registered.contribution().themeId(), registered);
            }
            byThemeId = immutableCopy(nextById);
        }
    }

    /** Returns all registered GUI themes in plugin registration order. */
    public List<RegisteredTheme> themes() {
        return snapshot;
    }

    /** Returns an immutable index keyed by global theme id. */
    public Map<String, RegisteredTheme> themesById() {
        return byThemeId;
    }

    /** Finds a registered GUI theme by global theme id. */
    public Optional<RegisteredTheme> find(String themeId) {
        return Optional.ofNullable(byThemeId.get(themeId));
    }

    private static void validate(GuiThemeContribution theme, String pluginId) {
        if (theme == null) {
            throw new IllegalStateException("null GUI theme contribution (plugin: " + pluginId + ")");
        }
        if (theme.themeId() == null || theme.themeId().isBlank()) {
            throw new IllegalStateException("GUI theme without id (plugin: " + pluginId + ")");
        }
    }

    private static IllegalStateException duplicateThemeId(String themeId, String existingPluginId,
                                                         String conflictingPluginId) {
        return new IllegalStateException("duplicate GUI theme id: " + themeId
                + " (plugins: " + existingPluginId + ", " + conflictingPluginId + ")");
    }

    private static Map<String, RegisteredTheme> immutableCopy(Map<String, RegisteredTheme> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
