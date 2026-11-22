package top.sywyar.pixivdownload.gui.importing;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.*;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.setup.ProgramAccess;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 管理员登记服务器已有记录文件；一次性凭据仅传递给精确来源。 */
@HttpApiGroup("core-import")
@RestController
@RequestMapping("/api/tools/file-import")
public final class FileImportToolController {
    private static final String EXAMPLE_IDENTITY = """
            {"pluginId":"example-import","packageId":"example-import","generation":1,"publication":1}""";
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

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = InspectRequest.class),
            examples = @ExampleObject(value = "{\"identity\":" + EXAMPLE_IDENTITY
                    + ",\"options\":{\"recordFile\":\"/srv/import/records.json\",\"sourceRoot\":\"/srv/import/files\"}}")))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @PostMapping(value = "/previews", consumes = "application/json")
    public Preview inspect(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, InspectRequest.class, BODY_LIMIT);
        if (body.options() == null) throw new InputFieldException("/options", "required");
        return source(request, body.identity(), "file-import.preview").inspect(body.options()).valueOrThrow();
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = StartRequest.class),
            examples = @ExampleObject(value = "{\"identity\":" + EXAMPLE_IDENTITY
                    + ",\"token\":\"token-from-preview\"}")))
    @ApiResponse(responseCode = "202", description = "Accepted", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @PostMapping(value = "/tasks", consumes = "application/json")
    public ResponseEntity<FileImportExecution.Snapshot> start(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, StartRequest.class, BODY_LIMIT);
        if (body.token() == null || body.token().isBlank()) throw new InputFieldException("/token", "required");
        if (body.token().length() > 128) throw new InputFieldException("/token", "too_large");
        var execution = source(request, body.identity(), "file-import.execute");
        synchronized (execution) {
            execution.start(body.token(), body.credential()).valueOrThrow();
            return ResponseEntity.accepted().body(execution.snapshot());
        }
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/tasks/current")
    public FileImportExecution.Snapshot status(@RequestParam String pluginId, @RequestParam String packageId,
                                    @RequestParam long generation, @RequestParam long publication, HttpServletRequest request) {
        var identity = new Identity(ToolApiRequests.requiredQuery("pluginId", pluginId),
                ToolApiRequests.requiredQuery("packageId", packageId), generation, publication);
        return source(request, identity, "file-import.read").snapshot();
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = CancelRequest.class),
            examples = @ExampleObject(value = "{\"identity\":" + EXAMPLE_IDENTITY
                    + ",\"executionId\":\"9892b717-124c-4299-b9ed-9bb32ca83518\"}")))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @PostMapping(value = "/tasks/cancel", consumes = "application/json")
    public FileImportExecution.Snapshot cancel(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, CancelRequest.class, BODY_LIMIT);
        var execution = source(request, body.identity(), "file-import.cancel");
        synchronized (execution) {
            if (!execution.cancel(body.executionId()))
                throw new InputFieldException("/executionId", body.executionId() == null ? "required" : "invalid_value");
            return execution.snapshot();
        }
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = AcknowledgeRequest.class),
            examples = @ExampleObject(value = "{\"identity\":" + EXAMPLE_IDENTITY
                    + ",\"completionId\":\"id-from-pending-completion\"}")))
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "404", content = @Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @PostMapping(value = "/completion/acknowledge", consumes = "application/json")
    public ResponseEntity<Void> acknowledge(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, AcknowledgeRequest.class, BODY_LIMIT);
        if (body.completionId() == null || body.completionId().isBlank()) throw new InputFieldException("/completionId", "required");
        if (body.completionId().length() > 128) throw new InputFieldException("/completionId", "too_large");
        var execution = source(request, body.identity(), "file-import.acknowledge");
        synchronized (execution) {
            if (execution.pendingCompletion().filter(value -> value.id().equals(body.completionId())).isEmpty())
                return ResponseEntity.notFound().build();
            execution.acknowledgeCompletion(body.completionId());
        }
        return ResponseEntity.noContent().build();
    }

    private FileImportExecution source(HttpServletRequest request, Identity identity, String permission) {
        if (identity == null) throw new InputFieldException("/identity", "required");
        if (identity.pluginId() == null) throw new InputFieldException("/identity/pluginId", "required");
        if (identity.packageId() == null) throw new InputFieldException("/identity/packageId", "required");
        ProgramAccess.require(request, identity.pluginId(), permission, () -> registry.isPublished(identity));
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
