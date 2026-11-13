package top.sywyar.pixivdownload.setup;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import java.io.IOException;
import java.util.*;

/** 仅管理员会话可管理程序密钥；响应禁止缓存，完整秘密仅创建时返回。 */
@RestController
@RequestMapping("/api/security/program-keys")
public final class ProgramKeysController {
    static final int BODY_LIMIT = 128 * 1024;
    private final ProgramKeyService keys;
    private final SetupService setup;
    private final AppMessages messages;
    private final ToolApiRequests requests;
    public ProgramKeysController(ProgramKeyService keys, SetupService setup, AppMessages messages, ToolApiRequests requests) {
        this.keys = keys; this.setup = setup; this.messages = messages; this.requests = requests;
    }
    record Edit(long revision, ProgramKeyStore.Policy policy) {}
    record Revision(long revision) {}
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) throws IOException {
        admin(request); return response(200, keys.list());
    }
    @GetMapping("/permissions")
    public ResponseEntity<?> permissions(HttpServletRequest request) {
        admin(request); return response(200, keys.permissions());
    }
    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request) throws IOException {
        admin(request);
        return response(201, keys.create(ToolApiRequests.read(request, ProgramKeyStore.Policy.class, BODY_LIMIT)));
    }
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        admin(request);
        var edit = ToolApiRequests.read(request, Edit.class, BODY_LIMIT);
        if (edit.policy() == null) throw new IllegalArgumentException("PROGRAM_KEY_PERMISSIONS");
        return response(200, keys.update(id, edit.revision(), edit.policy()));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> revoke(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        admin(request);
        keys.revoke(id, ToolApiRequests.read(request, Revision.class, BODY_LIMIT).revision());
        return response(204, null);
    }
    private void admin(HttpServletRequest request) {
        if (!setup.isAdminLoggedIn(request)) throw new LocalizedException(HttpStatus.FORBIDDEN,
                "auth.program.management", "Sign in as administrator to manage program keys");
    }
    private static ResponseEntity<?> response(int status, Object body) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> unavailable(IOException failure, Locale locale) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Program key storage unavailable: failureType={}", failure.getClass().getSimpleName());
        return response(503, ApiErrorResponse.of("PROGRAM_KEY_STORE_UNAVAILABLE", messages.get(locale, "auth.program.unavailable")));
    }
    @ExceptionHandler({IllegalStateException.class, NoSuchElementException.class})
    public ResponseEntity<?> conflict(RuntimeException failure, Locale locale) {
        String key = failure instanceof NoSuchElementException ? "auth.program.not-found"
                : "PROGRAM_KEY_CAPACITY".equals(failure.getMessage()) ? "auth.program.capacity" : "auth.program.changed";
        return response(failure instanceof NoSuchElementException ? 404 : 409,
                ApiErrorResponse.of(key, messages.get(locale, key, ProgramKeyStore.MAX_KEYS)));
    }
    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<?> invalid(Exception failure, Locale locale) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String key = switch (String.valueOf(cause.getMessage())) {
                case "PROGRAM_KEY_NAME" -> "auth.program.name-invalid";
                case "PROGRAM_KEY_PERMISSIONS" -> "auth.program.permissions-invalid";
                case "PROGRAM_KEY_NETWORKS" -> "auth.program.networks-invalid";
                default -> null;
            };
            if (key != null) return response(400, ApiErrorResponse.of(key, messages.get(locale, key,
                    ProgramKeyStore.MAX_NAME_LENGTH, ProgramKeyStore.MAX_PERMISSIONS, ProgramKeyStore.MAX_NETWORKS)));
        }
        var result = requests.invalid(failure, locale, BODY_LIMIT);
        return response(result.getStatusCode().value(), result.getBody());
    }
}
