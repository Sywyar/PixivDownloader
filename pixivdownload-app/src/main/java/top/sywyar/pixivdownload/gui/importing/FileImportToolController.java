package top.sywyar.pixivdownload.gui.importing;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.*;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.setup.ProgramAccess;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 管理员登记服务器已有记录文件；一次性凭据仅传递给精确来源。 */
@RestController
@RequestMapping("/api/tools/file-import")
public final class FileImportToolController {
    private static final int BODY_LIMIT = 64 * 1024;
    private final DesktopFileImportToolRegistry registry;
    private final ToolApiRequests requests;

    public FileImportToolController(DesktopFileImportToolRegistry registry, ToolApiRequests requests) {
        this.registry = registry;
        this.requests = requests;
    }

    public record InspectRequest(Identity identity, Request options) {}
    public record StartRequest(Identity identity, String token, String credential) {
        @Override public String toString() { return "StartRequest[redacted]"; }
    }
    public record CancelRequest(Identity identity, UUID executionId) {}
    public record AcknowledgeRequest(Identity identity, String completionId) {}

    @GetMapping public List<DesktopFileImportTool> tools(HttpServletRequest request) {
        return registry.tools().stream().filter(tool -> ProgramAccess.permits(request, tool.identity().pluginId(), "file-import.read")).toList();
    }

    @PostMapping(value = "/previews", consumes = "application/json")
    public Preview inspect(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, InspectRequest.class, BODY_LIMIT);
        if (body.options() == null) throw new IllegalArgumentException();
        return source(request, body.identity(), "file-import.preview").inspect(body.options()).valueOrThrow();
    }

    @PostMapping(value = "/tasks", consumes = "application/json")
    public ResponseEntity<FileImportExecution.Snapshot> start(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, StartRequest.class, BODY_LIMIT);
        if (body.token() == null || body.token().isBlank() || body.token().length() > 128)
            throw new IllegalArgumentException();
        var execution = source(request, body.identity(), "file-import.execute");
        synchronized (execution) {
            execution.start(body.token(), body.credential()).valueOrThrow();
            return ResponseEntity.accepted().body(execution.snapshot());
        }
    }

    @GetMapping("/tasks/current")
    public FileImportExecution.Snapshot status(@ModelAttribute Identity identity, HttpServletRequest request) {
        return source(request, identity, "file-import.read").snapshot();
    }

    @PostMapping(value = "/tasks/cancel", consumes = "application/json")
    public FileImportExecution.Snapshot cancel(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, CancelRequest.class, BODY_LIMIT);
        var execution = source(request, body.identity(), "file-import.cancel");
        synchronized (execution) {
            if (!execution.cancel(body.executionId())) throw new IllegalArgumentException();
            return execution.snapshot();
        }
    }

    @PostMapping(value = "/completion/acknowledge", consumes = "application/json")
    public ResponseEntity<Void> acknowledge(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, AcknowledgeRequest.class, BODY_LIMIT);
        if (body.completionId() == null || body.completionId().isBlank() || body.completionId().length() > 128)
            throw new IllegalArgumentException();
        var execution = source(request, body.identity(), "file-import.acknowledge");
        synchronized (execution) {
            if (execution.pendingCompletion().filter(value -> value.id().equals(body.completionId())).isEmpty())
                return ResponseEntity.notFound().build();
            execution.acknowledgeCompletion(body.completionId());
        }
        return ResponseEntity.noContent().build();
    }

    private FileImportExecution source(HttpServletRequest request, Identity identity, String permission) {
        if (identity == null || identity.pluginId() == null || identity.packageId() == null)
            throw new IllegalArgumentException();
        ProgramAccess.require(request, identity.pluginId(), permission);
        return registry.execution(identity);
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
