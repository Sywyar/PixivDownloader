package top.sywyar.pixivdownload.config;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class AppRuntimePathProvider implements RuntimePathProvider {

    private final DownloadSettings downloadSettings;

    public AppRuntimePathProvider(DownloadSettings downloadSettings) {
        this.downloadSettings = downloadSettings;
    }

    @Override
    public Path resolvePluginConfigPath(String pluginId, String extension) {
        return RuntimeFiles.resolvePluginConfigPath(pluginId, extension);
    }

    @Override
    public Path resolvePluginDataDirectory(String pluginId) {
        return RuntimeFiles.resolvePluginDataDirectory(pluginId);
    }

    @Override
    public Path resolveBatchStatePath() {
        return RuntimeFiles.resolveBatchStatePath(downloadSettings.getRootFolder());
    }

    @Override
    public Path narrationVoiceDirectory(long castId) {
        return RuntimeFiles.narrationVoiceCastDirectory(castId);
    }

    @Override
    public Path narrationVoiceFile(long castId, int characterId, String extension) {
        return RuntimeFiles.narrationVoiceFile(castId, characterId, extension);
    }

    @Override
    public Path resolveEdgeTtsVersionPath() {
        return RuntimeFiles.resolveEdgeTtsVersionPath();
    }
}
