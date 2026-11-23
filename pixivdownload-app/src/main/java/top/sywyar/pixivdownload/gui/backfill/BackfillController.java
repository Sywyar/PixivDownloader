package top.sywyar.pixivdownload.gui.backfill;

import top.sywyar.pixivdownload.common.web.HttpJsonInput;
import top.sywyar.pixivdownload.web.RequestFieldError;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import static top.sywyar.pixivdownload.gui.backfill.DesktopBackfillRunner.BACKFILL_BUSY;
import static top.sywyar.pixivdownload.gui.backfill.DesktopBackfillRunner.BACKFILL_UNAVAILABLE;

import io.swagger.v3.oas.annotations.media.SchemaProperty;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.common.web.BoundedBodyRequest;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.BackfillTask;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 管理员回填入口，共用桌面及无头启动的执行器和当前数据库目标。 */
@HttpApiGroup("core-maintenance")
@RestController
@RequestMapping("/api/tools/backfill")
public final class BackfillController {
    static final String BACKFILL_INVALID_REQUEST = "BACKFILL_INVALID_REQUEST";
    static final String BACKFILL_TASK_NOT_FOUND = "BACKFILL_TASK_NOT_FOUND";

    static final int MAX_BODY_BYTES = 4096;
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final DesktopBackfillRunner runner;
    private final DatabaseTarget target;
    private final ProxyConfig proxy;
    private final DownloadConfig download;
    private final AppMessages messages;

    public BackfillController(DesktopBackfillRunner runner, DatabaseTarget target, ProxyConfig proxy,
                              DownloadConfig download, AppMessages messages) {
        this.runner = runner;
        this.target = target;
        this.proxy = proxy;
        this.download = download;
        this.messages = messages;
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Request(Long delayMs, Integer limit, Boolean dryRun, Boolean alwaysNetwork,
                          @HttpApiNullable @ArraySchema(minItems = 1, schema = @Schema(type = "integer", format = "int64", minimum = "1"))
                          List<Long> artworkIds) {
        public Request {
            if (artworkIds != null) {
                if (artworkIds.isEmpty()) throw new InputFieldException("/artworkIds", "invalid_value");
                for (int i = 0; i < artworkIds.size(); i++) {
                    Long id = artworkIds.get(i);
                    if (id == null) throw new InputFieldException("/artworkIds/" + i, "required");
                    if (id <= 0) throw new InputFieldException("/artworkIds/" + i, "out_of_range");
                }
            }
        }
    }
    public record Candidates(int count) {}

    @GetMapping("/defaults")
    public Request defaults() {
        var defaults = ArtworksBackFill.Options.defaults();
        return new Request(defaults.delayMs(), defaults.limit(), defaults.dryRun(), defaults.alwaysNetwork(), null);
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Request.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = RequestFieldError.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = BACKFILL_INVALID_REQUEST))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = BoundedBodyRequest.HTTP_BODY_LIMIT))))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = {BACKFILL_UNAVAILABLE, "auth.maintenance", "auth.setup-required", "auth.program.unavailable"}))))
    @PostMapping(value = "/candidates", consumes = "application/json")
    public Candidates candidates(HttpServletRequest request) throws Exception {
        return new Candidates(runner.countCandidates(options(read(request))));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Request.class)))
    @ApiResponse(responseCode = "202", description = "Accepted", useReturnTypeSchema = true,
            headers = @Header(name = "Location", required = true, schema = @Schema(type = "string", format = "uri-reference", example = "/api/tools/backfill/tasks/00000000-0000-4000-8000-000000000042")),
            links = @io.swagger.v3.oas.annotations.links.Link(name = "status",
                    operationRef = "#/paths/~1api~1tools~1backfill~1tasks~1%7Bid%7D/get",
                    parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/executionId")))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = RequestFieldError.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = BACKFILL_INVALID_REQUEST))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = BACKFILL_BUSY))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = BoundedBodyRequest.HTTP_BODY_LIMIT))))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = {BACKFILL_UNAVAILABLE, "auth.maintenance", "auth.setup-required", "auth.program.unavailable"}))))
    @PostMapping(value = "/tasks", consumes = "application/json")
    public ResponseEntity<BackfillTask> start(HttpServletRequest request) throws IOException {
        var task = runner.start(options(read(request)), ignored -> {});
        return ResponseEntity.accepted().location(URI.create("/api/tools/backfill/tasks/" + task.executionId())).body(task);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "204", content = @Content)
    @GetMapping("/tasks/current")
    public ResponseEntity<BackfillTask> current() {
        return runner.snapshot().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = BACKFILL_TASK_NOT_FOUND))))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = BACKFILL_INVALID_REQUEST))))
    @GetMapping("/tasks/{id}")
    public BackfillTask status(@PathVariable UUID id) {
        return runner.snapshot(id)
                .orElseThrow(() -> new IllegalArgumentException(BACKFILL_TASK_NOT_FOUND));
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = BACKFILL_TASK_NOT_FOUND))))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = BACKFILL_INVALID_REQUEST))))
    @PostMapping("/tasks/{id}/cancel")
    public BackfillTask cancel(@PathVariable UUID id) {
        var task = status(id);
        runner.cancel(id);
        return runner.snapshot(id).orElse(task);
    }

    private ArtworksBackFill.Options options(Request request) {
        var defaults = defaults();
        long delay = request.delayMs() == null ? defaults.delayMs() : request.delayMs();
        int limit = request.limit() == null ? defaults.limit() : request.limit();
        if (delay < 0) throw new InputFieldException("/delayMs", "out_of_range");
        if (limit < 0) throw new InputFieldException("/limit", "out_of_range");
        if (!target.available() || !runner.isRunning()) throw new IllegalStateException(BACKFILL_UNAVAILABLE);
        return new ArtworksBackFill.Options(target.displayName(), proxy.getHost(), proxy.getPort(), proxy.isEnabled(),
                delay, limit, Boolean.TRUE.equals(request.dryRun()), target)
                .withSources(download.getRootFolder(), Boolean.TRUE.equals(request.alwaysNetwork()))
                .withArtworkIds(request.artworkIds() == null ? List.of() : request.artworkIds().stream().distinct().toList());
    }

    private Request read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw new IllegalArgumentException(BoundedBodyRequest.HTTP_BODY_LIMIT);
        var body = JSON.readTree(new BoundedBodyRequest(request).readBody(MAX_BODY_BYTES));
        if (body == null || !body.isObject()) throw new IllegalArgumentException(BACKFILL_INVALID_REQUEST);
        if (body.path("artworkIds").isNull()) throw new InputFieldException("/artworkIds", "type");
        return JSON.treeToValue(body, Request.class);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> error(Exception failure, Locale locale) {
        String code = failure.getMessage();
        int status = switch (code == null ? "" : code) {
            case BoundedBodyRequest.HTTP_BODY_LIMIT -> 413;
            case BACKFILL_BUSY -> 409;
            case BACKFILL_UNAVAILABLE -> 503;
            case BACKFILL_TASK_NOT_FOUND -> 404;
            default -> 400;
        };
        if (status == 400) code = BACKFILL_INVALID_REQUEST;
        String message = messages.get(locale, "backfill.api." + code);
        if (failure instanceof InputFieldException field)
            return ResponseEntity.badRequest().body(new RequestFieldError(code, message, field.field(), field.reason()));
        if (failure instanceof tools.jackson.core.JacksonException binding) {
            if (binding.getCause() instanceof InputFieldException field)
                return ResponseEntity.badRequest().body(new RequestFieldError(code, message, field.field(), field.reason()));
            String field = HttpJsonInput.pointer(binding);
            if (field != null) return ResponseEntity.badRequest().body(new RequestFieldError(code, message, field, HttpJsonInput.reason(binding)));
        }
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message));
    }
}
