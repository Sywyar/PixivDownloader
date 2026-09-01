package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件 artifact 有界扫描器")
class PluginArtifactScannerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("安装根直接 entry 超出总上限时拒绝部分清点")
    void rejectsExcessiveRootEntries() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins"));
        for (int index = 0; index <= PluginArtifactScanner.MAX_ROOT_ENTRIES; index++) {
            Files.createFile(plugins.resolve("entry-" + index + ".txt"));
        }

        assertThatThrownBy(() -> PluginArtifactScanner.scan(plugins))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("entry count");
    }

    @Test
    @DisplayName("可见 jar 名称若是目录或符号链接只拒绝该候选")
    void isolatesNonRegularCandidate() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins"));
        Path directoryCandidate = Files.createDirectory(plugins.resolve("directory.jar"));
        Path regularCandidate = Files.writeString(plugins.resolve("regular.jar"), "not-a-package");

        PluginArtifactScanner.ScanResult directoryScan = PluginArtifactScanner.scan(plugins);
        assertThat(directoryScan.candidates()).containsExactly(regularCandidate.toAbsolutePath().normalize());
        assertThat(directoryScan.rejectedCandidates()).singleElement().satisfies(rejected -> {
            assertThat(rejected.path()).isEqualTo(directoryCandidate.toAbsolutePath().normalize());
            assertThat(rejected.reason()).contains("plain regular file");
        });
        assertThat(new PluginRuntimeManager(plugins).start().failures())
                .extracting(failure -> failure.source())
                .contains("directory.jar", "regular.jar");

        Files.delete(directoryCandidate);
        Path target = Files.writeString(temporaryDirectory.resolve("outside.jar"), "not-a-package");
        Path link = plugins.resolve("linked.jar");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "当前文件系统不允许创建符号链接：" + e.getMessage());
            return;
        }
        PluginArtifactScanner.ScanResult linkScan = PluginArtifactScanner.scan(plugins);
        assertThat(linkScan.candidates()).containsExactly(regularCandidate.toAbsolutePath().normalize());
        assertThat(linkScan.rejectedCandidates()).singleElement().satisfies(rejected -> {
            assertThat(rejected.path()).isEqualTo(link.toAbsolutePath().normalize());
            assertThat(rejected.reason()).contains("plain regular file");
        });
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        assertThatThrownBy(() -> manager.loadPlugin(link))
                .isInstanceOf(PluginRuntimeOperationException.class)
                .hasMessageContaining("failed to freeze plugin artifact")
                .hasRootCauseMessage("plugin artifact must be a plain regular file: "
                        + link.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("安装根本身是符号链接时解析真实目录后安全扫描")
    void resolvesSymbolicLinkRoot() throws Exception {
        Path target = Files.createDirectories(temporaryDirectory.resolve("portable-target"));
        Path artifact = Files.writeString(target.resolve("probe.jar"), "not-a-package");
        Path link = temporaryDirectory.resolve("portable-link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "当前文件系统不允许创建符号链接：" + e.getMessage());
            return;
        }

        PluginArtifactScanner.ScanResult scan = PluginArtifactScanner.scan(link);

        assertThat(scan.root()).isEqualTo(target.toRealPath());
        assertThat(scan.candidates()).containsExactly(artifact.toRealPath());
        assertThat(scan.rejectedCandidates()).isEmpty();
    }

    @Test
    @DisplayName("portable 目录整体移动后从新位置重新解析插件根")
    void scansPortableRootAfterMove() throws Exception {
        Path original = Files.createDirectories(temporaryDirectory.resolve("portable-old/plugins"));
        Files.writeString(original.resolve("probe.jar"), "not-a-package");
        Path movedHome = temporaryDirectory.resolve("portable-new");
        Files.move(original.getParent(), movedHome);

        PluginArtifactScanner.ScanResult scan = PluginArtifactScanner.scan(movedHome.resolve("plugins"));

        assertThat(scan.root()).isEqualTo(movedHome.resolve("plugins").toRealPath());
        assertThat(scan.candidates()).containsExactly(movedHome.resolve("plugins/probe.jar").toRealPath());
    }
}
