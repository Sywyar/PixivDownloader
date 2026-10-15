package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopMediaToolHistorySourceTest {
    @Test
    @DisplayName("无界面轮询时仍持久化精确执行终态，重复通知不重复记录")
    void recordsCompletionWithoutPolling(@TempDir Path directory) {
        var source = mock(DesktopMediaTool.CompletionSource.class);
        var callback = new AtomicReference<Consumer<DesktopMediaTool.Status>>();
        when(source.start(eq("token"), any())).thenAnswer(call -> {
            callback.set(call.getArgument(1));
            return new DesktopMediaTool.Result<>(new DesktopMediaTool.Status("running", 4, 0, 0, List.of()), null);
        });
        var history = new DesktopToolHistory(directory);
        var wrapper = new DesktopMediaToolHistorySource(new DesktopMediaTool.Identity("independent", "sample", 9, 3),
                source, history);
        wrapper.start("token").valueOrThrow();
        assertTrue(history.entries().isEmpty());
        var cancelled = new DesktopMediaTool.Status("cancelled", 4, 2, 1, List.of(new DesktopMediaTool.Failure(42, 0)));
        callback.get().accept(cancelled);
        callback.get().accept(cancelled);
        var entries = new DesktopToolHistory(directory).entries();
        assertEquals(1, entries.size());
        assertEquals(DesktopUiToolHost.ToolId.MEDIA_MAINTENANCE, entries.get(0).toolId());
        assertEquals(DesktopUiToolHost.ToolOutcome.CANCELLED, entries.get(0).outcome());
        assertEquals(2, entries.get(0).processedCount());
        assertEquals(1, entries.get(0).changedCount());
        assertEquals(1, entries.get(0).failedCount());
        verify(source, never()).status();
    }
}
