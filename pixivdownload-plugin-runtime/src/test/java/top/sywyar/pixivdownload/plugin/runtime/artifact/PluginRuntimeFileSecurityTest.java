package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("插件 runtime 文件权限便携降级")
class PluginRuntimeFileSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("原生文件系统按 POSIX 或 ACL 能力收紧 runtime 托管目录")
    void hardensManagedDirectoriesOnNativeFilesystem() throws Exception {
        Path plugins = Files.createDirectory(tempDir.resolve("plugins-native"));
        PluginRuntimeLayout layout = new PluginRuntimeLayout(plugins);

        var owner = PluginRuntimeFileSecurity.secureLoadingRoots(layout);
        Path runtime = layout.runtimeDirectory();
        PosixFileAttributeView posix = Files.getFileAttributeView(
                runtime, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            assertThat(posix.readAttributes().permissions()).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(
                runtime, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null && owner != null) {
            assertThat(acl.getAcl()).singleElement().satisfies(entry -> {
                assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                assertThat(entry.principal().getName()).isEqualToIgnoringCase(owner.getName());
                assertThat(entry.permissions()).contains(
                        AclEntryPermission.READ_DATA,
                        AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.EXECUTE);
            });
        }
    }

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
