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
    @DisplayName("可见 jar 名称若是目录或符号链接必须在包校验前拒绝")
    void rejectsNonRegularCandidate() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins"));
        Files.createDirectory(plugins.resolve("directory.jar"));

        assertThatThrownBy(() -> PluginArtifactScanner.scan(plugins))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("plain regular file");

        Files.delete(plugins.resolve("directory.jar"));
        Path target = Files.writeString(temporaryDirectory.resolve("outside.jar"), "not-a-package");
        Path link = plugins.resolve("linked.jar");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "当前文件系统不允许创建符号链接：" + e.getMessage());
            return;
        }
        assertThatThrownBy(() -> PluginArtifactScanner.scan(plugins))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("plain regular file");
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        assertThatThrownBy(() -> manager.loadPlugin(link))
                .isInstanceOf(PluginRuntimeOperationException.class)
                .hasMessageContaining("not found");
    }
}
