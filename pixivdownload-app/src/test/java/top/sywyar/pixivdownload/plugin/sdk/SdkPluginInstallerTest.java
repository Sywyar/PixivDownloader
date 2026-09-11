package top.sywyar.pixivdownload.plugin.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SDK 项目专属宿主的正式部署入口")
class SdkPluginInstallerTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("清理要求项目归属与正式目录 lease，并保留未知工作区")
    void cleanupUsesProductionLeaseAndWorkspaceOwnership() throws Exception {
        Path project = Files.createDirectories(temp.resolve("cleanup-project"));
        Path run = Files.createDirectories(project.resolve(".dev/runs/old"));
        Path plugins = Files.createDirectories(run.resolve("plugins"));
        assertThatThrownBy(() -> SdkPluginInstaller.cleanup(project, run))
                .isInstanceOf(IOException.class).hasMessageContaining("SDK_CLEANUP_OWNER");
        Files.writeString(run.resolve(".sdk-run"), "owned", StandardCharsets.UTF_8);
        Path unknown = Files.createDirectories(plugins.resolve("runtime/.artifact-snapshot-unknown"));
        Files.writeString(unknown.resolve("keep.txt"), "unowned", StandardCharsets.UTF_8);
        try (ExternalPluginInstaller active = new ExternalPluginInstaller(plugins)) {
            assertThat(active.recoverPendingTransactions().safeToScan()).isTrue();
            assertThatThrownBy(() -> SdkPluginInstaller.cleanup(project, run))
                    .isInstanceOf(IOException.class).hasMessageContaining("SDK_INSTALL_RECOVERY");
        }
        SdkPluginInstaller.cleanup(project, run);
        assertThat(unknown.resolve("keep.txt")).hasContent("unowned");
        assertThatThrownBy(() -> SdkPluginInstaller.cleanup(project, temp))
                .isInstanceOf(IOException.class).hasMessageContaining("SDK_INSTALL_WORKSPACE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"host-process-full-trust", "declarative-process"})
    @DisplayName("每次新运行绑定当前字节和声明模式，旧摘要与原目录均不能覆盖已有包")
    void deploysCurrentArtifactWithoutChangingExecutionMode(String mode) throws Exception {
        Path project = Files.createDirectories(temp.resolve("中文 插件"));
        Path run = Files.createDirectories(project.resolve(".dev/runs/first"));
        Files.createDirectories(run.resolve("plugins"));
        Path jar = project.resolve("current.jar");
        writeCandidate(jar, mode, "first build");
        String firstSha = PluginPackageIntegrity.sha256Hex(jar);
        var first = SdkPluginInstaller.install(project, run, jar, firstSha);
        assertThat(first.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(first.descriptor().executionMode().descriptorValue()).isEqualTo(mode);
        var provenance = new PluginProvenanceStore(run.resolve("plugins"))
                .readRequiredForRecovery(first.installedPath());
        assertThat(provenance.developmentOnly()).isFalse();
        assertThat(provenance.officialRepository()).isFalse();
        assertThat(provenance.artifactSha256()).isEqualTo(firstSha);
        byte[] installed = Files.readAllBytes(first.installedPath());

        writeCandidate(jar, mode, "second build");
        String currentSha = PluginPackageIntegrity.sha256Hex(jar);
        assertThat(currentSha).isNotEqualTo(firstSha);
        assertThat(SdkPluginInstaller.install(project, run, jar, currentSha).outcome())
                .isEqualTo(PluginInstallOutcome.REJECTED_INVALID);
        assertThat(Files.readAllBytes(first.installedPath())).isEqualTo(installed);

        Path next = Files.createDirectories(project.resolve(".dev/runs/second"));
        Files.createDirectories(next.resolve("plugins"));
        assertThat(SdkPluginInstaller.install(project, next, jar, firstSha).outcome())
                .isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);
        var current = SdkPluginInstaller.install(project, next, jar, currentSha);
        assertThat(current.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(PluginPackageIntegrity.sha256Hex(current.installedPath())).isEqualTo(currentSha);
        assertThat(current.descriptor().executionMode().descriptorValue()).isEqualTo(mode);
    }

    @Test
    @DisplayName("部署拒绝日常应用目录、开发缓存产物及不完整摘要")
    void rejectsUnrelatedDirectoriesAndStaleArtifacts() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path run = Files.createDirectories(project.resolve(".dev/runs/current"));
        Files.createDirectories(run.resolve("plugins"));
        Path jar = project.resolve("current.jar");
        writeCandidate(jar, "host-process-full-trust", "current");
        String digest = PluginPackageIntegrity.sha256Hex(jar);
        assertThatThrownBy(() -> SdkPluginInstaller.install(project, temp, jar, digest))
                .isInstanceOf(IOException.class).hasMessageContaining("SDK_INSTALL_WORKSPACE");
        Path stale = Files.copy(jar, run.resolve("stale.jar"));
        assertThatThrownBy(() -> SdkPluginInstaller.install(project, run, stale, digest))
                .isInstanceOf(IOException.class).hasMessageContaining("SDK_INSTALL_ARTIFACT_LOCATION");
        assertThatThrownBy(() -> SdkPluginInstaller.install(project, run, jar, "invalid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SDK_INSTALL_IDENTITY");
    }

    private static void writeCandidate(Path jar, String mode, String content) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("plugin.properties"));
            zip.write(("plugin.id=my-plugin\nplugin.version=" + SdkVersion.VERSION
                    + "\nplugin.requires=" + SdkVersion.MAJOR + "." + SdkVersion.MINOR + "\n"
                    + "plugin.class=example.Plugin\npixiv.kind=feature\npixiv.execution-mode=" + mode + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("build.txt"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
