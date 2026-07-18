package top.sywyar.pixivdownload.download.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("下载工作台状态文件归属")
class BatchStateFilesTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("批量状态使用下载工作台自有 state 目录")
    void resolvesStateFileUnderOwnerDirectory() {
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        DownloadSettings downloadSettings = mock(DownloadSettings.class);
        Path ownerDirectory = tempDir.resolve("state/download-workbench");
        when(runtimePathProvider.resolvePluginStateDirectory(DownloadWorkbenchPlugin.ID))
                .thenReturn(ownerDirectory);
        when(downloadSettings.getRootFolder()).thenReturn(tempDir.resolve("downloads").toString());

        BatchStateFiles files = new BatchStateFiles(runtimePathProvider, downloadSettings);

        assertThat(files.stateFile()).isEqualTo(ownerDirectory.resolve("batch_state.json"));
        verify(runtimePathProvider).resolvePluginStateDirectory(DownloadWorkbenchPlugin.ID);
    }

    @Test
    @DisplayName("迁移旧 state 文件且保留其它冲突副本并可重复执行")
    void migratesLegacyStateWithoutDiscardingConflictingCopies() throws IOException {
        Path ownerDirectory = tempDir.resolve("state/download-workbench");
        Path oldState = tempDir.resolve("state/batch_state.json");
        Path oldWorking = tempDir.resolve("batch_state.json");
        Path downloadRoot = tempDir.resolve("downloads");
        Path oldDownload = downloadRoot.resolve("batch_state.json");
        Files.createDirectories(oldState.getParent());
        Files.createDirectories(downloadRoot);
        Files.writeString(oldState, "from-state", StandardCharsets.UTF_8);
        Files.writeString(oldWorking, "from-working", StandardCharsets.UTF_8);
        Files.writeString(oldDownload, "from-download", StandardCharsets.UTF_8);

        BatchStateFiles first = createFiles(ownerDirectory, downloadRoot);
        BatchStateFiles second = createFiles(ownerDirectory, downloadRoot);

        assertThat(first.stateFile()).isEqualTo(ownerDirectory.resolve("batch_state.json"));
        assertThat(second.stateFile()).isEqualTo(first.stateFile());
        assertThat(Files.readString(first.stateFile(), StandardCharsets.UTF_8)).isEqualTo("from-state");
        assertThat(oldState).doesNotExist();
        assertThat(Files.readString(oldWorking, StandardCharsets.UTF_8)).isEqualTo("from-working");
        assertThat(Files.readString(oldDownload, StandardCharsets.UTF_8)).isEqualTo("from-download");
    }

    @Test
    @DisplayName("旧工作目录状态文件在没有 canonical 文件时会被迁移")
    void migratesLegacyWorkingDirectoryFile() throws IOException {
        Path ownerDirectory = tempDir.resolve("state/download-workbench");
        Path legacy = tempDir.resolve("batch_state.json");
        Files.writeString(legacy, "from-working", StandardCharsets.UTF_8);

        Path resolved = createFiles(ownerDirectory, tempDir.resolve("downloads")).stateFile();

        assertThat(Files.readString(resolved, StandardCharsets.UTF_8)).isEqualTo("from-working");
        assertThat(legacy).doesNotExist();
    }

    @Test
    @DisplayName("旧下载根状态文件在没有 canonical 文件时会被迁移")
    void migratesLegacyDownloadRootFile() throws IOException {
        Path ownerDirectory = tempDir.resolve("state/download-workbench");
        Path downloadRoot = tempDir.resolve("downloads");
        Path legacy = downloadRoot.resolve("batch_state.json");
        Files.createDirectories(downloadRoot);
        Files.writeString(legacy, "from-download", StandardCharsets.UTF_8);

        Path resolved = createFiles(ownerDirectory, downloadRoot).stateFile();

        assertThat(Files.readString(resolved, StandardCharsets.UTF_8)).isEqualTo("from-download");
        assertThat(legacy).doesNotExist();
    }

    @Test
    @DisplayName("canonical 与旧文件冲突时保留两份数据")
    void keepsDivergentCanonicalAndLegacyFiles() throws IOException {
        Path ownerDirectory = tempDir.resolve("state/download-workbench");
        Path canonical = ownerDirectory.resolve("batch_state.json");
        Path legacy = tempDir.resolve("state/batch_state.json");
        Files.createDirectories(ownerDirectory);
        Files.writeString(canonical, "canonical", StandardCharsets.UTF_8);
        Files.writeString(legacy, "legacy", StandardCharsets.UTF_8);

        Path resolved = createFiles(ownerDirectory, tempDir.resolve("downloads")).stateFile();

        assertThat(Files.readString(resolved, StandardCharsets.UTF_8)).isEqualTo("canonical");
        assertThat(Files.readString(legacy, StandardCharsets.UTF_8)).isEqualTo("legacy");
    }

    private BatchStateFiles createFiles(Path ownerDirectory, Path downloadRoot) {
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        DownloadSettings downloadSettings = mock(DownloadSettings.class);
        when(runtimePathProvider.resolvePluginStateDirectory(DownloadWorkbenchPlugin.ID))
                .thenReturn(ownerDirectory);
        when(downloadSettings.getRootFolder()).thenReturn(downloadRoot.toString());
        return new BatchStateFiles(runtimePathProvider, downloadSettings);
    }
}
