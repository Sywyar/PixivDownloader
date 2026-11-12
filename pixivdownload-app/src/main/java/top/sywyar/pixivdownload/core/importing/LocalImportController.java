package top.sywyar.pixivdownload.core.importing;

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
@RestController
@RequestMapping("/api/local-import")
public final class LocalImportController {
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
    @PostMapping(value = "/previews", consumes = "application/json")
    public ResponseEntity<LocalArtworkImports.Snapshot> scan(HttpServletRequest request) throws IOException {
        return ResponseEntity.accepted().body(service.scan(read(request, LocalArtworkImports.Request.class)));
    }
    @GetMapping({"/previews/{id}", "/tasks/{id}"})
    public LocalArtworkImports.Snapshot status(@PathVariable UUID id) { return service.status(id); }
    @GetMapping({"/previews/{id}/items", "/tasks/{id}/items"})
    public List<LocalArtworkImports.Item> items(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) {
        return service.items(id, offset, limit);
    }
    @PostMapping(value = "/tasks", consumes = "application/json")
    public ResponseEntity<LocalArtworkImports.Snapshot> start(HttpServletRequest request) throws IOException {
        var body = read(request, Start.class);
        if (body.previewId() == null) throw new IllegalArgumentException("INVALID_IMPORT_REQUEST");
        return ResponseEntity.accepted().body(service.start(body.previewId()));
    }
    @PostMapping({"/previews/{id}/cancel", "/tasks/{id}/cancel"})
    public LocalArtworkImports.Snapshot cancel(@PathVariable UUID id) { return service.cancel(id); }

    public record Start(UUID previewId) {}
    public record VariableError(String code, String error, String variable) implements ApiErrorResponse {}

    private static <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        if (request.getContentLengthLong() > 256 * 1024) throw new IllegalArgumentException("IMPORT_BODY_LIMIT");
        byte[] bytes = request.getInputStream().readNBytes(256 * 1024 + 1);
        if (bytes.length > 256 * 1024) throw new IllegalArgumentException("IMPORT_BODY_LIMIT");
        T value = JSON.readValue(bytes, type);
        if (value == null) throw new IllegalArgumentException("INVALID_IMPORT_REQUEST");
        return value;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, tools.jackson.core.JacksonException.class, IOException.class})
    public ResponseEntity<ApiErrorResponse> error(Exception failure, Locale locale) {
        if (failure instanceof LocalArtworkImports.VariableException variable) {
            return ResponseEntity.badRequest().body(new VariableError(variable.getMessage(), messages.get(locale,
                    "local-import.error." + variable.getMessage(), "{" + variable.variable() + "}"), variable.variable()));
        }
        String code = failure.getMessage();
        if (code == null || !code.matches("(?:IMPORT_|INVALID_|AMBIGUOUS_|UNKNOWN_)[A-Z_]{1,64}")) code = "INVALID_IMPORT_REQUEST";
        int status = switch (code) {
            case "IMPORT_BODY_LIMIT" -> 413;
            case "IMPORT_BUSY", "IMPORT_PREVIEW_NOT_READY" -> 409;
            case "IMPORT_TASK_EXPIRED" -> 404;
            case "IMPORT_UNAVAILABLE" -> 503;
            default -> 400;
        };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, messages.getOrDefault(locale,
                "local-import.error." + code, messages.get(locale, "local-import.error.INVALID_IMPORT_REQUEST"))));
    }
}
