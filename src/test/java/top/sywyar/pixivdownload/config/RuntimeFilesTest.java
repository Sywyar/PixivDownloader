package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY);
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

        assertThat(resolved).isEqualTo(Path.of("C:\\Users\\tester\\AppData\\Local", "PixivDownload", "run"));
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
