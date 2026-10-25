package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.WindowStateSnapshot;

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@DisplayName("桌面窗口状态持久化")
class DesktopWindowStateStoreTest {
    private static final int LIMIT = 4 * 1024;

    @TempDir Path directory;

    @Test
    @DisplayName("保存尺寸和最大化状态并忽略损坏文件")
    void roundTripsValidStateAndIgnoresInvalidState() throws Exception {
        DesktopWindowStateStore store = new DesktopWindowStateStore(directory);
        WindowStateSnapshot expected = new WindowStateSnapshot(1120, 760, true);

        assertThat(store.save(expected)).isTrue();
        assertThat(store.load()).contains(expected);

        Files.writeString(directory.resolve("window-state.json"),
                "{\"width\":0,\"height\":760,\"maximized\":true}", StandardCharsets.UTF_8);
        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("允许恰好达到字节上限的窗口状态并保留超限原文件")
    void readsAtLimitAndRejectsOverflow() throws Exception {
        Path file = directory.resolve("window-state.json");
        String json = "{\"width\":1120,\"height\":760,\"maximized\":false}";
        Files.writeString(file, json + " ".repeat(LIMIT - json.length()), StandardCharsets.UTF_8);
        DesktopWindowStateStore store = new DesktopWindowStateStore(directory);

        assertThat(store.load()).contains(new WindowStateSnapshot(1120, 760, false));
        Files.writeString(file, json + " ".repeat(LIMIT + 1 - json.length()), StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(file);
        assertThat(store.load()).isEmpty();
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    @Test
    @DisplayName("文件在大小检查后增长时仍只读取上限加一字节并关闭通道")
    void growthAfterSizeObservationStaysBounded() throws Exception {
        Path file = directory.resolve("growing.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        BasicFileAttributes beforeGrowth = Files.readAttributes(file, BasicFileAttributes.class);
        Files.writeString(file, "{}" + " ".repeat(2 * LIMIT), StandardCharsets.UTF_8);
        AtomicInteger consumed = new AtomicInteger();
        SeekableByteChannel channel = spy(Files.newByteChannel(file));
        doAnswer(call -> {
            int count = (int) call.callRealMethod();
            if (count > 0) consumed.addAndGet(count);
            return count;
        }).when(channel).read(any(ByteBuffer.class));
        FileSystemProvider provider = mock(FileSystemProvider.class, CALLS_REAL_METHODS);
        FileSystem fileSystem = mock(FileSystem.class);
        Path observed = mock(Path.class);
        Path observedDirectory = mock(Path.class);
        when(observedDirectory.resolve("window-state.json")).thenReturn(observed);
        when(observed.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.provider()).thenReturn(provider);
        when(provider.readAttributes(observed, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))
                .thenReturn(beforeGrowth);
        when(provider.readAttributes(observed, BasicFileAttributes.class)).thenReturn(beforeGrowth);
        when(provider.newByteChannel(eq(observed), any())).thenReturn(channel);
        try {
            assertThat(new DesktopWindowStateStore(observedDirectory).load()).isEmpty();
            assertThat(consumed.get()).isEqualTo(LIMIT + 1);
            assertThat(channel.isOpen()).isFalse();
        } finally {
            channel.close();
        }
    }
}
