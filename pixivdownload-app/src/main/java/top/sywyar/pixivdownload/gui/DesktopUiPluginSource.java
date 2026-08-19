package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.util.Objects;

/** Short-lived app-internal view of one host-verified active plugin. */
public record DesktopUiPluginSource(String id, boolean builtIn,
                                    PixivFeaturePlugin plugin, ClassLoader classLoader,
                                    String packageId, long generation) {
    public DesktopUiPluginSource {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        plugin = Objects.requireNonNull(plugin, "plugin");
        classLoader = Objects.requireNonNull(classLoader, "classLoader");
        if (packageId == null || packageId.isBlank()) throw new IllegalArgumentException("packageId must not be blank");
        if (generation < 0L) throw new IllegalArgumentException("generation must not be negative");
    }

    public DesktopUiPluginSource(String id, boolean builtIn,
                                 PixivFeaturePlugin plugin, ClassLoader classLoader) {
        this(id, builtIn, plugin, classLoader, id, 0L);
    }

    Fingerprint fingerprint() {
        return new Fingerprint(id, builtIn, packageId, generation);
    }

    record Fingerprint(String id, boolean builtIn, String packageId, long generation) { }
}
