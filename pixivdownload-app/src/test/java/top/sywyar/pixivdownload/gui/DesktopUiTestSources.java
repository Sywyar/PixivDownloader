package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.util.List;

/** Test-only adapter from the app registry to the stable desktop UI contract. */
public final class DesktopUiTestSources {
    private DesktopUiTestSources() {}

    public static List<DesktopUiPluginSource> from(PluginRegistry registry) {
        return from(registry.registeredPlugins());
    }

    public static List<DesktopUiPluginSource> from(
            List<PluginRegistry.RegisteredPlugin> registeredPlugins) {
        return registeredPlugins.stream()
                .map(registered -> new DesktopUiPluginSource(
                        registered.id(), registered.source() == PluginSource.BUILT_IN,
                        registered.plugin(), registered.classLoader(),
                        registered.packageId(), registered.generation()))
                .toList();
    }
}
