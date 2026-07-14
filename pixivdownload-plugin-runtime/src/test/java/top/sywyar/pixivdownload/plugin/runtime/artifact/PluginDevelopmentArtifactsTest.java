package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginDevelopmentArtifactsTest {

    private static final String PLUGIN_ID = "development-probe";
    private static final String PLUGIN_VERSION = "1.0.0";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("开发缓存会话为每次物化创建互不覆盖的完整快照")
    void sessionCreatesUniqueCompleteSnapshotsWithoutReplacingLegacyCache() throws IOException {
        PluginDevelopmentArtifacts.DevelopmentPluginArtifact artifact = writeArtifact();
        Path cacheRoot = tempDir.resolve("cache");
        Path legacyTarget = cacheRoot.resolve(PLUGIN_ID + "-" + PLUGIN_VERSION);
        Files.createDirectories(legacyTarget);
        Path legacyMarker = legacyTarget.resolve("last-good.marker");
        Files.writeString(legacyMarker, "legacy", StandardCharsets.UTF_8);
        PluginDevelopmentArtifacts.DevelopmentCacheSession session =
                PluginDevelopmentArtifacts.openSession(cacheRoot);

        PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin first =
                PluginDevelopmentArtifacts.materialize(artifact, session);
        PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin second =
                PluginDevelopmentArtifacts.materialize(artifact, session);

        assertThat(first.pf4jLoadPath()).isNotEqualTo(second.pf4jLoadPath());
        assertThat(first.pf4jLoadPath().getParent()).isEqualTo(session.sessionRoot());
        assertThat(second.pf4jLoadPath().getParent()).isEqualTo(session.sessionRoot());
        assertCompleteSnapshot(first.pf4jLoadPath());
        assertCompleteSnapshot(second.pf4jLoadPath());
        assertThat(legacyMarker).hasContent("legacy");

        Path sessionRoot = session.sessionRoot();
        session.close();

        assertThat(sessionRoot).doesNotExist();
        assertThat(legacyMarker).hasContent("legacy");
    }

    @Test
    @DisplayName("不同开发缓存会话互不共享快照且只清理自身目录")
    void sessionsAreIsolatedAndCleanupIsScoped() throws IOException {
        PluginDevelopmentArtifacts.DevelopmentPluginArtifact artifact = writeArtifact();
        Path cacheRoot = tempDir.resolve("cache");
        PluginDevelopmentArtifacts.DevelopmentCacheSession firstSession =
                PluginDevelopmentArtifacts.openSession(cacheRoot);
        PluginDevelopmentArtifacts.DevelopmentCacheSession secondSession =
                PluginDevelopmentArtifacts.openSession(cacheRoot);
        Path firstSnapshot = PluginDevelopmentArtifacts.materialize(artifact, firstSession).pf4jLoadPath();
        Path secondSnapshot = PluginDevelopmentArtifacts.materialize(artifact, secondSession).pf4jLoadPath();

        assertThat(firstSession.sessionRoot()).isNotEqualTo(secondSession.sessionRoot());
        assertThat(firstSnapshot).isNotEqualTo(secondSnapshot);
        assertCompleteSnapshot(firstSnapshot);
        assertCompleteSnapshot(secondSnapshot);

        firstSession.close();

        assertThat(firstSession.sessionRoot()).doesNotExist();
        assertThat(secondSession.sessionRoot()).exists();
        assertCompleteSnapshot(secondSnapshot);

        secondSession.close();
        assertThat(secondSession.sessionRoot()).doesNotExist();
    }

    @Test
    @DisplayName("开发缓存会话标记缺失时拒绝递归清理目录")
    void cleanupRejectsSessionWithoutOwnershipMarker() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        PluginDevelopmentArtifacts.DevelopmentCacheSession session =
                PluginDevelopmentArtifacts.openSession(cacheRoot);
        Files.delete(session.sessionRoot().resolve(".pixiv-plugin-dev-session"));

        assertThatThrownBy(session::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("session marker is missing");
        assertThat(session.sessionRoot()).exists();
    }

    private PluginDevelopmentArtifacts.DevelopmentPluginArtifact writeArtifact() throws IOException {
        Path moduleRoot = tempDir.resolve("pixivdownload-plugin-development-probe");
        Path classesDirectory = moduleRoot.resolve("target/classes");
        Files.createDirectories(classesDirectory.resolve("content"));
        String descriptor = "plugin.id=" + PLUGIN_ID + "\n"
                + "plugin.version=" + PLUGIN_VERSION + "\n"
                + "plugin.requires=1.0\n"
                + "plugin.class=com.example.DevelopmentProbePlugin\n"
                + "plugin.provider=test\n"
                + "plugin.description=development probe\n";
        Path descriptorPath = classesDirectory.resolve("plugin.properties");
        Files.writeString(descriptorPath, descriptor, StandardCharsets.UTF_8);
        Files.writeString(classesDirectory.resolve("content/marker.txt"), "snapshot", StandardCharsets.UTF_8);
        Path privateLibrary = classesDirectory.resolve("lib/private-lib.jar");
        Files.createDirectories(privateLibrary.getParent());
        Files.write(privateLibrary, new byte[]{1, 2, 3});
        return new PluginDevelopmentArtifacts.DevelopmentPluginArtifact(
                moduleRoot.toAbsolutePath().normalize(), classesDirectory.toAbsolutePath().normalize(),
                descriptorPath.toAbsolutePath().normalize());
    }

    private static void assertCompleteSnapshot(Path snapshot) {
        assertThat(snapshot.resolve("plugin.properties")).isRegularFile();
        assertThat(snapshot.resolve("classes/content/marker.txt")).hasContent("snapshot");
        assertThat(snapshot.resolve("lib/private-lib.jar")).hasBinaryContent(new byte[]{1, 2, 3});
        assertThat(snapshot.resolve(".pixiv-plugin-dev-snapshot")).isRegularFile();
    }
}
