package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.Objects;

/** Short-lived app-internal view of one host-verified active plugin. */
public record DesktopUiPluginSource(String id, boolean builtIn,
                                    PixivFeaturePlugin plugin, ClassLoader classLoader) {
    public DesktopUiPluginSource {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        plugin = Objects.requireNonNull(plugin, "plugin");
        classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }
}
