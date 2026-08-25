package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("生产插件 artifact workspace 所有权")
class PluginArtifactWorkspaceOwnerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("最后一个已确认引用释放后删除 workspace")
    void releasesWorkspaceAfterLastConfirmedReference() throws IOException {
        Path plugins = Files.createDirectory(tempDir.resolve("plugins-release"));
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);
        PluginArtifactSnapshot snapshot = snapshot(layout, plugins.resolve("probe.jar"));
        Path workspace = snapshot.snapshotArtifact().getParent();
        PluginArtifactWorkspaceOwner owner = new PluginArtifactWorkspaceOwner(layout);

        owner.release(snapshot, List.of(snapshot));
        assertThat(workspace).exists();

        owner.release(snapshot, List.of());
        assertThat(workspace).doesNotExist();
    }

    @Test
    @DisplayName("未确认或未干净释放的 workspace 保留并阻止遗留清理")
    void retainsWorkspaceWhenReleaseIsUnconfirmed() throws IOException {
        Path plugins = Files.createDirectory(tempDir.resolve("plugins-retain"));
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);
        PluginArtifactSnapshot unconfirmed = snapshot(layout, plugins.resolve("unconfirmed.jar"));
        PluginArtifactSnapshot dirtyRuntime = snapshot(layout, plugins.resolve("dirty-runtime.jar"));
        Path unconfirmedWorkspace = unconfirmed.snapshotArtifact().getParent();
        Path dirtyWorkspace = dirtyRuntime.snapshotArtifact().getParent();
        PluginArtifactWorkspaceOwner owner = new PluginArtifactWorkspaceOwner(layout);

        owner.retainUnconfirmed(unconfirmed);
        owner.closeAll(List.of(unconfirmed, dirtyRuntime), false, "test");
        owner.cleanupAbandoned(true);

        assertThat(unconfirmedWorkspace).exists();
        assertThat(dirtyWorkspace).exists();
        unconfirmed.close();
        dirtyRuntime.close();
    }

    private static PluginArtifactSnapshot snapshot(PluginRuntimeLayout layout, Path artifact) throws IOException {
        Files.writeString(artifact, "probe", StandardCharsets.UTF_8);
        return PluginArtifactSnapshot.create(layout, artifact, 1024L);
    }
}
