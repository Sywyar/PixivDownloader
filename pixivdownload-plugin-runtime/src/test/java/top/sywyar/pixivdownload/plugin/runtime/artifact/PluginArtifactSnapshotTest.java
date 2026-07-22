package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

}
