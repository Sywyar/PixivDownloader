package top.sywyar.pixivdownload.gui.media;

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
import top.sywyar.pixivdownload.gui.DesktopMediaToolHistorySource;
import top.sywyar.pixivdownload.gui.DesktopToolHistory;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool.*;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.setup.ProgramAccess;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 媒体工具以完整 publication 身份调用，参数不修改已保存配置。 */
@HttpApiGroup("core-tools")
@RestController
@RequestMapping("/api/tools/media")
public final class MediaToolController {
    private static final String EXAMPLE_IDENTITY = """
            {"pluginId":"example-media","packageId":"example-media","generation":1,"publication":1}""";
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

    @GetMapping public List<DesktopMediaTool> tools(HttpServletRequest request) {
        return registry.tools().stream().filter(tool -> ProgramAccess.permits(request, tool.identity().pluginId(), "media.read")).toList();
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = PreviewRequest.class),
            examples = @ExampleObject(value = "{\"identity\":" + EXAMPLE_IDENTITY
                    + ",\"options\":{\"imageFormats\":\"png\",\"ugoiraFormats\":\"gif\",\"repairThumbnails\":false}}")))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @PostMapping(value = "/previews", consumes = "application/json")
    public Preview preview(HttpServletRequest request) throws IOException {
        var body = read(request, PreviewRequest.class);
        if (body.options() == null) throw new InputFieldException("/options", "required");
        return source(request, body.identity(), "media.preview").preview(body.options()).valueOrThrow();
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
    public ResponseEntity<MediaToolExecution.Snapshot> start(HttpServletRequest request) throws IOException {
        var body = read(request, StartRequest.class);
        if (body.token() == null || body.token().isBlank()) throw new InputFieldException("/token", "required");
        var execution = source(request, body.identity(), "media.execute");
        synchronized (execution) {
            new DesktopMediaToolHistorySource(body.identity(), execution, history).start(body.token()).valueOrThrow();
            return ResponseEntity.accepted().body(execution.snapshot());
        }
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/tasks/current")
    public MediaToolExecution.Snapshot status(@RequestParam String pluginId, @RequestParam String packageId,
                                    @RequestParam long generation, @RequestParam long publication, HttpServletRequest request) {
        var identity = new Identity(ToolApiRequests.requiredQuery("pluginId", pluginId),
                ToolApiRequests.requiredQuery("packageId", packageId), generation, publication);
        return source(request, identity, "media.read").snapshot();
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
    public ResponseEntity<MediaToolExecution.Snapshot> cancel(HttpServletRequest request) throws IOException {
        var body = read(request, CancelRequest.class);
        var execution = source(request, body.identity(), "media.cancel");
        synchronized (execution) {
            if (!execution.cancel(body.executionId()))
                throw new InputFieldException("/executionId", body.executionId() == null ? "required" : "invalid_value");
            return ResponseEntity.ok(execution.snapshot());
        }
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Identity.class),
            examples = @ExampleObject(value = EXAMPLE_IDENTITY)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @PostMapping(value = "/capabilities", consumes = "application/json")
    public Capabilities capabilities(HttpServletRequest request) throws IOException {
        var identity = read(request, Identity.class);
        var report = source(request, identity, "media.read", "").capabilities().valueOrThrow();
        return new Capabilities(report.source(), report.capabilities());
    }

    private MediaToolExecution source(HttpServletRequest request, Identity identity, String permission) {
        return source(request, identity, permission, "/identity");
    }

    private MediaToolExecution source(HttpServletRequest request, Identity identity, String permission, String pointer) {
        if (identity == null) throw new InputFieldException(pointer, "required");
        if (identity.pluginId() == null) throw new InputFieldException(pointer + "/pluginId", "required");
        if (identity.packageId() == null) throw new InputFieldException(pointer + "/packageId", "required");
        ProgramAccess.require(request, identity.pluginId(), permission, () -> registry.isPublished(identity));
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
