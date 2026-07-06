package top.sywyar.pixivdownload.douyin.settings;

import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Properties;

public class DouyinPluginSettingsService {

    private static final String PLUGIN_ID = "douyin";

    public static final String KEY_DOWNLOAD_DIRECTORY = "douyin.download.directory";
    public static final String KEY_PROXY_MODE = "douyin.proxy.mode";
    public static final String KEY_PROXY_HOST = "douyin.proxy.host";
    public static final String KEY_PROXY_PORT = "douyin.proxy.port";

    private final Path configFile;
    private final Path inheritedDownloadDirectory;
    private final DouyinRuntimeSettings fixedSettings;

    public DouyinPluginSettingsService(Path inheritedDownloadDirectory) {
        this(RuntimeFiles.resolvePluginConfigPath(PLUGIN_ID, "properties"), inheritedDownloadDirectory, null);
    }

    DouyinPluginSettingsService(Path configFile, Path inheritedDownloadDirectory) {
        this(configFile, inheritedDownloadDirectory, null);
    }

    private DouyinPluginSettingsService(Path configFile,
                                        Path inheritedDownloadDirectory,
                                        DouyinRuntimeSettings fixedSettings) {
        this.configFile = configFile;
        this.inheritedDownloadDirectory = normalizePath(inheritedDownloadDirectory);
        this.fixedSettings = fixedSettings;
    }

    public static DouyinPluginSettingsService fixed(Path downloadDirectory, DouyinProxyMode proxyMode) {
        return fixed(downloadDirectory, proxyMode, "", 0);
    }

    public static DouyinPluginSettingsService fixed(Path downloadDirectory,
                                                    DouyinProxyMode proxyMode,
                                                    String proxyHost,
                                                    int proxyPort) {
        DouyinRuntimeSettings runtimeSettings = new DouyinRuntimeSettings(
                normalizePath(downloadDirectory),
                proxyMode == null ? DouyinProxyMode.INHERIT : proxyMode,
                proxyHost,
                proxyPort);
        return new DouyinPluginSettingsService(null, runtimeSettings.downloadDirectory(), runtimeSettings);
    }

    public synchronized DouyinPluginSettings load() {
        if (fixedSettings != null) {
            return new DouyinPluginSettings(
                    fixedSettings.downloadDirectory().toString(),
                    fixedSettings.proxyMode(),
                    fixedSettings.proxyHost(),
                    fixedSettings.proxyPort() > 0 ? Integer.toString(fixedSettings.proxyPort()) : "");
        }
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return DouyinPluginSettings.defaults();
        }
        try {
            Properties properties = loadProperties(configFile);
            return new DouyinPluginSettings(
                    property(properties, KEY_DOWNLOAD_DIRECTORY),
                    DouyinProxyMode.from(property(properties, KEY_PROXY_MODE)),
                    property(properties, KEY_PROXY_HOST),
                    property(properties, KEY_PROXY_PORT));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Douyin plugin settings: " + configFile, e);
        }
    }

    public DouyinRuntimeSettings runtimeSettings() {
        if (fixedSettings != null) {
            return fixedSettings;
        }
        DouyinPluginSettings settings = load();
        return new DouyinRuntimeSettings(
                effectiveDownloadDirectory(settings),
                settings.proxyMode(),
                settings.proxyHost(),
                parsePort(settings.proxyPort()));
    }

    public Path inheritedDownloadDirectory() {
        return inheritedDownloadDirectory;
    }

    public Path effectiveDownloadDirectory(DouyinPluginSettings settings) {
        DouyinPluginSettings normalized = normalize(settings);
        if (normalized.downloadDirectory().isBlank()) {
            return inheritedDownloadDirectory;
        }
        return normalizePath(Path.of(normalized.downloadDirectory()));
    }

    private static DouyinPluginSettings normalize(DouyinPluginSettings settings) {
        DouyinPluginSettings normalized = settings == null ? DouyinPluginSettings.defaults() : settings;
        if (!normalized.downloadDirectory().isBlank()) {
            try {
                normalizePath(Path.of(normalized.downloadDirectory()));
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("Invalid Douyin download directory", e);
            }
        }
        return normalized;
    }

    private static Path normalizePath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Path must not be null");
        }
        return path.normalize();
    }

    private static Properties loadProperties(Path configFile) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String property(Properties properties, String key) {
        return properties == null ? "" : properties.getProperty(key, "");
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
