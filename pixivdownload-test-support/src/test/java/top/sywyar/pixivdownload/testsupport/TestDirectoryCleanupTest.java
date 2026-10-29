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
        Path path = deletionPath(provider);
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException(root.toString()))
                .thenAnswer(invocation -> Files.deleteIfExists(root));
        TestDirectoryCleanup.deleteEmptyDirectory(path);
        assertThat(root).doesNotExist();
        verify(provider, times(2)).deleteIfExists(path);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 等待临时条目自行消失，不主动删除后来出现的文件")
    void waitsForTransientChild() throws Exception {
        Path root = Files.createDirectory(directory.resolve("transient"));
        Path child = Files.writeString(root.resolve("temporary"), "retained until owner closes");
        var provider = mock(FileSystemProvider.class);
        Path path = deletionPath(provider);
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException(root.toString()))
                .thenAnswer(invocation -> {
                    assertThat(child).hasContent("retained until owner closes");
                    Files.delete(child);
                    return Files.deleteIfExists(root);
                });
        TestDirectoryCleanup.deleteEmptyDirectory(path);
        assertThat(root).doesNotExist();
        verify(provider, times(2)).deleteIfExists(path);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("文件和目录权限错误直接传播且不重试")
    void propagatesAccessDenied(boolean file) throws Exception {
        var provider = mock(FileSystemProvider.class);
        Path path = deletionPath(provider);
        when(provider.deleteIfExists(path)).thenThrow(new AccessDeniedException("fixture"));
        assertThatThrownBy(() -> {
            if (file) TestDirectoryCleanup.deleteFile(path);
            else TestDirectoryCleanup.deleteEmptyDirectory(path);
        })
                .isInstanceOf(AccessDeniedException.class);
        verify(provider).deleteIfExists(path);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("等待被中断时传播失败并保留中断标记")
    void preservesInterruption(boolean file) throws Exception {
        var provider = mock(FileSystemProvider.class);
        Path path = deletionPath(provider);
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException("fixture"));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> {
                if (file) TestDirectoryCleanup.deleteFile(path);
                else TestDirectoryCleanup.deleteEmptyDirectory(path);
            })
                    .isInstanceOf(java.io.IOException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(provider).deleteIfExists(path);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 文件占用错误后只等待消失，不重试删除")
    void waitsForDisappearingFile() throws Exception {
        Path file = Files.writeString(directory.resolve("pending-file"), "retained");
        var provider = mock(FileSystemProvider.class);
        Path path = deletionPath(provider);
        when(provider.deleteIfExists(path)).thenThrow(new FileSystemException(file.toString()));
        when(provider.readAttributes(eq(path), eq(java.nio.file.attribute.BasicFileAttributes.class), any(LinkOption[].class)))
                .thenAnswer(invocation -> {
                    assertThat(file).hasContent("retained");
                    Files.delete(file);
                    throw new NoSuchFileException(file.toString());
                });
        TestDirectoryCleanup.deleteFile(path);
        assertThat(file).doesNotExist();
        verify(provider).deleteIfExists(path);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 文件持续占用时保留文件并传播原错误")
    void preservesPersistentFileFailure() throws Exception {
        Path file = Files.writeString(directory.resolve("occupied-file"), "retained");
        var provider = mock(FileSystemProvider.class);
        Path path = deletionPath(provider);
        var failure = new FileSystemException(file.toString());
        when(provider.deleteIfExists(path)).thenThrow(failure);
        when(provider.readAttributes(eq(path), eq(java.nio.file.attribute.BasicFileAttributes.class), any(LinkOption[].class)))
                .thenAnswer(invocation -> Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class));
        assertThatThrownBy(() -> TestDirectoryCleanup.deleteFile(path)).isSameAs(failure);
        assertThat(file).hasContent("retained");
        verify(provider).deleteIfExists(path);
        verify(provider, times(9)).readAttributes(eq(path), eq(java.nio.file.attribute.BasicFileAttributes.class), any(LinkOption[].class));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 持续删除失败达到重试上限后仍报错")
    void persistentHandleIsNotHidden() throws Exception {
        Path root = Files.createDirectory(directory.resolve("locked"));
        var provider = mock(FileSystemProvider.class);
        Path path = deletionPath(provider);
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException(root.toString()));
        assertThatThrownBy(() -> TestDirectoryCleanup.deleteEmptyDirectory(path))
                .isInstanceOf(DirectoryNotEmptyException.class);
        assertThat(root).isDirectory();
        verify(provider, times(10)).deleteIfExists(path);
    }

    private static Path deletionPath(FileSystemProvider provider) throws Exception {
        Path path = mock(Path.class);
        FileSystem fileSystem = mock(FileSystem.class);
        when(path.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.provider()).thenReturn(provider);
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
