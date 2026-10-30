package top.sywyar.pixivdownload.pageeditor;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.page.PageCatalog;
import java.io.IOException;
import java.util.Map;

/** 管理路由的身份和 CSRF 沿用宿主过滤链，正文在分配大对象前限量读取。 */
@RestController
@RequestMapping("/api/page-editor")
public final class PageEditorController {
    private final PageCatalog catalog;
    private final PageStore store;
    private final MessageResolver messages;
    public PageEditorController(PageCatalog catalog, PageStore store, MessageResolver messages) {
        this.catalog = catalog; this.store = store; this.messages = messages;
    }
    @GetMapping("/catalog") public ResponseEntity<?> catalog() {
        return ok(Map.of("pages", catalog.pages(), "components", catalog.components(), "styleProperties", PageStyles.properties()));
    }
    @GetMapping("/documents/{id}") public ResponseEntity<?> read(@PathVariable String id) { return ok(store.read(id)); }
    @GetMapping("/documents/{id}/default") public ResponseEntity<?> defaults(@PathVariable String id) { return ok(store.defaults(id)); }
    @PostMapping("/documents/{id}/save") public ResponseEntity<?> save(@PathVariable String id, HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        return ok(store.save(id, revision(command), command.path("document")));
    }
    @PostMapping("/documents/{id}/publish") public ResponseEntity<?> publish(@PathVariable String id, HttpServletRequest request) throws IOException {
        return ok(store.publish(id, revision(command(request))));
    }
    @PostMapping("/documents/{id}/restore") public ResponseEntity<?> restore(@PathVariable String id, HttpServletRequest request) throws IOException {
        return ok(store.restore(id, revision(command(request))));
    }
    @PostMapping("/documents/{id}/preview") public ResponseEntity<?> preview(@PathVariable String id, HttpServletRequest request) throws IOException {
        return ok(store.preview(id, command(request).path("document")));
    }
    @PostMapping("/migrate") public ResponseEntity<?> migrate(HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        String value = catalog.migrate(command.path("type").asText(), command.path("publication").asText(),
                command.path("version").asInt(), command.path("configuration").toString());
        return ok(PageDocuments.parse(value));
    }
    @PostMapping("/styles/parse") public ResponseEntity<?> parseStyles(HttpServletRequest request) throws IOException {
        JsonNode css = command(request).path("css");
        if (!css.isTextual()) throw PageDocuments.invalid("PAGE_STYLE_INVALID");
        return ok(PageStyles.parse(css.textValue()));
    }
    private static JsonNode command(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(PageDocuments.MAX_BYTES + 1);
        return PageDocuments.parse(bytes);
    }
    private static long revision(JsonNode command) {
        if (!command.path("revision").isIntegralNumber() || !command.path("revision").canConvertToLong())
            throw PageDocuments.invalid("PAGE_REVISION_INVALID");
        return command.path("revision").longValue();
    }
    private static ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<?> failure(RuntimeException failure) {
        String code = failure.getMessage();
        if (code == null || !code.matches("PAGE_[A-Z_]+")) code = "PAGE_OPERATION_FAILED";
        int status = code.equals("PAGE_REVISION_CONFLICT") ? 409 : code.equals("PAGE_STORAGE_FAILED") ? 503 : 422;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(code, messages.get("error.request.param.invalid")));
    }
}
