package top.sywyar.pixivdownload.core.db.pathprefix;

import top.sywyar.pixivdownload.common.InvalidPage;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** 管理员路径前缀修正，复用桌面的验证与事务；不移动磁盘文件。 */
@HttpApiGroup("core-files")
@RestController
@RequestMapping("/api/tools/path-prefixes")
public final class PathPrefixToolsController {
    private static final int BODY_LIMIT = 256 * 1024;
    private final PathPrefixMigrationService service;
    private final ToolApiRequests requests;
    private final top.sywyar.pixivdownload.i18n.AppMessages messages;
    public PathPrefixToolsController(PathPrefixMigrationService service, ToolApiRequests requests,
                                     top.sywyar.pixivdownload.i18n.AppMessages messages) {
        this.service = service; this.requests = requests; this.messages = messages;
    }
    public record Page(List<PathPrefixMigrationService.PathPrefixView> prefixes, int total,
                       PathPrefixMigrationService.SymbolicRootStatus symbolicRoot) {}
    public record Changes(@HttpApiNullable List<PathPrefixMigrationService.PathPrefixUpdate> updates,
                          @HttpApiNullable List<String> registerPaths) {}
    public record Pin(String path) {}

    @Parameter(name = "offset", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
    @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "100", minimum = "1", maximum = "1000"))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping public Page list(@RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "100") int limit) {
        InvalidPage.check(offset, limit, 1000, null);
        var all = service.list();
        return new Page(all.stream().skip(offset).limit(limit).toList(), all.size(), service.symbolicRootStatus());
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Changes.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PathPrefixMigrationService.PathPrefixMigrationResult.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ToolApiRequests.InvalidRequest.class, Rejected.class})))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Rejected.class)))
    @top.sywyar.pixivdownload.core.web.KnownErrorCodes(status = "500", value = "PATH_PREFIX_WRITE_FAILED")
    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> apply(HttpServletRequest request, Locale locale) throws IOException {
        var body = ToolApiRequests.readStrictStrings(request, Changes.class, BODY_LIMIT);
        var updates = body.updates() == null ? List.<PathPrefixMigrationService.PathPrefixUpdate>of() : body.updates();
        var paths = body.registerPaths() == null ? List.<String>of() : body.registerPaths();
        if (updates.isEmpty() && paths.isEmpty()) throw new InputFieldException("", "invalid_value");
        for (int i = 0; i < updates.size(); i++) {
            var update = updates.get(i);
            String field = "/updates/" + i;
            if (update == null) throw new InputFieldException(field, "required");
            if (update.id() < 0) throw new InputFieldException(field + "/id", "out_of_range");
            validatePath(update.path(), field + "/path");
        }
        for (int i = 0; i < paths.size(); i++) validatePath(paths.get(i), "/registerPaths/" + i);
        return result(service.apply(updates, paths), locale);
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Pin.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PathPrefixMigrationService.PathPrefixMigrationResult.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ToolApiRequests.InvalidRequest.class, Rejected.class})))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Rejected.class)))
    @top.sywyar.pixivdownload.core.web.KnownErrorCodes(status = "500", value = "PATH_PREFIX_WRITE_FAILED")
    @PostMapping(value = "/pin", consumes = "application/json")
    public ResponseEntity<?> pin(HttpServletRequest request, Locale locale) throws IOException {
        var body = ToolApiRequests.readStrictStrings(request, Pin.class, BODY_LIMIT);
        validatePath(body.path(), "/path");
        return result(service.pinSymbolicRoot(body.path()), locale);
    }
    private static void validatePath(String path, String field) {
        if (path == null || path.isBlank()) throw new InputFieldException(field, "required");
        if (path.length() > 4096) throw new InputFieldException(field, "too_large");
        if (path.chars().anyMatch(Character::isISOControl)) throw new InputFieldException(field, "invalid_format");
    }
    public record Rejected(String code, String error, boolean success, int applied,
                           List<PathPrefixMigrationService.PrefixError> errors) implements ApiErrorResponse {}
    private ResponseEntity<?> result(PathPrefixMigrationService.PathPrefixMigrationResult result, Locale locale) {
        if (result.success()) return ResponseEntity.ok(result);
        if (result.errors().stream().anyMatch(error -> "write-failed".equals(error.reason()))) {
            return ResponseEntity.internalServerError().body(new Rejected("PATH_PREFIX_WRITE_FAILED",
                    messages.get(locale, "error.io"), false, result.applied(), result.errors()));
        }
        return ResponseEntity.badRequest().body(new Rejected("PATH_PREFIX_REJECTED",
                messages.get(locale, "tools.api.invalid-request"), false, result.applied(), result.errors()));
    }
    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) { return requests.invalid(failure, locale, BODY_LIMIT); }
}
