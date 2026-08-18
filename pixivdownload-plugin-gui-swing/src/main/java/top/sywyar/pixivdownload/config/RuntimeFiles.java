package top.sywyar.pixivdownload.config;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;

import java.nio.file.Path;

public final class RuntimeFiles {
    public static final String DEFAULT_DOWNLOAD_ROOT = "pixiv-download";
    public static final String PIXIV_DOWNLOAD_DB = "pixiv_download.db";
    private RuntimeFiles() {}

    public static Path resolveConfigYamlPath() { return SwingHost.installed() ? SwingHost.context().configPath() : Path.of("config", "config.yaml"); }
    public static Path dataDirectory() { return SwingHost.installed() ? SwingHost.host().dataDirectory() : Path.of("data"); }
    public static Path guiStateDirectory() { return SwingHost.installed() ? SwingHost.host().guiStateDirectory() : Path.of("state", "gui"); }
    public static Path resolvePluginConfigPath(String id, String extension) {
        if (SwingHost.installed()) return SwingHost.host().resolvePluginConfigPath(id, extension);
        return Path.of("config", "plugins", id + "." + extension);
    }
    public static Path resolveImageClassifierPath(String rootFolder) {
        return SwingHost.installed() ? SwingHost.host().resolveImageClassifierPath(rootFolder) : Path.of("config", "image_classifier.properties");
    }
    public static Path resolveSetupConfigPath(String rootFolder) {
        return SwingHost.installed() ? SwingHost.host().resolveSetupConfigPath(rootFolder) : Path.of("state", "setup_config.json");
    }
    public static Path resolveDatabasePath(String rootFolder) {
        return SwingHost.installed() ? SwingHost.host().resolveDatabasePath(rootFolder) : dataDirectory().resolve(PIXIV_DOWNLOAD_DB);
    }
    public static String readDownloadRootFromConfig(Path configPath, String fallback) {
        if (SwingHost.installed()) return SwingHost.host().readDownloadRootFromConfig(configPath, fallback);
        try { return new ConfigFileEditor(configPath).readAll(java.util.Set.of("download.root-folder")).getOrDefault("download.root-folder", fallback); }
        catch (Exception ignored) { return fallback; }
    }
    public static String normalizeRootFolder(String rootFolder) {
        if (SwingHost.installed()) return SwingHost.host().normalizeRootFolder(rootFolder);
        return rootFolder == null || rootFolder.isBlank() ? DEFAULT_DOWNLOAD_ROOT : Path.of(rootFolder.trim()).normalize().toString();
    }
}
