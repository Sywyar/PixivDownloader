package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("工具日志会话")
class ToolHtmlLogSessionTest {
    @Test
    @DisplayName("快速重跑不覆盖日志，拒绝并行替换，历史保留可读取的精确会话名")
    void keepsDistinctSessions(@TempDir Path directory) throws Exception {
        var logger = LoggerFactory.getLogger(ToolHtmlLogSessionTest.class);
        var history = new DesktopToolHistory(directory.resolve("state"));
        Path first;
        try (var session = ToolHtmlLogSession.open(directory, "artworks-backfill", getClass())) {
            first = session.sessionPath();
            logger.error("first 中文", new java.io.IOException("proxy unavailable"));
            assertThrows(IllegalStateException.class,
                    () -> ToolHtmlLogSession.open(directory, "artworks-backfill", getClass()));
            history.record(DesktopUiToolHost.ToolId.ARTWORKS_BACKFILL,
                    DesktopUiToolHost.ToolOutcome.FAILED, 1, 0, null, null, first);
        }
        String previous = Files.readString(first);
        try (var session = ToolHtmlLogSession.open(directory, "artworks-backfill", getClass())) {
            assertNotEquals(first, session.sessionPath());
            logger.info("second");
        }
        assertEquals(previous, Files.readString(first));
        assertTrue(previous.contains("proxy unavailable"));
        assertTrue(previous.contains("中文"));
        assertFalse(Files.readString(directory.resolve("artworks-backfill-latest.html")).contains("proxy unavailable"));
        assertEquals(first.getFileName().toString(),
                new DesktopToolHistory(directory.resolve("state")).entries().get(0).logFileName());
    }
}
