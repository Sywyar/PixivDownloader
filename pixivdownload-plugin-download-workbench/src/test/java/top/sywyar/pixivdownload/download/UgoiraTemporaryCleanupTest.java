package top.sywyar.pixivdownload.download;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("动图临时目录删除边界")
class UgoiraTemporaryCleanupTest {
    @TempDir Path directory;
    private final FileSystemProvider provider = mock(FileSystemProvider.class);
    private final Path path = mock(Path.class);

    UgoiraTemporaryCleanupTest() {
        FileSystem fileSystem = mock(FileSystem.class);
        when(path.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.provider()).thenReturn(provider);
    }

    @Test
    @DisplayName("清理失败保留已保存文件并仅汇总脱敏告警")
    void reportsFailureWithoutExposingPathsOrRollingBackOutputs() throws Exception {
        when(provider.deleteIfExists(path)).thenThrow(new AccessDeniedException("private-path-secret"));
        Path output = Files.writeString(directory.resolve("saved.zip"), "saved source");
        Logger logger = (Logger) LoggerFactory.getLogger(UgoiraService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var service = mock(UgoiraService.class, CALLS_REAL_METHODS);
            service.cleanup(42L, path, directory.resolve("missing-frames"));
            assertThat(output).hasContent("saved source");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("artworkId=42", "failures=1", "AccessDeniedException")
                        .doesNotContain("private-path-secret", directory.toString());
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("子项删除尚未可见时等待并只重试目录删除")
    void retriesPendingDirectoryOnly() throws Exception {
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException("fixture")).thenReturn(true);
        UgoiraService.deleteTemporaryPath(path);
        verify(provider, times(2)).deleteIfExists(path);
        verifyNoMoreInteractions(provider);
    }

    @Test
    @DisplayName("持续非空有界失败且不遍历删除新增条目")
    void rejectsPersistentContents() throws Exception {
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException("fixture"));
        assertThatThrownBy(() -> UgoiraService.deleteTemporaryPath(path)).isInstanceOf(DirectoryNotEmptyException.class);
        int attempts = System.getProperty("os.name", "").startsWith("Windows") ? 10 : 1;
        verify(provider, times(attempts)).deleteIfExists(path);
        verifyNoMoreInteractions(provider);
    }

    @Test
    @DisplayName("权限错误立即传播")
    void rejectsDeniedDeletion() throws Exception {
        when(provider.deleteIfExists(path)).thenThrow(new AccessDeniedException("fixture"));
        assertThatThrownBy(() -> UgoiraService.deleteTemporaryPath(path)).isInstanceOf(AccessDeniedException.class);
        verify(provider).deleteIfExists(path);
        verifyNoMoreInteractions(provider);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("等待中断后保留中断标记并结束重试")
    void preservesInterruption() throws Exception {
        when(provider.deleteIfExists(path)).thenThrow(new DirectoryNotEmptyException("fixture"));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> UgoiraService.deleteTemporaryPath(path))
                    .isInstanceOf(IOException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(provider).deleteIfExists(path);
        } finally {
            Thread.interrupted();
        }
    }
}
