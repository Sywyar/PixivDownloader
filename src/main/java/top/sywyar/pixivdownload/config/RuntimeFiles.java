package top.sywyar.pixivdownload.config;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 管理运行期生成文件的目录，并兼容旧位置自动迁移。
 */
@Slf4j
public final class RuntimeFiles {

    public static final String CONFIG_DIR_PROPERTY = "pixivdownload.config-dir";
    public static final String STATE_DIR_PROPERTY = "pixivdownload.state-dir";
    public static final String DATA_DIR_PROPERTY = "pixivdownload.data-dir";
    public static final String INSTANCE_DIR_PROPERTY = "pixivdownload.instance-dir";

    public static final String DEFAULT_CONFIG_DIR = "config";
    public static final String DEFAULT_STATE_DIR = "state";
    public static final String DEFAULT_DATA_DIR = "data";
    public static final String DEFAULT_DOWNLOAD_ROOT = "pixiv-download";
    private static final String WINDOWS_INSTANCE_ROOT_ENV = "LOCALAPPDATA";
    private static final String WINDOWS_INSTANCE_APP_DIR = AppInfo.LEGACY_ARTIFACT_NAME;
    private static final String NON_WINDOWS_INSTANCE_APP_DIR = AppInfo.HIDDEN_DIRECTORY_NAME;
    private static final String INSTANCE_SUBDIR = "run";

    public static final String CONFIG_YAML = "config.yaml";
    public static final String IMAGE_CLASSIFIER_PROPERTIES = "image_classifier.properties";
    public static final String SETUP_CONFIG_JSON = "setup_config.json";
    public static final String BATCH_STATE_JSON = "batch_state.json";
    public static final String PIXIV_DOWNLOAD_DB = "pixiv_download.db";
    public static final String COLLECTION_ICONS_DIR = "collection_icons";
    public static final String GALLERY_THUMBNAILS_DIR = "gallery_thumbs";
    public static final String GUI_STATE_DIR = "gui";
    public static final String TTS_DIR = "tts";
    public static final String EDGE_TTS_CHROMIUM_VERSION = "chromium-version.txt";
    public static final String BACKFILL_DIR = "backfill";
    public static final String BACKFILL_UNREACHABLE_FILE = "unreachable.json";
    private static final String LEGACY_COLLECTION_ICONS_DIR = "_collection_icons";
    private static final String LEGACY_GUI_STATE_DIR = "_gui";
    private static final String LEGACY_TTS_DIR = "_tts";
    private static final List<String> SQLITE_COMPANION_SUFFIXES = List.of("-wal", "-shm");

    private RuntimeFiles() {
    }

    public static Path configDirectory() {
        return resolveDirectory(CONFIG_DIR_PROPERTY, DEFAULT_CONFIG_DIR);
    }

    public static Path stateDirectory() {
        return resolveDirectory(STATE_DIR_PROPERTY, DEFAULT_STATE_DIR);
    }

    public static Path dataDirectory() {
        return resolveDirectory(DATA_DIR_PROPERTY, DEFAULT_DATA_DIR);
    }

    public static Path collectionIconsDirectory() {
        Path dataDir = dataDirectory();
        Path target = dataDir.resolve(COLLECTION_ICONS_DIR);
        return resolveManagedDirectory(target, List.of(
                siblingOf(dataDir, COLLECTION_ICONS_DIR),
                siblingOf(dataDir, LEGACY_COLLECTION_ICONS_DIR)
        ));
    }

    public static Path galleryThumbnailDirectory() {
        return dataDirectory().resolve(GALLERY_THUMBNAILS_DIR).normalize();
    }

    public static Path guiStateDirectory() {
        Path stateDir = stateDirectory();
        Path target = stateDir.resolve(GUI_STATE_DIR);
        return resolveManagedDirectory(target, List.of(siblingOf(stateDir, LEGACY_GUI_STATE_DIR)));
    }

    public static Path ttsDataDirectory() {
        Path dataDir = dataDirectory();
        Path target = dataDir.resolve(TTS_DIR);
        return resolveManagedDirectory(target, List.of(siblingOf(dataDir, LEGACY_TTS_DIR)));
    }

    public static Path resolveEdgeTtsVersionPath() {
        return ttsDataDirectory().resolve(EDGE_TTS_CHROMIUM_VERSION).normalize();
    }

    public static Path backfillDirectory() {
        Path target = dataDirectory().resolve(BACKFILL_DIR);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new UncheckedIOException(message("runtime.error.resolve-directory.failed", target), e);
        }
        return target.normalize();
    }

    public static Path resolveBackfillUnreachablePath() {
        return backfillDirectory().resolve(BACKFILL_UNREACHABLE_FILE).normalize();
    }

    public static Path singleInstanceDirectory() {
        String configured = System.getProperty(INSTANCE_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }

        return defaultSingleInstanceDirectory(
                System.getProperty("os.name"),
                System.getenv(WINDOWS_INSTANCE_ROOT_ENV),
                System.getProperty("user.home"),
                System.getProperty("java.io.tmpdir"));
    }

    public static Path resolveConfigYamlPath() {
        Path target = configDirectory().resolve(CONFIG_YAML);
        return resolveManagedFile(target, List.of(legacyRootFile(target.getParent(), CONFIG_YAML)));
    }

    public static Path resolveImageClassifierPath(String rootFolder) {
        Path target = configDirectory().resolve(IMAGE_CLASSIFIER_PROPERTIES);
        return resolveManagedFile(target, rootAndDownloadLegacyCandidates(target.getParent(), rootFolder, IMAGE_CLASSIFIER_PROPERTIES));
    }

    public static Path resolveSetupConfigPath(String rootFolder) {
        Path target = stateDirectory().resolve(SETUP_CONFIG_JSON);
        return resolveManagedFile(target, rootAndDownloadLegacyCandidates(target.getParent(), rootFolder, SETUP_CONFIG_JSON));
    }

    public static Path resolveBatchStatePath(String rootFolder) {
        Path target = stateDirectory().resolve(BATCH_STATE_JSON);
        return resolveManagedFile(target, rootAndDownloadLegacyCandidates(target.getParent(), rootFolder, BATCH_STATE_JSON));
    }

    public static Path resolveDatabasePath(String rootFolder) {
        Path target = dataDirectory().resolve(PIXIV_DOWNLOAD_DB);
        return resolveManagedFile(target, rootAndDownloadLegacyCandidates(target.getParent(), rootFolder, PIXIV_DOWNLOAD_DB), SQLITE_COMPANION_SUFFIXES);
    }

    public static void prepareRuntimeFiles(String rootFolder) {
        resolveConfigYamlPath();
        resolveImageClassifierPath(rootFolder);
        resolveSetupConfigPath(rootFolder);
        resolveBatchStatePath(rootFolder);
        resolveDatabasePath(rootFolder);
        collectionIconsDirectory();
        guiStateDirectory();
        ttsDataDirectory();
    }

    public static String readDownloadRootFromConfig(Path configPath, String defaultRootFolder) {
        String fallback = normalizeRootFolder(defaultRootFolder);
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return fallback;
        }

        try {
            for (String rawLine : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!trimmed.startsWith("download.root-folder:") && !trimmed.startsWith("download.root-folder :")) {
                    continue;
                }

                int colonIndex = trimmed.indexOf(':');
                if (colonIndex < 0) {
                    continue;
                }

                String value = trimmed.substring(colonIndex + 1);
                int commentIndex = value.indexOf('#');
                if (commentIndex >= 0) {
                    value = value.substring(0, commentIndex);
                }

                value = stripWrappingQuotes(value.trim());
                if (!value.isBlank()) {
                    return normalizeRootFolder(value);
                }
            }
        } catch (IOException e) {
            log.warn(message(
                    "runtime.log.download-root.read-failed",
                    configPath.toAbsolutePath(),
                    e.getMessage()
            ));
        }

        return fallback;
    }

    public static String normalizeRootFolder(String rootFolder) {
        if (rootFolder == null || rootFolder.isBlank()) {
            return DEFAULT_DOWNLOAD_ROOT;
        }
        return rootFolder.replaceAll("[/\\\\]+$", "");
    }

    private static Path resolveDirectory(String propertyName, String defaultDirectory) {
        String configured = System.getProperty(propertyName, defaultDirectory);
        if (configured == null || configured.isBlank()) {
            return Path.of(defaultDirectory);
        }
        return Path.of(configured);
    }

    private static Path siblingOf(Path directory, String siblingName) {
        Path parent = directory.getParent();
        return parent == null ? Path.of(siblingName) : parent.resolve(siblingName);
    }

    private static Path resolveManagedDirectory(Path target, List<Path> legacyCandidates) {
        try {
            Files.createDirectories(target);
            for (Path legacy : legacyCandidates) {
                adoptLegacyDirectory(target, legacy);
            }
            return target.normalize();
        } catch (IOException e) {
            throw new UncheckedIOException(message("runtime.error.resolve-directory.failed", target), e);
        }
    }

    private static Path resolveManagedFile(Path target, List<Path> legacyCandidates) {
        return resolveManagedFile(target, legacyCandidates, List.of());
    }

    private static Path resolveManagedFile(Path target, List<Path> legacyCandidates, List<String> companionSuffixes) {
        try {
            Files.createDirectories(target.getParent());
            for (Path legacy : legacyCandidates) {
                adoptLegacyFile(target, legacy, companionSuffixes);
            }
            return target.normalize();
        } catch (IOException e) {
            throw new UncheckedIOException(message("runtime.error.resolve-file.failed", target), e);
        }
    }

    private static void adoptLegacyFile(Path target, Path legacy, List<String> companionSuffixes) throws IOException {
        if (legacy == null) {
            return;
        }

        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path normalizedLegacy = legacy.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedLegacy) || !Files.exists(legacy)) {
            return;
        }

        if (!Files.exists(target)) {
            Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
            moveCompanionFiles(legacy, target, companionSuffixes);
            log.info(message("runtime.log.file.migrated", normalizedLegacy, normalizedTarget));
            return;
        }

        if (Files.size(target) == Files.size(legacy) && Files.mismatch(target, legacy) == -1) {
            Files.deleteIfExists(legacy);
            deleteCompanionFiles(legacy, companionSuffixes);
            log.info(message("runtime.log.legacy-duplicate.deleted", normalizedLegacy));
            return;
        }

        Files.deleteIfExists(legacy);
        deleteCompanionFiles(legacy, companionSuffixes);
        log.warn(message("runtime.log.legacy-authoritative.deleted", normalizedLegacy, normalizedTarget));
    }

    private static void adoptLegacyDirectory(Path target, Path legacy) throws IOException {
        if (legacy == null) {
            return;
        }

        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path normalizedLegacy = legacy.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedLegacy) || !Files.exists(legacy)) {
            return;
        }
        if (!Files.isDirectory(legacy)) {
            log.warn(message("runtime.log.legacy-directory.not-directory", normalizedLegacy));
            return;
        }

        try (Stream<Path> stream = Files.walk(legacy)) {
            for (Path source : stream
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .toList()) {
                if (source.equals(legacy) || Files.isDirectory(source)) {
                    continue;
                }
                Path targetFile = target.resolve(legacy.relativize(source));
                Files.createDirectories(targetFile.getParent());
                adoptLegacyFile(targetFile, source, List.of());
            }
        }

        deleteDirectoryTree(legacy);
        log.info(message("runtime.log.directory.migrated", normalizedLegacy, normalizedTarget));
    }

    private static void deleteDirectoryTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static List<Path> rootAndDownloadLegacyCandidates(Path targetDirectory, String rootFolder, String fileName) {
        Set<Path> paths = new LinkedHashSet<>();
        addDownloadRootCandidate(paths, rootFolder, fileName);
        paths.add(legacyRootFile(targetDirectory, fileName));
        return List.copyOf(paths);
    }

    private static Path legacyRootFile(Path targetDirectory, String fileName) {
        return targetDirectory.resolveSibling(fileName);
    }

    private static void moveCompanionFiles(Path legacy, Path target, List<String> companionSuffixes) throws IOException {
        for (String suffix : companionSuffixes) {
            Path legacyCompanion = Path.of(legacy + suffix);
            if (Files.exists(legacyCompanion)) {
                Files.move(legacyCompanion, Path.of(target + suffix), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteCompanionFiles(Path legacy, List<String> companionSuffixes) throws IOException {
        for (String suffix : companionSuffixes) {
            Files.deleteIfExists(Path.of(legacy + suffix));
        }
    }

    private static void addDownloadRootCandidate(Set<Path> paths, String rootFolder, String fileName) {
        String normalizedRoot = normalizeRootFolder(rootFolder);
        if (!normalizedRoot.isBlank()) {
            paths.add(Path.of(normalizedRoot, fileName));
        }
    }

    private static String stripWrappingQuotes(String value) {
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    static Path defaultSingleInstanceDirectory(String osName,
                                               String localAppData,
                                               String userHome,
                                               String tempDir) {
        if (isWindows(osName) && localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, WINDOWS_INSTANCE_APP_DIR, INSTANCE_SUBDIR);
        }
        if (userHome != null && !userHome.isBlank()) {
            return Path.of(userHome, NON_WINDOWS_INSTANCE_APP_DIR, INSTANCE_SUBDIR);
        }
        if (tempDir != null && !tempDir.isBlank()) {
            return Path.of(tempDir, WINDOWS_INSTANCE_APP_DIR, INSTANCE_SUBDIR);
        }
        return Path.of(INSTANCE_SUBDIR);
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase().contains("win");
    }

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
