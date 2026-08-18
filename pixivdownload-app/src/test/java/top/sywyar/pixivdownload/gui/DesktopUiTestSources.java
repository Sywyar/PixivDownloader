package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.util.List;

/** Test-only adapter from the app registry to the stable desktop UI contract. */
public final class DesktopUiTestSources {
    private DesktopUiTestSources() {}

    public static List<DesktopUiContext.PluginSource> from(PluginRegistry registry) {
        return from(registry.registeredPlugins());
    }

    public static List<DesktopUiContext.PluginSource> from(
            List<PluginRegistry.RegisteredPlugin> registeredPlugins) {
        return registeredPlugins.stream()
                .map(registered -> new DesktopUiContext.PluginSource(
                        registered.id(), registered.source() == PluginSource.BUILT_IN,
                        registered.plugin(), registered.classLoader()))
                .toList();
    }
}
