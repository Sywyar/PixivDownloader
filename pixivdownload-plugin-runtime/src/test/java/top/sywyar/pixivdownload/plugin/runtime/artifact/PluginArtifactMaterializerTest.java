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
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    @Test
    @DisplayName("PF4J 准入前拒绝物化树中新增的文件")
    void rejectsMaterializedTreeMutationBeforePf4jAdmission() throws IOException {
        Path plugins = tempDir.resolve("manifest-plugins");
        Files.createDirectory(plugins);
        Path artifact = plugins.resolve("probe.jar");
        Files.write(artifact, PluginPackageFixtures.pluginJarBytes(
                "probe", "1.0.0", "1.0", "com.example.Probe",
                Map.of("lib/private-lib.jar", PluginPackageFixtures.zipBytes(
                        Map.of("private/Marker.txt", "first".getBytes(StandardCharsets.UTF_8))))));
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);
        PluginArtifactSnapshot snapshot = PluginArtifactSnapshot.create(
                layout, artifact, PluginPackageLimits.DEFAULT_MAX_ARCHIVE_BYTES);
        Path workspace = snapshot.snapshotArtifact().getParent();
        try {
            PluginPackageInspection inspection = PluginPackageReader.inspect(
                    snapshot.snapshotArtifact(), PluginPackageLimits.defaults());
            PluginArtifactMaterializer.MaterializedPluginArtifact materialized =
                    new PluginArtifactMaterializer(layout).materialize(snapshot, inspection,
                            PluginPackageIntegrity.sha256Hex(snapshot.snapshotArtifact()));
            snapshot.verifyLoadPath(materialized.pf4jLoadPath());

            PluginRuntimeFileSecurity.makeTreeWritable(
                    workspace, PluginRuntimeFileSecurity.owner(workspace));
            Files.writeString(materialized.pf4jLoadPath().resolve("injected.class"),
                    "unverified", StandardCharsets.UTF_8);

            assertThatThrownBy(() -> snapshot.verifyLoadPath(materialized.pf4jLoadPath()))
                    .isInstanceOf(PluginRuntimeOperationException.class)
                    .hasMessageContaining("load tree changed");
        } finally {
            snapshot.close();
        }
    }

    @Test
    @DisplayName("包条目预算内的隐式目录仍受实际物化路径预算约束")
    void rejectsImplicitDirectoriesBeyondWorkspaceEntryBudget() throws IOException {
        Path plugins = Files.createDirectory(tempDir.resolve("entry-budget-plugins"));
        Path artifact = plugins.resolve("probe.zip");
        int implicitDirectoryCount = 5_000;
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PluginPackageFixtures.PLUGIN_PROPERTIES, PluginPackageFixtures.bytes(
                PluginPackageFixtures.pluginProperties(
                        "probe", "1.0.0", "1.0", "com.example.Probe")));
        entries.put("classes/", new byte[0]);
        int fileEntryCount = PluginPackageLimits.DEFAULT_MAX_ENTRIES - entries.size();
        for (int index = 0; index < fileEntryCount; index++) {
            entries.put("classes/group-" + index % implicitDirectoryCount + "/Marker-" + index + ".class",
                    new byte[]{1});
        }
        PluginPackageFixtures.writeZip(artifact, entries);
        PluginPackageLimits limits = PluginPackageLimits.defaults();
        PluginPackageVerifier.VerificationUsage usage =
                PluginPackageVerifier.verifyAndMeasure(artifact, limits);
        PluginPackageInspection inspection = PluginPackageReader.inspect(artifact, limits);
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);
        PluginArtifactSnapshot snapshot = PluginArtifactSnapshot.create(
                layout, artifact, limits.maxArchiveBytes());
        Path workspace = snapshot.snapshotArtifact().getParent();
        Path loadPath = workspace.resolve("load");

        try {
            assertThat(usage.entryCount()).isEqualTo(PluginPackageLimits.DEFAULT_MAX_ENTRIES);
            assertThatThrownBy(() -> new PluginArtifactMaterializer(layout).materialize(
                    snapshot, inspection, PluginPackageIntegrity.sha256Hex(snapshot.snapshotArtifact())))
                    .isInstanceOf(PluginRuntimeOperationException.class)
                    .hasRootCauseMessage("plugin artifact load tree exceeds the supported entry count");
            try (var paths = Files.walk(loadPath)) {
                assertThat(paths.count())
                        .isEqualTo((long) usage.entryCount() + implicitDirectoryCount + 1L)
                        .isEqualTo(25_001L);
            }
        } finally {
            deleteTree(workspace);
            snapshot.close();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
