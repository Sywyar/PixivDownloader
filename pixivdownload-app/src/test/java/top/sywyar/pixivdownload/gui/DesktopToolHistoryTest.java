package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolHistoryEntry;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolId;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopToolHistoryTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("终态事实跨重启保留且不持久化敏感路径")
    void persistsTerminalFactsAcrossRestartWithoutSensitivePaths() throws Exception {
        DesktopToolHistory history = new DesktopToolHistory(tempDir);
        Path privateLog = tempDir.resolve("private").resolve("credential")
                .resolve("artworks-backfill_2026-08-21_120000.html");

        history.record(ToolId.ARTWORKS_BACKFILL,
                ToolOutcome.SUCCEEDED, 1L, 12, null, null, privateLog);

        String json = Files.readString(tempDir.resolve("tool-history.json"), StandardCharsets.UTF_8);
        assertThat(json).doesNotContain(
                tempDir.toString(), "private", "credential", "changedCount", "failedCount");
        assertThat(new DesktopToolHistory(tempDir).entries()).singleElement().satisfies(entry -> {
            assertThat(entry.toolId()).isEqualTo(ToolId.ARTWORKS_BACKFILL);
            assertThat(entry.outcome()).isEqualTo(ToolOutcome.SUCCEEDED);
            assertThat(entry.processedCount()).isEqualTo(12);
            assertThat(entry.changedCount()).isNull();
            assertThat(entry.logFileName()).isEqualTo("artworks-backfill_2026-08-21_120000.html");
        });
    }

    @Test
    @DisplayName("损坏、超限和不兼容历史保留为隔离证据")
    void quarantinesMalformedOversizedUnsupportedAndUnexpectedContent() throws Exception {
        assertQuarantined("malformed", "{not-json");
        assertQuarantined("oversized", "x".repeat(DesktopToolHistory.MAX_FILE_BYTES + 1));
        assertQuarantined("unsupported", "{\"schemaVersion\":2,\"entries\":[]}");
        assertQuarantined("unexpected",
                "{\"schemaVersion\":1,\"entries\":[{\"toolId\":\"FOLDER_CHECKER\","
                        + "\"startedAtEpochMs\":1,\"finishedAtEpochMs\":2,\"outcome\":\"CLOSED\","
                        + "\"password\":\"must-not-survive\"}]}");
    }

    @Test
    @DisplayName("并发终态记录只保留最新一百条且不超过文件上限")
    void keepsNewestHundredConcurrentTerminalEventsWithinByteLimit() throws Exception {
        DesktopToolHistory history = new DesktopToolHistory(tempDir);
        int total = 125;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        var executor = Executors.newFixedThreadPool(12);
        try {
            for (int index = 0; index < total; index++) {
                int processed = index;
                executor.execute(() -> {
                    try {
                        start.await();
                        history.record(ToolId.FOLDER_CHECKER,
                                ToolOutcome.SUCCEEDED, processed + 1L,
                                processed, null, null, null);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        Path file = tempDir.resolve("tool-history.json");
        List<ToolHistoryEntry> reloaded = new DesktopToolHistory(tempDir).entries();
        assertThat(reloaded).hasSize(DesktopToolHistory.MAX_ENTRIES);
        assertThat(reloaded).extracting(ToolHistoryEntry::processedCount)
                .doesNotHaveDuplicates();
        assertThat(Files.size(file)).isLessThanOrEqualTo(DesktopToolHistory.MAX_FILE_BYTES);
    }

    private void assertQuarantined(String directoryName, String content) throws Exception {
        Path directory = tempDir.resolve(directoryName);
        Files.createDirectories(directory);
        Path file = directory.resolve("tool-history.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertThat(new DesktopToolHistory(directory).entries()).isEmpty();
        assertThat(file).doesNotExist();
        try (var files = Files.list(directory)) {
            assertThat(files.filter(path -> path.getFileName().toString()
                    .matches("tool-history\\.corrupt-[0-9]+\\.json"))).hasSize(1);
        }
    }
}
