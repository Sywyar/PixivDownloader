package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("生产插件 artifact 私有冻结 workspace")
class PluginArtifactSnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("原路径改写不影响已冻结字节且每次冻结只清理自己的 workspace")
    void snapshotKeepsFrozenBytesInUniqueOwnedWorkspace() throws IOException {
        Path plugins = tempDir.resolve("plugins");
        Files.createDirectory(plugins);
        Path artifact = plugins.resolve("probe.jar");
        Files.writeString(artifact, "first", StandardCharsets.UTF_8);
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);

        PluginArtifactSnapshot first = PluginArtifactSnapshot.create(layout, artifact, 1024L);
        Path firstWorkspace = first.snapshotArtifact().getParent();
        Files.writeString(artifact, "second", StandardCharsets.UTF_8);
        PluginArtifactSnapshot second = PluginArtifactSnapshot.create(layout, artifact, 1024L);
        Path secondWorkspace = second.snapshotArtifact().getParent();

        assertThat(Files.readString(first.snapshotArtifact(), StandardCharsets.UTF_8)).isEqualTo("first");
        assertThat(Files.readString(second.snapshotArtifact(), StandardCharsets.UTF_8)).isEqualTo("second");
        assertThat(firstWorkspace).isNotEqualTo(secondWorkspace);

        first.close();
        assertThat(firstWorkspace).doesNotExist();
        assertThat(secondWorkspace).exists();
        second.close();
        assertThat(secondWorkspace).doesNotExist();
    }

    @Test
    @DisplayName("同一 generation 可连续创建超过旧上限的 worker 私有目录")
    void workerDirectoriesAreNotLimitedPerGeneration() throws IOException {
        Path plugins = Files.createDirectory(tempDir.resolve("plugins-worker-restarts"));
        Path artifact = plugins.resolve("probe.jar");
        Files.writeString(artifact, "probe", StandardCharsets.UTF_8);
        PluginArtifactSnapshot snapshot = PluginArtifactSnapshot.create(
                new PluginRuntimeLayout(plugins), artifact, 1024L);
        Path workspace = snapshot.snapshotArtifact().getParent();
        Set<Path> workerDirectories = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            Path workerDirectory = snapshot.createWorkerDirectory();
            assertThat(workerDirectory).isDirectory();
            assertThat(workerDirectories.add(workerDirectory)).isTrue();
        }

        snapshot.close();
        assertThat(workspace).doesNotExist();
    }

    @Test
    @DisplayName("下次会话只清理名称与目录形态合规且带有效 owner marker 的 workspace")
    void cleanupOnlyRemovesValidOwnedAbandonedWorkspaces() throws IOException {
        Path plugins = tempDir.resolve("plugins-cleanup");
        Files.createDirectory(plugins);
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);
        Path artifact = plugins.resolve("probe.jar");
        Files.writeString(artifact, "probe", StandardCharsets.UTF_8);
        PluginArtifactSnapshot owned = PluginArtifactSnapshot.create(layout, artifact, 1024L);
        Path ownedWorkspace = owned.snapshotArtifact().getParent();
        Path runtime = layout.runtimeDirectory();
        Path emptyIncomplete = Files.createDirectory(runtime.resolve(".artifact-snapshot-empty"));
        Path copiedIncomplete = Files.createDirectory(runtime.resolve(".artifact-snapshot-copied"));
        Files.writeString(copiedIncomplete.resolve("partial.jar"), "partial", StandardCharsets.UTF_8);
        Path invalidMarker = Files.createDirectory(runtime.resolve(".artifact-snapshot-invalid-marker"));
        Files.writeString(invalidMarker.resolve(".pixiv-plugin-runtime-workspace"),
                "formatVersion=1\nworkspace.name=.artifact-snapshot-other\n", StandardCharsets.UTF_8);
        Path wrongName = Files.createDirectory(runtime.resolve("manual-owned-workspace"));
        Files.writeString(wrongName.resolve(".pixiv-plugin-runtime-workspace"),
                "formatVersion=1\nworkspace.name=manual-owned-workspace\n", StandardCharsets.UTF_8);
        Path nonDirectory = runtime.resolve(".artifact-snapshot-plain-file");
        Files.writeString(nonDirectory, "not-a-directory", StandardCharsets.UTF_8);

        PluginArtifactSnapshot.cleanupAbandonedWorkspaces(layout);

        assertThat(ownedWorkspace).doesNotExist();
        assertThat(emptyIncomplete).exists();
        assertThat(copiedIncomplete).exists();
        assertThat(invalidMarker).exists();
        assertThat(wrongName).exists();
        assertThat(nonDirectory).exists();
        owned.close();
    }

    @Test
    @DisplayName("遗留 workspace 含符号链接时不跟随也不删除外部 canary")
    void cleanupRetainsWorkspaceWithSymbolicLinkCanary() throws IOException {
        Path plugins = tempDir.resolve("plugins-symlink");
        Files.createDirectory(plugins);
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);
        Path artifact = plugins.resolve("probe.jar");
        Files.writeString(artifact, "probe", StandardCharsets.UTF_8);
        PluginArtifactSnapshot snapshot = PluginArtifactSnapshot.create(layout, artifact, 1024L);
        Path canary = tempDir.resolve("outside-canary.jar");
        Files.writeString(canary, "keep", StandardCharsets.UTF_8);
        Path workspace = snapshot.snapshotArtifact().getParent();
        try {
            Files.createSymbolicLink(workspace.resolve("escape.jar"), canary);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            snapshot.close();
            Assumptions.abort("当前文件系统不能创建符号链接: " + e.getMessage());
        }

        PluginArtifactSnapshot.cleanupAbandonedWorkspaces(layout);

        assertThat(workspace).exists();
        assertThat(Files.readString(canary, StandardCharsets.UTF_8)).isEqualTo("keep");
        snapshot.close();
        assertThat(workspace).exists();
        Files.delete(workspace.resolve("escape.jar"));
        snapshot.close();
        assertThat(workspace).doesNotExist();
    }
}
