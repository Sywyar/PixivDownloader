package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.common.AppInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("RuntimeFiles path migration tests")
class RuntimeFilesTest {

    @TempDir
    Path tempDir;

    private Path configDir;
    private Path stateDir;
    private Path dataDir;
    private Path downloadRoot;

    @BeforeEach
    void setUp() throws IOException {
        configDir = tempDir.resolve("config");
        stateDir = tempDir.resolve("state");
        dataDir = tempDir.resolve("data");
        downloadRoot = tempDir.resolve("pixiv-download");
        Files.createDirectories(downloadRoot);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, configDir.toString());
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, stateDir.toString());
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, dataDir.toString());
        System.clearProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @Test
    @DisplayName("should migrate root config.yaml into config directory")
    void shouldMigrateRootConfigFile() throws IOException {
        Path legacyConfig = tempDir.resolve(RuntimeFiles.CONFIG_YAML);
        Files.writeString(legacyConfig, "download.root-folder: custom-download\n", StandardCharsets.UTF_8);

        Path resolved = RuntimeFiles.resolveConfigYamlPath();

        assertThat(resolved).isEqualTo(configDir.resolve(RuntimeFiles.CONFIG_YAML));
        assertThat(resolved).exists();
        assertThat(Files.readString(resolved, StandardCharsets.UTF_8)).contains("custom-download");
        assertThat(legacyConfig).doesNotExist();
        assertThat(RuntimeFiles.readDownloadRootFromConfig(resolved, RuntimeFiles.DEFAULT_DOWNLOAD_ROOT))
                .isEqualTo("custom-download");
    }

    @Test
    @DisplayName("should migrate legacy download files into state and data directories")
    void shouldMigrateLegacyDownloadFiles() throws IOException {
        Path legacySetup = downloadRoot.resolve(RuntimeFiles.SETUP_CONFIG_JSON);
        Path legacyBatch = downloadRoot.resolve(RuntimeFiles.BATCH_STATE_JSON);
        Path legacyDb = downloadRoot.resolve(RuntimeFiles.PIXIV_DOWNLOAD_DB);
        Path legacyDbWal = Path.of(legacyDb + "-wal");
        Path legacyDbShm = Path.of(legacyDb + "-shm");
        Files.writeString(legacySetup, "{\"mode\":\"solo\"}", StandardCharsets.UTF_8);
        Files.writeString(legacyBatch, "{\"page\":1}", StandardCharsets.UTF_8);
        Files.writeString(legacyDb, "sqlite-placeholder", StandardCharsets.UTF_8);
        Files.writeString(legacyDbWal, "wal", StandardCharsets.UTF_8);
        Files.writeString(legacyDbShm, "shm", StandardCharsets.UTF_8);

        Path setup = RuntimeFiles.resolveSetupConfigPath(downloadRoot.toString());
        Path batch = RuntimeFiles.resolveBatchStatePath(downloadRoot.toString());
        Path db = RuntimeFiles.resolveDatabasePath(downloadRoot.toString());

        assertThat(setup).isEqualTo(stateDir.resolve(RuntimeFiles.SETUP_CONFIG_JSON));
        assertThat(batch).isEqualTo(stateDir.resolve(RuntimeFiles.BATCH_STATE_JSON));
        assertThat(db).isEqualTo(dataDir.resolve(RuntimeFiles.PIXIV_DOWNLOAD_DB));
        assertThat(setup).exists();
        assertThat(batch).exists();
        assertThat(db).exists();
        assertThat(legacySetup).doesNotExist();
        assertThat(legacyBatch).doesNotExist();
        assertThat(legacyDb).doesNotExist();
        assertThat(Path.of(db + "-wal")).exists();
        assertThat(Path.of(db + "-shm")).exists();
        assertThat(legacyDbWal).doesNotExist();
        assertThat(legacyDbShm).doesNotExist();
    }

    @Test
    @DisplayName("should migrate legacy root runtime files into config directory")
    void shouldMigrateLegacyRootRuntimeFiles() throws IOException {
        Path target = configDir.resolve(RuntimeFiles.IMAGE_CLASSIFIER_PROPERTIES);
        Path legacy = tempDir.resolve(RuntimeFiles.IMAGE_CLASSIFIER_PROPERTIES);
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "from-root=true\n", StandardCharsets.UTF_8);

        Path resolved = RuntimeFiles.resolveImageClassifierPath(downloadRoot.toString());

        assertThat(resolved).isEqualTo(target);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).contains("from-root=true");
        assertThat(legacy).doesNotExist();
    }

    @Test
    @DisplayName("should keep new directory copy when both new and legacy files exist")
    void shouldKeepNewDirectoryCopyWhenBothExist() throws IOException {
        Path target = stateDir.resolve(RuntimeFiles.SETUP_CONFIG_JSON);
        Path legacy = downloadRoot.resolve(RuntimeFiles.SETUP_CONFIG_JSON);
        Files.createDirectories(target.getParent());
        Files.createDirectories(legacy.getParent());
        Files.writeString(target, "{\"mode\":\"new\"}", StandardCharsets.UTF_8);
        Files.writeString(legacy, "{\"mode\":\"legacy\"}", StandardCharsets.UTF_8);

        Path resolved = RuntimeFiles.resolveSetupConfigPath(downloadRoot.toString());

        assertThat(resolved).isEqualTo(target);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).contains("\"new\"");
        assertThat(legacy).doesNotExist();
    }

    @Test
    @DisplayName("应迁移旧辅助目录到 data 与 state 下的新目录")
    void shouldMigrateLegacyAuxiliaryDirectories() throws IOException {
        Path legacyCollectionIcons = tempDir.resolve(RuntimeFiles.COLLECTION_ICONS_DIR);
        Path legacyUnderscoreCollectionIcons = tempDir.resolve("_collection_icons");
        Path legacyGui = tempDir.resolve("_gui");
        Path legacyTts = tempDir.resolve("_tts");
        Files.createDirectories(legacyCollectionIcons);
        Files.createDirectories(legacyUnderscoreCollectionIcons);
        Files.createDirectories(legacyGui);
        Files.createDirectories(legacyTts);
        Files.writeString(legacyCollectionIcons.resolve("1.png"), "root-icon", StandardCharsets.UTF_8);
        Files.writeString(legacyUnderscoreCollectionIcons.resolve("2.webp"), "old-icon", StandardCharsets.UTF_8);
        Files.writeString(legacyGui.resolve("onboarding-seen"), "1", StandardCharsets.UTF_8);
        Files.writeString(
                legacyTts.resolve(RuntimeFiles.EDGE_TTS_CHROMIUM_VERSION),
                "148.0.3967.70",
                StandardCharsets.UTF_8);

        Path collectionIcons = RuntimeFiles.collectionIconsDirectory();
        Path guiState = RuntimeFiles.guiStateDirectory();
        Path ttsVersion = RuntimeFiles.resolveEdgeTtsVersionPath();

        assertThat(collectionIcons).isEqualTo(dataDir.resolve(RuntimeFiles.COLLECTION_ICONS_DIR));
        assertThat(guiState).isEqualTo(stateDir.resolve(RuntimeFiles.GUI_STATE_DIR));
        assertThat(ttsVersion)
                .isEqualTo(dataDir.resolve(RuntimeFiles.TTS_DIR).resolve(RuntimeFiles.EDGE_TTS_CHROMIUM_VERSION));
        assertThat(collectionIcons.resolve("1.png")).exists();
        assertThat(collectionIcons.resolve("2.webp")).exists();
        assertThat(guiState.resolve("onboarding-seen")).exists();
        assertThat(ttsVersion).exists();
        assertThat(legacyCollectionIcons).doesNotExist();
        assertThat(legacyUnderscoreCollectionIcons).doesNotExist();
        assertThat(legacyGui).doesNotExist();
        assertThat(legacyTts).doesNotExist();
    }

    @Test
    @DisplayName("图库缩略图缓存目录应使用不带下划线的新 data 路径")
    void shouldResolveGalleryThumbnailDirectoryWithoutLegacyMigration() throws IOException {
        Path legacyGalleryThumbnails = dataDir.resolve("_gallery_thumbs");
        Files.createDirectories(legacyGalleryThumbnails);

        Path resolved = RuntimeFiles.galleryThumbnailDirectory();

        assertThat(resolved).isEqualTo(dataDir.resolve(RuntimeFiles.GALLERY_THUMBNAILS_DIR));
        assertThat(legacyGalleryThumbnails).exists();
    }

    @Test
    @DisplayName("启动恢复应据恢复清单把中断删除中缺失的原文件复制回原位并清理暂存子目录")
    void shouldRecoverInterruptedDeletionFromManifest() throws IOException {
        Path stagingRoot = RuntimeFiles.deleteStagingDirectory();
        Path subdir = Files.createDirectories(stagingRoot.resolve("crashed-op"));
        // 模拟「已暂存、原文件已删、未来得及回滚 / 软删」：原文件缺失，暂存副本仍在，清单记录映射
        Path original = downloadRoot.resolve("300").resolve("300_p0.jpg");
        Files.writeString(subdir.resolve("0_300_p0.jpg"), "p0-bytes", StandardCharsets.UTF_8);
        DeleteStagingManifest.write(subdir, List.of(new DeleteStagingManifest.Entry(
                original.toAbsolutePath().normalize(), "0_300_p0.jpg")));

        RuntimeFiles.recoverDeleteStagingLeftovers();

        assertThat(original).exists();
        assertThat(Files.readString(original, StandardCharsets.UTF_8)).isEqualTo("p0-bytes");
        assertThat(subdir).doesNotExist();
        assertThat(stagingRoot).isEqualTo(dataDir.resolve(RuntimeFiles.DELETE_STAGING_DIR));
        assertThat(stagingRoot).isDirectory();
    }

    @Test
    @DisplayName("启动恢复对清单缺失 / 损坏的暂存子目录一律保留，不删除唯一备份")
    void shouldKeepLeftoverWhenManifestMissingOrCorrupt() throws IOException {
        Path stagingRoot = RuntimeFiles.deleteStagingDirectory();
        Path noManifest = Files.createDirectories(stagingRoot.resolve("no-manifest"));
        Files.writeString(noManifest.resolve("0_a.jpg"), "a", StandardCharsets.UTF_8);
        Path corrupt = Files.createDirectories(stagingRoot.resolve("corrupt"));
        Files.writeString(corrupt.resolve("0_b.jpg"), "b", StandardCharsets.UTF_8);
        Files.writeString(corrupt.resolve("manifest.properties"), "count=oops\n", StandardCharsets.UTF_8);

        RuntimeFiles.recoverDeleteStagingLeftovers();

        assertThat(noManifest).isDirectory();
        assertThat(noManifest.resolve("0_a.jpg")).exists();
        assertThat(corrupt).isDirectory();
        assertThat(corrupt.resolve("0_b.jpg")).exists();
        assertThat(stagingRoot).isDirectory();
    }

    @Test
    @DisplayName("插件目录默认为工作目录下 plugins、可经系统属性覆盖，且只解析不创建")
    void shouldResolvePluginsDirectoryWithoutCreating() {
        assertThat(RuntimeFiles.pluginsDirectory()).isEqualTo(Path.of(RuntimeFiles.DEFAULT_PLUGINS_DIR));

        Path customPlugins = tempDir.resolve("custom-plugins");
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, customPlugins.toString());

        assertThat(RuntimeFiles.pluginsDirectory()).isEqualTo(customPlugins);
        // 只解析路径、不创建目录（缺失诊断由运行时骨架报告，目录创建归后续安装流程）
        assertThat(customPlugins).doesNotExist();
    }

    @Test
    @DisplayName("插件自有配置路径应落在 config/plugins 下并创建目录")
    void shouldResolvePluginConfigPathUnderConfigPlugins() {
        Path resolved = RuntimeFiles.resolvePluginConfigPath("douyin", "properties");

        assertThat(resolved).isEqualTo(configDir.resolve("plugins").resolve("douyin.properties"));
        assertThat(resolved.getParent()).isDirectory();
    }

    @Test
    @DisplayName("插件数据目录应按 owner 落在 data 下并拒绝路径穿越")
    void shouldResolveOwnerScopedPluginDataDirectorySafely() {
        Path resolved = RuntimeFiles.resolvePluginDataDirectory("douyin");

        assertThat(resolved).isEqualTo(dataDir.resolve("douyin"));
        assertThat(resolved).isDirectory();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeFiles.resolvePluginDataDirectory("../escape"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeFiles.resolvePluginDataDirectory("nested/path"));
        assertThat(tempDir.resolve("escape")).doesNotExist();
    }

    @Test
    @DisplayName("参考音文件应留在花名册目录并拒绝不安全扩展名")
    void shouldResolveNarrationVoiceFileSafely() {
        Path castDirectory = RuntimeFiles.narrationVoiceCastDirectory(7L).normalize();

        assertThat(RuntimeFiles.narrationVoiceFile(7L, 3, " WAV "))
                .isEqualTo(castDirectory.resolve("3.wav"));
        assertThat(RuntimeFiles.narrationVoiceFile(7L, 3, null))
                .isEqualTo(castDirectory.resolve("3.wav"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeFiles.narrationVoiceFile(7L, 3, "../../../escape"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeFiles.narrationVoiceFile(7L, 3, "mp3.tmp"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeFiles.narrationVoiceFile(7L, 3, "wav/child"));
    }

    @Test
    @DisplayName("插件状态目录应按 owner 落在 state 下并拒绝路径穿越")
    void shouldResolveOwnerScopedPluginStateDirectorySafely() {
        Path resolved = RuntimeFiles.resolvePluginStateDirectory("download-workbench");

        assertThat(resolved).isEqualTo(stateDir.resolve("download-workbench"));
        assertThat(resolved).isDirectory();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeFiles.resolvePluginStateDirectory("../escape"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeFiles.resolvePluginStateDirectory("nested/state"));
        assertThat(tempDir.resolve("escape")).doesNotExist();
    }

    @Test
    @DisplayName("插件凭证路径应按 owner 落在独立 credentials 目录")
    void shouldResolveOwnerScopedPluginCredentialPath() {
        Path resolved = RuntimeFiles.resolvePluginCredentialPath("fixture");

        assertThat(resolved).isEqualTo(RuntimeFiles.configDirectory()
                .resolve(RuntimeFiles.PLUGIN_CREDENTIAL_DIR)
                .resolve("fixture.properties")
                .normalize());
    }

    @Test
    @DisplayName("should prefer explicitly configured single-instance directory")
    void shouldPreferConfiguredSingleInstanceDirectory() {
        Path instanceDir = tempDir.resolve("instance");
        System.setProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY, instanceDir.toString());

        assertThat(RuntimeFiles.singleInstanceDirectory()).isEqualTo(instanceDir);
    }

    @Test
    @DisplayName("should resolve Windows single-instance directory under LOCALAPPDATA")
    void shouldResolveWindowsSingleInstanceDirectoryFromLocalAppData() {
        Path resolved = RuntimeFiles.defaultSingleInstanceDirectory(
                "Windows 11",
                "C:\\Users\\tester\\AppData\\Local",
                "C:\\Users\\tester",
                "C:\\Temp");

        assertThat(resolved).isEqualTo(Path.of("C:\\Users\\tester\\AppData\\Local", AppInfo.LEGACY_ARTIFACT_NAME, "run"));
    }

    @Test
    @DisplayName("should resolve non-Windows single-instance directory under user home")
    void shouldResolveNonWindowsSingleInstanceDirectoryFromUserHome() {
        Path resolved = RuntimeFiles.defaultSingleInstanceDirectory(
                "Linux",
                null,
                "/home/tester",
                "/tmp");

        assertThat(resolved).isEqualTo(Path.of("/home/tester", ".pixivdownload", "run"));
    }
}
