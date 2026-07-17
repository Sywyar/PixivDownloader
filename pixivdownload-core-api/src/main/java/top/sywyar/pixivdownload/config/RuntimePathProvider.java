package top.sywyar.pixivdownload.config;

import java.nio.file.Path;

/**
 * Runtime paths exposed to optional plugins without depending on app utilities.
 */
public interface RuntimePathProvider {

    Path resolvePluginConfigPath(String pluginId, String extension);

    Path resolvePluginDataDirectory(String pluginId);

    Path resolveBatchStatePath();

    Path narrationVoiceDirectory(long castId);

    /**
     * Resolves one narration reference-voice file. Implementations must reject extensions that are not a
     * single alphanumeric file-extension token; {@code null} or blank may default to {@code wav}.
     */
    Path narrationVoiceFile(long castId, int characterId, String extension);

    Path resolveEdgeTtsVersionPath();
}
