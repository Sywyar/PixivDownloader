package top.sywyar.pixivdownload.gui.media;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.gui.DesktopMediaToolHistorySource;
import top.sywyar.pixivdownload.gui.DesktopToolHistory;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool.*;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 媒体工具以完整 publication 身份调用，参数不修改已保存配置。 */
@RestController
@RequestMapping("/api/tools/media")
public final class MediaToolController {
    private static final int BODY_LIMIT = 4096;
    private final DesktopMediaToolRegistry registry;
    private final DesktopToolHistory history;
    private final ToolApiRequests requests;

    public MediaToolController(DesktopMediaToolRegistry registry, DesktopToolHistory history,
                               ToolApiRequests requests) {
        this.registry = registry;
        this.history = history;
        this.requests = requests;
    }

    public record PreviewRequest(Identity identity, Request options) {}
    public record StartRequest(Identity identity, String token) {}
    public record CancelRequest(Identity identity, UUID executionId) {}
    public record Capabilities(String source, List<Capability> capabilities) {}

    @GetMapping public List<DesktopMediaTool> tools() { return registry.tools(); }

    @PostMapping(value = "/previews", consumes = "application/json")
    public Preview preview(HttpServletRequest request) throws IOException {
        var body = read(request, PreviewRequest.class);
        if (body.options() == null) throw new IllegalArgumentException();
        return source(body.identity()).preview(body.options()).valueOrThrow();
    }

    @PostMapping(value = "/tasks", consumes = "application/json")
    public ResponseEntity<MediaToolExecution.Snapshot> start(HttpServletRequest request) throws IOException {
        var body = read(request, StartRequest.class);
        if (body.token() == null || body.token().isBlank()) throw new IllegalArgumentException();
        var execution = source(body.identity());
        synchronized (execution) {
            new DesktopMediaToolHistorySource(body.identity(), execution, history).start(body.token()).valueOrThrow();
            return ResponseEntity.accepted().body(execution.snapshot());
        }
    }

    @GetMapping("/tasks/current")
    public MediaToolExecution.Snapshot status(@ModelAttribute Identity identity) { return source(identity).snapshot(); }

    @PostMapping(value = "/tasks/cancel", consumes = "application/json")
    public ResponseEntity<MediaToolExecution.Snapshot> cancel(HttpServletRequest request) throws IOException {
        var body = read(request, CancelRequest.class);
        var execution = source(body.identity());
        synchronized (execution) {
            if (!execution.cancel(body.executionId())) throw new IllegalArgumentException();
            return ResponseEntity.ok(execution.snapshot());
        }
    }

    @PostMapping(value = "/capabilities", consumes = "application/json")
    public Capabilities capabilities(HttpServletRequest request) throws IOException {
        var report = source(read(request, Identity.class)).capabilities().valueOrThrow();
        return new Capabilities(report.source(), report.capabilities());
    }

    private MediaToolExecution source(Identity identity) {
        if (identity == null || identity.pluginId() == null || identity.packageId() == null)
            throw new IllegalArgumentException();
        return registry.execution(identity);
    }

    private static <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        return ToolApiRequests.read(request, type, BODY_LIMIT);
    }

    @ExceptionHandler(OperationException.class)
    public ResponseEntity<ApiErrorResponse> rejected(OperationException failure, Locale locale) {
        return requests.rejected(failure.text(), locale);
    }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) {
        return requests.invalid(failure, locale, BODY_LIMIT);
    }
}
