package top.sywyar.pixivdownload.transfer;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.*;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 管理员归档预览、选择确认与实际恢复结果；确认复用下一进程的离线事务。 */
@HttpApiGroup("core-environment")
@RestController
@RequestMapping("/api/settings/environment")
public final class EnvironmentTransferController {
    private static final int BODY_LIMIT = 256 * 1024;
    private final DesktopEnvironmentTransfer transfer;
    private final ToolApiRequests requests;
    private final AppMessages messages;
    private final EnvironmentExportService exports;

    public EnvironmentTransferController(DesktopEnvironmentTransfer transfer, ToolApiRequests requests, AppMessages messages,
                                         EnvironmentExportService exports) {
        this.transfer = transfer; this.requests = requests; this.messages = messages; this.exports = exports;
    }

    public record Inspect(String archive, char[] password) {
        @Override public String toString() { return "Inspect[redacted]"; }
    }
    public record PathMapping(FieldKey field, String path) {}
    @Schema(requiredProperties = {"token", "selected", "mappedPaths", "fileGroups", "databasePaths"})
    public record Restore(String token, Set<FieldKey> selected, List<PathMapping> mappedPaths, char[] password,
                          boolean includeDatabases, Set<FieldKey> fileGroups, Map<Long, String> databasePaths) {
        @Override public String toString() { return "Restore[redacted]"; }
    }
    public record Accepted(String code) {}

    @GetMapping("/installation") public Installation installation() { return transfer.installation(); }
    @GetMapping("/restore") public RestoreState state() throws IOException { return transfer.restoreState(); }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = EnvironmentExportService.Request.class)))
    @ApiResponse(responseCode = "202", description = "Accepted", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/exports", consumes = "application/json")
    public ResponseEntity<EnvironmentExportService.View> export(HttpServletRequest request) throws IOException {
        return ResponseEntity.accepted().body(exports.start(ToolApiRequests.read(request, EnvironmentExportService.Request.class, BODY_LIMIT)));
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "204", content = @Content)
    @GetMapping("/exports")
    public ResponseEntity<EnvironmentExportService.View> exportState() throws IOException {
        var state = exports.current();
        return state == null ? ResponseEntity.noContent().build() : ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(state);
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/exports/{id}/archive")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> archive(@PathVariable UUID id) throws IOException {
        Path archive = exports.archive(id);
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header("Content-Disposition", "attachment; filename=environment.pdbackup")
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "sandbox")
                .body(new org.springframework.core.io.InputStreamResource(java.nio.file.Files.newInputStream(archive, java.nio.file.LinkOption.NOFOLLOW_LINKS)));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Inspect.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/preview", consumes = "application/json")
    public Preview preview(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, Inspect.class, BODY_LIMIT);
        try {
            password(body.password());
            return transfer.previewConfiguration(path(body.archive()), body.password());
        } finally { clear(body.password()); }
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Restore.class)))
    @ApiResponse(responseCode = "202", description = "Accepted", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/restore", consumes = "application/json")
    public ResponseEntity<Accepted> restore(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, Restore.class, BODY_LIMIT);
        try {
            password(body.password());
            if (body.token() == null || body.token().isBlank() || body.token().length() > 128 || body.selected() == null
                    || body.mappedPaths() == null || body.fileGroups() == null || body.databasePaths() == null)
                throw new IllegalArgumentException();
            body.selected().forEach(EnvironmentTransferController::validateFieldKey);
            body.fileGroups().forEach(EnvironmentTransferController::validateFieldKey);
            var paths = new LinkedHashMap<FieldKey, String>();
            for (var mapping : body.mappedPaths()) {
                if (mapping == null || mapping.field() == null || !body.selected().contains(mapping.field())
                        || paths.putIfAbsent(mapping.field(), path(mapping.path()).toString()) != null)
                    throw new IllegalArgumentException();
            }
            for (var mapping : body.databasePaths().entrySet()) {
                if (mapping.getKey() == null || mapping.getKey() < 0) throw new IllegalArgumentException();
                path(mapping.getValue());
            }
            transfer.importConfiguration(body.token(), body.selected(), paths, body.password(), body.includeDatabases(),
                    body.fileGroups(), body.databasePaths());
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Environment restore accepted through HTTP fields={} groups={} databases={}",
                    body.selected().size(), body.fileGroups().size(), body.includeDatabases());
            return ResponseEntity.accepted().body(new Accepted("RESTORE_PENDING"));
        } finally { clear(body.password()); }
    }

    private static void validateFieldKey(FieldKey field) {
        if (field == null || field.owner() == null || field.owner().isBlank() || field.key() == null || field.key().isBlank())
            throw new IllegalArgumentException();
    }

    private static Path path(String value) {
        if (value == null || value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException();
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException();
        return path.normalize();
    }
    private static void password(char[] value) { if (value != null && value.length > 4096) throw new IllegalArgumentException(); }
    private static void clear(char[] value) { if (value != null) Arrays.fill(value, '\0'); }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiErrorResponse> failed(IOException failure, Locale locale) {
        String code = failure.getMessage();
        if (code == null || !code.matches("transfer\\.[a-z0-9-]{1,80}")) code = "transfer.failed";
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Environment HTTP operation failed code={} type={}", code, failure.getClass().getSimpleName());
        int status = Set.of("transfer.busy", "transfer.preview-changed", "transfer.target-changed").contains(code) ? 409
                : code.equals("transfer.failed") || code.equals("transfer.restart-failed") ? 500 : 400;
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code,
                messages.getOrDefault(locale, code, messages.get(locale, "transfer.failed"))));
    }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) {
        return requests.invalid(failure, locale, BODY_LIMIT);
    }
}
