package top.sywyar.pixivdownload.core.importing;

import static top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Request.INVALID_IMPORT_REQUEST;
import static top.sywyar.pixivdownload.core.importing.LocalImportService.INVALID_IMPORT_PAGE;
import static top.sywyar.pixivdownload.core.importing.LocalImportService.IMPORT_BUSY;
import static top.sywyar.pixivdownload.core.importing.LocalImportService.IMPORT_PREVIEW_NOT_READY;
import static top.sywyar.pixivdownload.core.importing.LocalImportService.IMPORT_TASK_EXPIRED;
import static top.sywyar.pixivdownload.core.importing.LocalImportService.IMPORT_UNAVAILABLE;

import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.Parameter;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;

/** 管理员本地文件登记入口；沿用宿主路由鉴权与 CSRF，异步接纳不等于完成。 */
@HttpApiGroup("core-import")
@RestController
@RequestMapping("/api/local-import")
public final class LocalImportController {
    static final String IMPORT_BODY_LIMIT = "IMPORT_BODY_LIMIT";

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final LocalImportService service;
    private final AppMessages messages;
    public LocalImportController(LocalImportService service, AppMessages messages) { this.service = service; this.messages = messages; }

    @GetMapping("/presets") public List<LocalArtworkImports.Preset> presets() { return service.presets(); }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = LocalArtworkImports.Request.class)))
    @ApiResponse(responseCode = "202", description = "Accepted", useReturnTypeSchema = true,
            links = @io.swagger.v3.oas.annotations.links.Link(name = "status",
                    operationRef = "#/paths/~1api~1local-import~1previews~1%7Bid%7D/get",
                    parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/id")))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ApiErrorResponse.Basic.class, VariableError.class})))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = IMPORT_BUSY))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = IMPORT_BODY_LIMIT))))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = {IMPORT_UNAVAILABLE, "auth.maintenance", "auth.setup-required", "auth.program.unavailable"}))))
    @PostMapping(value = "/previews", consumes = "application/json")
    public ResponseEntity<LocalArtworkImports.Snapshot> scan(HttpServletRequest request) throws IOException {
        return ResponseEntity.accepted().body(service.scan(read(request, LocalArtworkImports.Request.class)));
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = IMPORT_TASK_EXPIRED))))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = INVALID_IMPORT_REQUEST))))
    @GetMapping({"/previews/{id}", "/tasks/{id}"})
    public LocalArtworkImports.Snapshot status(@PathVariable UUID id) { return service.status(id); }
    @Parameter(name = "offset", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
    @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1", maximum = "100"))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = {INVALID_IMPORT_REQUEST, INVALID_IMPORT_PAGE}))))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = IMPORT_TASK_EXPIRED))))
    @GetMapping({"/previews/{id}/items", "/tasks/{id}/items"})
    public List<LocalArtworkImports.Item> items(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) {
        return service.items(id, offset, limit);
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Start.class)))
    @ApiResponse(responseCode = "202", description = "Accepted", useReturnTypeSchema = true,
            links = @io.swagger.v3.oas.annotations.links.Link(name = "status",
                    operationRef = "#/paths/~1api~1local-import~1tasks~1%7Bid%7D/get",
                    parameters = @io.swagger.v3.oas.annotations.links.LinkParameter(name = "id", expression = "$response.body#/id")))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = INVALID_IMPORT_REQUEST))))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = IMPORT_TASK_EXPIRED))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = IMPORT_PREVIEW_NOT_READY))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = IMPORT_BODY_LIMIT))))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = {IMPORT_UNAVAILABLE, "auth.maintenance", "auth.setup-required", "auth.program.unavailable"}))))
    @PostMapping(value = "/tasks", consumes = "application/json")
    public ResponseEntity<LocalArtworkImports.Snapshot> start(HttpServletRequest request) throws IOException {
        var body = read(request, Start.class);
        if (body.previewId() == null) throw new IllegalArgumentException(INVALID_IMPORT_REQUEST);
        return ResponseEntity.accepted().body(service.start(body.previewId()));
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = IMPORT_TASK_EXPIRED))))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = INVALID_IMPORT_REQUEST))))
    @PostMapping({"/previews/{id}/cancel", "/tasks/{id}/cancel"})
    public LocalArtworkImports.Snapshot cancel(@PathVariable UUID id) { return service.cancel(id); }

    public record Start(UUID previewId) {}
    public record VariableError(
            @Schema(allowableValues = {LocalArtworkImports.VariableException.UNAVAILABLE, LocalArtworkImports.VariableException.UNKNOWN})
            String code, String error, String variable) implements ApiErrorResponse {}

    private static <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        if (request.getContentLengthLong() > 256 * 1024) throw new IllegalArgumentException(IMPORT_BODY_LIMIT);
        byte[] bytes = request.getInputStream().readNBytes(256 * 1024 + 1);
        if (bytes.length > 256 * 1024) throw new IllegalArgumentException(IMPORT_BODY_LIMIT);
        T value = JSON.readValue(bytes, type);
        if (value == null) throw new IllegalArgumentException(INVALID_IMPORT_REQUEST);
        return value;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, tools.jackson.core.JacksonException.class, IOException.class})
    public ResponseEntity<ApiErrorResponse> error(Exception failure, Locale locale) {
        if (failure instanceof LocalArtworkImports.VariableException variable) {
            return ResponseEntity.badRequest().body(new VariableError(variable.getMessage(), messages.get(locale,
                    "local-import.error." + variable.getMessage(), "{" + variable.variable() + "}"), variable.variable()));
        }
        String code = failure.getMessage();
        if (code == null || !code.matches("(?:IMPORT_|INVALID_|AMBIGUOUS_|UNKNOWN_)[A-Z_]{1,64}")) code = INVALID_IMPORT_REQUEST;
        int status = switch (code) {
            case IMPORT_BODY_LIMIT -> 413;
            case IMPORT_BUSY, IMPORT_PREVIEW_NOT_READY -> 409;
            case IMPORT_TASK_EXPIRED -> 404;
            case IMPORT_UNAVAILABLE -> 503;
            default -> 400;
        };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, messages.getOrDefault(locale,
                "local-import.error." + code, messages.get(locale, "local-import.error.INVALID_IMPORT_REQUEST"))));
    }
}
