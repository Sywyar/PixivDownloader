package top.sywyar.pixivdownload.schedule.batch;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

/** 管理员手动批次入口，来源与定义格式由既有来源清单声明。 */
@RestController
@RequestMapping("/api/download/batches")
public final class ManualBatchController {
    private static final int BODY_LIMIT = 8 * 1024 * 1024;
    private final ManualBatchService service;
    private final ToolApiRequests requests;
    private final AppMessages messages;

    public ManualBatchController(ManualBatchService service, ToolApiRequests requests, AppMessages messages) {
        this.service = service; this.requests = requests; this.messages = messages;
    }
    @PostMapping(consumes = "application/json")
    public ResponseEntity<ManualBatchService.View> create(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, ManualBatchService.Request.class, BODY_LIMIT);
        String credential = AcquisitionCredentialResolver.resolve(request.getHeader(AcquisitionCredentialResolver.HEADER_NAME));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.create(body, credential));
    }
    @GetMapping public ResponseEntity<ManualBatchService.View> current() {
        var view = service.current();
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
    @GetMapping("/{id}") public ResponseEntity<ManualBatchService.View> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }
    @GetMapping("/{id}/items") public ResponseEntity<ManualBatchService.Items> items(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.items(id, offset, limit));
    }
    @PostMapping("/{id}/pause") public ManualBatchService.View pause(@PathVariable UUID id) { return service.stop(id, true); }
    @PostMapping("/{id}/cancel") public ManualBatchService.View cancel(@PathVariable UUID id) { return service.stop(id, false); }
    @PostMapping("/{id}/resume") public ManualBatchService.View resume(@PathVariable UUID id) { return service.resume(id, false); }
    @PostMapping("/{id}/retry") public ManualBatchService.View retry(@PathVariable UUID id) { return service.resume(id, true); }
    public record Credentials(int expectedRun) {}
    @PutMapping(value = "/{id}/credentials", consumes = "application/json")
    public ManualBatchService.View credentials(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.read(request, Credentials.class, 16 * 1024);
        String credential = AcquisitionCredentialResolver.resolve(request.getHeader(AcquisitionCredentialResolver.HEADER_NAME));
        return service.credentials(id, body.expectedRun(), credential);
    }
    @PostMapping(value = "/{id}/resolve", consumes = "application/json")
    public ManualBatchService.View resolve(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        return service.resolve(id, ToolApiRequests.read(request, ManualBatchService.Resolve.class, 16 * 1024));
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { service.delete(id); return ResponseEntity.noContent().build(); }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale, HttpServletRequest request) {
        String path = request.getRequestURI();
        return requests.invalid(failure, locale, path.endsWith("/credentials") || path.endsWith("/resolve") ? 16 * 1024 : BODY_LIMIT);
    }
    @ExceptionHandler({IllegalStateException.class, NoSuchElementException.class})
    public ResponseEntity<ApiErrorResponse> rejected(RuntimeException failure, Locale locale) {
        String code = failure.getMessage();
        boolean known = code != null && code.matches("batch\\.[a-z.-]{1,80}");
        if (!known) code = "batch.operation-failed";
        int status = !known ? 500 : failure instanceof NoSuchElementException ? 404 : 409;
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, messages.getOrDefault(locale, code,
                messages.get(locale, "desktop.ui.action.failed"))));
    }
}
