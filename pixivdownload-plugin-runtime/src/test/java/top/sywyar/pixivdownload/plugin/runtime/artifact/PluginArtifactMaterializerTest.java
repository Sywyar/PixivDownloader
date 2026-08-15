package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageFormat;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("生产插件 artifact 从冻结 snapshot 物化")
class PluginArtifactMaterializerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("原路径在冻结后被替换时私有依赖目录仍只来自冻结字节")
    void materializesPrivateLibraryJarOnlyFromFrozenSnapshot() throws IOException {
        Path plugins = tempDir.resolve("plugins");
        Files.createDirectory(plugins);
        Path artifact = plugins.resolve("probe.jar");
        byte[] privateLibrary = PluginPackageFixtures.zipBytes(
                Map.of("private/Marker.txt", "first".getBytes(StandardCharsets.UTF_8)));
        Files.write(artifact, PluginPackageFixtures.pluginJarBytes(
                "probe", "1.0.0", "1.0", "com.example.Probe",
                Map.of("lib/private-lib.jar", privateLibrary)));
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);
        PluginArtifactSnapshot snapshot = PluginArtifactSnapshot.create(
                layout, artifact, PluginPackageLimits.DEFAULT_MAX_ARCHIVE_BYTES);
        Path workspace = snapshot.snapshotArtifact().getParent();
        PluginPackageInspection inspection = PluginPackageReader.inspect(
                snapshot.snapshotArtifact(), PluginPackageLimits.defaults());
        String verifiedSha256 = PluginPackageIntegrity.sha256Hex(snapshot.snapshotArtifact());
        Files.write(artifact, PluginPackageFixtures.pluginJarBytes(
                "replaced", "9.9.9", "1.0", "com.example.Replaced"));

        PluginArtifactMaterializer.MaterializedPluginArtifact materialized =
                new PluginArtifactMaterializer(layout).materialize(snapshot, inspection, verifiedSha256);

        assertThat(materialized.originalArtifactPath()).isEqualTo(artifact.toAbsolutePath().normalize());
        assertThat(materialized.materialized()).isTrue();
        assertThat(materialized.pf4jLoadPath().getParent()).isEqualTo(workspace);
        assertThat(Files.readString(materialized.pf4jLoadPath().resolve("plugin.properties"),
                StandardCharsets.UTF_8)).contains("plugin.id=probe").doesNotContain("plugin.id=replaced");
        assertThat(materialized.pf4jLoadPath().resolve("lib/private-lib.jar"))
                .hasBinaryContent(privateLibrary);

        snapshot.close();
        assertThat(workspace).doesNotExist();
    }

    @Test
    @DisplayName("物化器复用归档唯一名称规则并拒绝重复 entry")
    void rejectsDuplicateEntriesDuringMaterialization() throws IOException {
        Path plugins = tempDir.resolve("duplicate-plugins");
        Files.createDirectory(plugins);
        Path reference = PluginPackageFixtures.bareJar(tempDir.resolve("reference.jar"),
                "probe", "1.0.0", "1.0", "com.example.Probe");
        PluginPackageInspection referenceInspection = PluginPackageReader.inspect(reference);
        Path artifact = plugins.resolve("probe.jar");
        PluginPackageFixtures.writeDuplicateEntryZip(artifact, "plugin.properties",
                "plugin.id=first\n".getBytes(StandardCharsets.UTF_8),
                "plugin.id=second\n".getBytes(StandardCharsets.UTF_8));
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);
        PluginArtifactSnapshot snapshot = PluginArtifactSnapshot.create(
                layout, artifact, PluginPackageLimits.DEFAULT_MAX_ARCHIVE_BYTES);
        PluginPackageInspection inspection = new PluginPackageInspection(
                PluginPackageFormat.SINGLE_JAR, referenceInspection.descriptor(), null, true);

        try {
            assertThatThrownBy(() -> new PluginArtifactMaterializer(layout).materialize(
                    snapshot, inspection, PluginPackageIntegrity.sha256Hex(snapshot.snapshotArtifact())))
                    .isInstanceOfSatisfying(PluginPackageException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(PluginPackageException.Reason.UNSAFE));
        } finally {
            snapshot.close();
        }
    }
}
