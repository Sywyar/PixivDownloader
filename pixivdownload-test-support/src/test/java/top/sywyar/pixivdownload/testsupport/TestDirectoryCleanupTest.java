package top.sywyar.pixivdownload.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.spi.FileSystemProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("测试目录清理与 Windows 延迟删除")
class TestDirectoryCleanupTest {
    @TempDir Path directory;

    @Test
    @DisplayName("递归清理普通文件且不存在的目录可重复清理")
    void deletesTree() throws Exception {
        Path root = Files.createDirectories(directory.resolve("root/nested"));
        Files.writeString(root.resolve("file"), "fixture");
        TestDirectoryCleanup.delete(directory.resolve("root"));
        assertThat(directory.resolve("root")).doesNotExist();
        TestDirectoryCleanup.delete(directory.resolve("root"));
    }

    @Test
    @DisplayName("空目录删除不会吞掉真实非空目录错误")
    void rejectsNonemptyDirectory() throws Exception {
        Path root = Files.createDirectories(directory.resolve("occupied"));
        Files.writeString(root.resolve("file"), "retained");
        assertThatThrownBy(() -> TestDirectoryCleanup.deleteEmptyDirectory(root))
                .isInstanceOf(DirectoryNotEmptyException.class);
        assertThat(root.resolve("file")).hasContent("retained");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 空目录短暂报告非空后重试删除")
    void waitsForPendingDelete() throws Exception {
        Path root = Files.createDirectory(directory.resolve("pending"));
        var provider = mock(FileSystemProvider.class);
        Path path = deletionPath(root, provider);
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException(root.toString()))
                .thenAnswer(invocation -> Files.deleteIfExists(root));
        TestDirectoryCleanup.deleteEmptyDirectory(path);
        assertThat(root).doesNotExist();
        verify(provider, times(2)).deleteIfExists(path);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 枚举到已消失条目时仍可等待目录删除")
    void waitsForStaleDirectoryEntry() throws Exception {
        Path root = Files.createDirectory(directory.resolve("stale"));
        var provider = mock(FileSystemProvider.class);
        Path path = deletionPath(root, provider);
        var entries = mock(DirectoryStream.class);
        when(entries.iterator()).thenReturn(java.util.List.of(root.resolve("deleted")).iterator());
        when(provider.newDirectoryStream(eq(path), any())).thenReturn(entries);
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException(root.toString()))
                .thenAnswer(invocation -> Files.deleteIfExists(root));
        TestDirectoryCleanup.deleteEmptyDirectory(path);
        assertThat(root).doesNotExist();
        verify(entries).close();
        verify(provider, times(2)).deleteIfExists(path);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 持续删除失败达到重试上限后仍报错")
    void persistentHandleIsNotHidden() throws Exception {
        Path root = Files.createDirectory(directory.resolve("locked"));
        var provider = mock(FileSystemProvider.class);
        Path path = deletionPath(root, provider);
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException(root.toString()));
        assertThatThrownBy(() -> TestDirectoryCleanup.deleteEmptyDirectory(path))
                .isInstanceOf(DirectoryNotEmptyException.class);
        assertThat(root).isDirectory();
        verify(provider, times(10)).deleteIfExists(path);
    }

    private static Path deletionPath(Path root, FileSystemProvider provider) throws Exception {
        Path path = mock(Path.class);
        FileSystem fileSystem = mock(FileSystem.class);
        when(path.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.provider()).thenReturn(provider);
        when(provider.newDirectoryStream(eq(path), any())).thenAnswer(invocation -> Files.newDirectoryStream(root));
        return path;
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("目录联接不会让测试清理遍历外部目标")
    void rejectsJunctionTraversal() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("retained"), "fixture");
        Path root = Files.createDirectory(directory.resolve("root"));
        Path junction = root.resolve("link");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        try {
            assertThatThrownBy(() -> TestDirectoryCleanup.delete(root)).isInstanceOf(java.io.IOException.class);
            assertThat(outside.resolve("retained")).hasContent("fixture");
        } finally {
            Files.deleteIfExists(junction);
        }
    }
}
