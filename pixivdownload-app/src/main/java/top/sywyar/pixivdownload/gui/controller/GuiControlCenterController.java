package top.sywyar.pixivdownload.gui.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.gui.controlcenter.DesktopControlCenterRegistry;

/** 本机 GUI 受保护端点，读取控制中心快照并处理目录确认。 */
@RestController
public final class GuiControlCenterController {

    private final DesktopControlCenterRegistry registry;

    public GuiControlCenterController(DesktopControlCenterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/gui/control-center")
    public ResponseEntity<DesktopControlCenterRegistry.Snapshot> snapshot(HttpServletRequest request) {
        if (!NetworkUtils.isTrustedLocalRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return snapshotAdmin();
    }

    @GetMapping("/api/admin/control-center")
    public ResponseEntity<DesktopControlCenterRegistry.Snapshot> snapshotAdmin() {
        registry.refresh();
        return ResponseEntity.ok(registry.snapshot());
    }

    public record DirectoryDecision(DesktopControlCenterRegistry.Owner owner, String suggestionId,
                                    String directory, boolean dismiss) {}

    @PostMapping("/api/gui/control-center/directory")
    public ResponseEntity<?> directory(HttpServletRequest request, @RequestBody DirectoryDecision decision) {
        if (!NetworkUtils.isTrustedLocalRequest(request)) return failure(403, "DIRECTORY_LOCAL_ONLY");
        return decideDirectory(decision);
    }

    @PostMapping("/api/admin/control-center/directory")
    public ResponseEntity<?> directoryAdmin(HttpServletRequest request) throws java.io.IOException {
        return decideDirectory(top.sywyar.pixivdownload.gui.ToolApiRequests.read(request, DirectoryDecision.class, 32 * 1024));
    }

    private ResponseEntity<?> decideDirectory(DirectoryDecision decision) {
        if (decision == null || decision.owner() == null || decision.suggestionId() == null
                || decision.suggestionId().length() > 128
                || (!decision.dismiss() && (decision.directory() == null || decision.directory().length() > 4096))) {
            return failure(400, "INVALID_DIRECTORY");
        }
        try {
            registry.resolveDirectory(decision.owner(), decision.suggestionId(), decision.directory(), decision.dismiss());
            return ResponseEntity.ok(java.util.Map.of("code", decision.dismiss() ? "DISMISSED" : "SAVED"));
        } catch (IllegalStateException unavailable) {
            return failure(409, "DIRECTORY_SUGGESTION_STALE");
        } catch (java.io.IOException | IllegalArgumentException rejected) {
            return failure(400, "INVALID_DIRECTORY");
        } catch (RuntimeException failure) {
            return failure(503, "DIRECTORY_CONFIRMATION_FAILED");
        }
    }

    private static ResponseEntity<ApiErrorResponse> failure(int status, String code) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code,
                MessageBundles.get("gui.directory-suggestion.failed")));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalidBody(Exception failure, java.util.Locale locale) {
        boolean oversized = "HTTP_BODY_LIMIT".equals(failure.getMessage());
        return ResponseEntity.status(oversized ? 413 : 400).body(ApiErrorResponse.of(
                oversized ? "HTTP_BODY_LIMIT" : "INVALID_TOOL_REQUEST",
                MessageBundles.get(locale, oversized ? "tools.api.body-limit" : "tools.api.invalid-request", 32 * 1024)));
    }
}
