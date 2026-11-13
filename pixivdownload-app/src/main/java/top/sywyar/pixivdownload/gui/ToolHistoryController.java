package top.sywyar.pixivdownload.gui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.ToolHistoryEntry;

import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.PathVariable;

/** 与桌面共用有界执行历史，不启动工具或打开本机日志。 */
@RestController
public final class ToolHistoryController {
    private final DesktopToolHistory history;

    public ToolHistoryController(DesktopToolHistory history) { this.history = history; }

    @GetMapping("/api/tools/history")
    public List<ToolHistoryEntry> history() { return history.entries(); }

    /** 日志作为附件读取，禁止在应用同源上下文执行日志中的 HTML。 */
    @GetMapping("/api/tools/logs/{fileName}")
    public ResponseEntity<InputStreamResource> log(@PathVariable String fileName) throws IOException {
        if (!DesktopToolLogs.validFileName(fileName)) return ResponseEntity.notFound().build();
        var path = new DesktopToolLogs(RuntimeFiles.logDirectory().resolve("html")).find(fileName);
        if (path.isEmpty()) return ResponseEntity.notFound().build();
        var stream = Files.newInputStream(path.get(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        return ResponseEntity.ok().header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("Cache-Control", "no-store")
                .body(new InputStreamResource(stream));
    }
}
