package top.sywyar.pixivdownload.core.db.pathprefix;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** 管理员路径前缀修正，复用桌面的验证与事务；不移动磁盘文件。 */
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
    public record Changes(List<PathPrefixMigrationService.PathPrefixUpdate> updates, List<String> registerPaths) {}
    public record Pin(String path) {}

    @GetMapping public Page list(@RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "100") int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException();
        var all = service.list();
        return new Page(all.stream().skip(offset).limit(limit).toList(), all.size(), service.symbolicRootStatus());
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> apply(HttpServletRequest request, Locale locale) throws IOException {
        var body = ToolApiRequests.read(request, Changes.class, BODY_LIMIT);
        var updates = body.updates() == null ? List.<PathPrefixMigrationService.PathPrefixUpdate>of() : body.updates();
        var paths = body.registerPaths() == null ? List.<String>of() : body.registerPaths();
        if (updates.isEmpty() && paths.isEmpty() || updates.stream().anyMatch(value -> value == null || value.id() < 0))
            throw new IllegalArgumentException();
        updates.forEach(value -> validatePath(value.path()));
        paths.forEach(PathPrefixToolsController::validatePath);
        return result(service.apply(updates, paths), locale);
    }
    @PostMapping(value = "/pin", consumes = "application/json")
    public ResponseEntity<?> pin(HttpServletRequest request, Locale locale) throws IOException {
        var body = ToolApiRequests.read(request, Pin.class, BODY_LIMIT);
        validatePath(body.path());
        return result(service.pinSymbolicRoot(body.path()), locale);
    }
    private static void validatePath(String path) {
        if (path == null || path.isBlank() || path.length() > 4096 || path.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException();
    }
    public record Rejected(String code, String error, boolean success, int applied,
                           List<PathPrefixMigrationService.PrefixError> errors) implements ApiErrorResponse {}
    private ResponseEntity<?> result(PathPrefixMigrationService.PathPrefixMigrationResult result, Locale locale) {
        if (result.success()) return ResponseEntity.ok(result);
        return ResponseEntity.badRequest().body(new Rejected("PATH_PREFIX_REJECTED",
                messages.get(locale, "tools.api.invalid-request"), false, result.applied(), result.errors()));
    }
    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) { return requests.invalid(failure, locale, BODY_LIMIT); }
}
