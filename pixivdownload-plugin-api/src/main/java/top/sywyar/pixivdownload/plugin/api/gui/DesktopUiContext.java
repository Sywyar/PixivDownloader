package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable startup context passed from the host to the selected desktop UI provider.
 *
 * @param serverPort local backend port
 * @param rootFolder configured application root folder
 * @param configPath host configuration path
 * @param startupLaunch whether the process was started by an operating-system startup entry
 * @param startupPluginSources verified plugin snapshot used before the backend starts
 * @param currentPluginSourcesSupplier supplier for the current verified plugin snapshot
 * @param model host-owned toolkit-neutral desktop UI state and event dispatcher
 * @param host toolkit-neutral host operations
 */
public record DesktopUiContext(int serverPort, String rootFolder, Path configPath, boolean startupLaunch,
                               List<PluginSource> startupPluginSources,
                               Supplier<List<PluginSource>> currentPluginSourcesSupplier,
                               DesktopUiModel model,
                               DesktopUiHost host) {
    /**
     * Validates and defensively copies the startup context.
     *
     * @param serverPort local backend port
     * @param rootFolder configured application root folder
     * @param configPath host configuration path
     * @param startupLaunch whether the process was started by an operating-system startup entry
     * @param startupPluginSources verified startup plugin snapshot
     * @param currentPluginSourcesSupplier supplier for the current plugin snapshot
     * @param model host-owned desktop UI state and event dispatcher
     * @param host toolkit-neutral host operations
     */
    public DesktopUiContext {
        if (serverPort <= 0 || serverPort > 65_535) throw new IllegalArgumentException("serverPort out of range: " + serverPort);
        rootFolder = Objects.requireNonNull(rootFolder, "rootFolder");
        configPath = Objects.requireNonNull(configPath, "configPath");
        startupPluginSources = List.copyOf(Objects.requireNonNull(startupPluginSources, "startupPluginSources"));
        currentPluginSourcesSupplier = Objects.requireNonNull(currentPluginSourcesSupplier, "currentPluginSourcesSupplier");
        model = Objects.requireNonNull(model, "model");
        host = Objects.requireNonNull(host, "host");
    }

    /**
     * Returns a defensive copy of the current verified plugin snapshot.
     *
     * @return current verified plugin sources
     */
    public List<PluginSource> currentPluginSources() {
        List<PluginSource> sources = currentPluginSourcesSupplier.get();
        return sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * Returns the current toolkit-neutral desktop UI document.
     *
     * @return current desktop UI document
     */
    public DesktopUiDocument currentDocument() {
        return Objects.requireNonNull(model.document(), "model returned null document");
    }

    /**
     * Returns the current document revision.
     *
     * @return current document revision
     */
    public long currentDocumentRevision() {
        return model.revision();
    }

    /**
     * Dispatches one typed renderer event to the host-owned model.
     *
     * @param event typed renderer event
     */
    public void dispatchEvent(top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Event event) {
        model.dispatch(Objects.requireNonNull(event, "event"));
    }

    /**
     * Host-verified plugin identity and classloader; consumers must not re-read {@link PixivFeaturePlugin#id()}.
     *
     * @param id verified plugin id
     * @param builtIn whether the plugin is built into the host
     * @param plugin stable feature-plugin view
     * @param classLoader classloader that owns plugin resources
     */
    public record PluginSource(String id, boolean builtIn, PixivFeaturePlugin plugin, ClassLoader classLoader) {
        /**
         * Validates the verified plugin projection.
         *
         * @param id verified plugin id
         * @param builtIn whether the plugin is built into the host
         * @param plugin stable feature-plugin view
         * @param classLoader classloader that owns plugin resources
         */
        public PluginSource {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            plugin = Objects.requireNonNull(plugin, "plugin");
            classLoader = Objects.requireNonNull(classLoader, "classLoader");
        }
    }
}
