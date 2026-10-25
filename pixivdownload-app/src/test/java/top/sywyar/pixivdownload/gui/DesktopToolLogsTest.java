package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("已有工具日志的只读定位")
class DesktopToolLogsTest {
    @TempDir Path directory;

    @Test
    @DisplayName("未知工具的最新与精确会话日志均可读取，不创建或轮换任何文件")
    void resolvesUnknownToolWithoutChangingEvidence() throws Exception {
        try (var session = ToolHtmlLogSession.open(directory, "independent-inspector", getClass())) {
            var logs = new DesktopToolLogs(directory);
            for (Path file : List.of(session.latestPath(), session.sessionPath())) {
                byte[] before = Files.readAllBytes(file);
                assertEquals(file.toAbsolutePath(), logs.find(file.getFileName().toString()).orElseThrow());
                assertArrayEquals(before, Files.readAllBytes(file));
            }
            try (var files = Files.list(directory)) {
                assertEquals(2, files.count());
            }
        }
    }

    @Test
    @DisplayName("不存在的日志查询不创建目录，非法文件名不能读取其它宿主文件")
    void rejectsPathsAndDoesNotCreateMissingLogs() throws Exception {
        var missing = directory.resolve("missing");
        var logs = new DesktopToolLogs(missing);
        assertTrue(logs.find("independent-latest.html").isEmpty());
        assertFalse(Files.exists(missing));
        for (String name : List.of("../secret.html", "..\\secret.html", "C:\\secret.html", "/secret.html",
                "secret.html:stream", "secret.yaml", "", "x".repeat(256) + ".html")) {
            assertThrows(IllegalArgumentException.class, () -> logs.find(name), name);
        }
        assertThrows(IllegalArgumentException.class, () -> logs.find(null));
    }

    @Test
    @DisplayName("日志条目是目录时拒绝，已轮换删除的历史文件返回缺失")
    void rejectsDirectoryAndReportsExpiredFile() throws Exception {
        var logs = new DesktopToolLogs(directory);
        Files.createDirectory(directory.resolve("directory.html"));
        assertThrows(java.io.IOException.class, () -> logs.find("directory.html"));
        Path file = Files.writeString(directory.resolve("independent-session.html"), "evidence");
        assertTrue(logs.find(file.getFileName().toString()).isPresent());
        Files.delete(file);
        assertTrue(logs.find(file.getFileName().toString()).isEmpty());
    }

    @Test
    @DisplayName("宿主按文件引用读取未知工具日志，拒绝沿目录联接读取外部证据")
    void hostLookupRejectsLinkedDirectory() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("independent.html"), "private evidence");
        Path logs = Files.createDirectory(directory.resolve("log"));
        Path link = logs.resolve("html");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertEquals(0, process.waitFor());
        assertTrue(Files.isDirectory(link));
        try (var paths = org.mockito.Mockito.mockStatic(top.sywyar.pixivdownload.config.RuntimeFiles.class)) {
            paths.when(top.sywyar.pixivdownload.config.RuntimeFiles::logDirectory).thenReturn(logs);
            paths.when(top.sywyar.pixivdownload.config.RuntimeFiles::guiStateDirectory).thenReturn(directory.resolve("state"));
            var host = new AppDesktopUiHost(0, org.mockito.Mockito.mock(
                    top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.ConfigFile.class));
            assertThrows(java.io.IOException.class, () -> host.findToolLog("independent.html"));
            Files.delete(link);
            Files.createDirectory(link);
            Path file = Files.writeString(link.resolve("independent.html"), "managed evidence");
            assertEquals(file, host.findToolLog("independent.html").orElseThrow());
            assertEquals("private evidence", Files.readString(outside.resolve("independent.html")));
        } finally {
            if (!top.sywyar.pixivdownload.common.PlainFilePathGuard.isPlainDirectory(link)) Files.deleteIfExists(link);
        }
    }
}
