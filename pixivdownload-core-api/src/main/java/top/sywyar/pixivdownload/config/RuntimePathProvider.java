package top.sywyar.pixivdownload.config;

import java.nio.file.Path;

/**
 * Runtime paths exposed to optional plugins without depending on app utilities.
 */
public interface RuntimePathProvider {

    Path resolvePluginConfigPath(String pluginId, String extension);

    Path resolvePluginStateDirectory(String pluginId);

    Path resolvePluginDataDirectory(String pluginId);
}
