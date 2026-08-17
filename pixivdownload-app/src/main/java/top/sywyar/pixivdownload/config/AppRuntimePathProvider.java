package top.sywyar.pixivdownload.config;

import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.nio.file.Path;
import java.util.Objects;

public final class AppRuntimePathProvider implements RuntimePathProvider {

    private final String ownerPluginId;

    public AppRuntimePathProvider(String ownerPluginId) {
        this.ownerPluginId = Objects.requireNonNull(ownerPluginId, "ownerPluginId");
    }

    @Override
    public Path configFile(String extension) {
        return RuntimeFiles.resolvePluginConfigPath(ownerPluginId, extension);
    }

    @Override
    public Path stateDirectory() {
        return RuntimeFiles.resolvePluginStateDirectory(ownerPluginId);
    }

    @Override
    public Path dataDirectory() {
        return RuntimeFiles.resolvePluginDataDirectory(ownerPluginId);
    }
}
