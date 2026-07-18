package top.sywyar.pixivdownload.config;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class AppRuntimePathProvider implements RuntimePathProvider {

    @Override
    public Path resolvePluginConfigPath(String pluginId, String extension) {
        return RuntimeFiles.resolvePluginConfigPath(pluginId, extension);
    }

    @Override
    public Path resolvePluginStateDirectory(String pluginId) {
        return RuntimeFiles.resolvePluginStateDirectory(pluginId);
    }

    @Override
    public Path resolvePluginDataDirectory(String pluginId) {
        return RuntimeFiles.resolvePluginDataDirectory(pluginId);
    }
}
