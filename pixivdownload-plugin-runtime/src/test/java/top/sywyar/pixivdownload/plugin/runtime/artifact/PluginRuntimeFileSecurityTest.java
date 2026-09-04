package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("插件 runtime 文件权限便携降级")
class PluginRuntimeFileSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("无 POSIX 权限和 ACL 的文件系统仍可创建安全托管目录")
    void allowsFilesystemWithoutPermissionViews() throws Exception {
        URI archive = URI.create("jar:" + tempDir.resolve("portable.zip").toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path plugins = Files.createDirectory(fileSystem.getPath("/plugins"));
            PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);

            assertThatCode(() -> PluginRuntimeFileSecurity.secureLoadingRoots(layout))
                    .doesNotThrowAnyException();
            assertThat(layout.runtimeDirectory()).isDirectory();
            assertThat(layout.provenanceDirectory()).isDirectory();
        }
    }
}
