package top.sywyar.pixivdownload.plugin.web.controller;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.web.page.DeclaredHttpHookBindings;

/** 管理界面只准备选中的声明，提交仍走原配置发布事务及权限确认。 */
@RestController
@RequestMapping("/api/web/operation-hooks/declarations")
public final class DeclaredHttpHooksController {
    private final DeclaredHttpHookBindings bindings;
    private final RequestOwnerIdentityResolver identities;
    private final AppMessages messages;
    private final Semaphore requests = new Semaphore(4);
    public DeclaredHttpHooksController(DeclaredHttpHookBindings bindings, RequestOwnerIdentityResolver identities, AppMessages messages) {
        this.bindings = bindings; this.identities = identities; this.messages = messages;
    }
    @GetMapping
    public ResponseEntity<?> list(@RequestParam String provider, HttpServletRequest request, Locale locale) {
        return run(request, locale, () -> bindings.list(provider));
    }
    @PostMapping("/prepare")
    public ResponseEntity<?> prepare(HttpServletRequest request, Locale locale) {
        return run(request, locale, () -> bindings.prepare(OperationHooksController.read(request, DeclaredHttpHookBindings.Selection.class)));
    }
    @FunctionalInterface private interface Action { Object run() throws IOException; }
    private ResponseEntity<?> run(HttpServletRequest request, Locale locale, Action action) {
        if (!identities.isAdminAuthenticated(request)) return error(403, "HOOK_DECLARATION_FORBIDDEN", locale);
        if (!requests.tryAcquire()) return error(429, "HOOK_DECLARATION_CAPACITY", locale);
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.run()); }
        catch (IOException | RuntimeException failure) {
            return error(failure instanceof IllegalStateException ? 409 : 400, "HOOK_DECLARATION_STALE_OR_UNAVAILABLE", locale);
        } finally { requests.release(); }
    }
    private ResponseEntity<ApiErrorResponse> error(int status, String code, Locale locale) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(code, messages.get(locale, "hook-declaration.error")));
    }
}
