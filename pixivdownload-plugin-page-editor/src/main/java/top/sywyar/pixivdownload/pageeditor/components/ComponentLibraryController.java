package top.sywyar.pixivdownload.pageeditor.components;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import java.io.IOException;
import java.net.URI;

/** 所有源码写入都要求明确的同源 Origin，匿名端点只读取已发布版本或短期预览。 */
@RestController
@RequestMapping("/api/page-editor/components")
public final class ComponentLibraryController {
    private final ComponentLibrary library;
    private final ComponentPreviews previews;
    private final MessageResolver messages;
    public ComponentLibraryController(ComponentLibrary library, ComponentPreviews previews, MessageResolver messages) {
        this.library = library; this.previews = previews; this.messages = messages;
    }
    public record PreviewReference(String token, String url) { }
    @GetMapping public ResponseEntity<?> catalog() { return ok(library.catalog()); }
    @GetMapping("/{id}") public ResponseEntity<?> read(@PathVariable String id) { return ok(library.read(id)); }
    @GetMapping("/{id}/versions/{version}") public ResponseEntity<?> version(@PathVariable String id, @PathVariable String version) {
        return ok(library.version(id, version));
    }
    @PostMapping("/save") public ResponseEntity<?> save(HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        if (!command.path("id").isTextual()) throw ComponentLibrary.invalid("PAGE_COMPONENT_ID_INVALID");
        return ok(library.save(command.path("id").asText(), revision(command), command.path("source")));
    }
    @PostMapping("/{id}/publish") public ResponseEntity<?> publish(@PathVariable String id, HttpServletRequest request) throws IOException {
        return ok(library.publish(id, revision(command(request))));
    }
    @PostMapping("/{id}/availability") public ResponseEntity<?> availability(@PathVariable String id, HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        if (!command.path("enabled").isBoolean()) throw ComponentLibrary.invalid("PAGE_COMPONENT_SOURCE_INVALID");
        return ok(library.availability(id, revision(command), command.path("enabled").asBoolean()));
    }
    @PostMapping("/preview") public ResponseEntity<?> preview(HttpServletRequest request) throws IOException {
        String token = previews.create(command(request).path("source"));
        return ok(new PreviewReference(token, "/page-components/previews/" + token + "/frame"));
    }
    @PostMapping("/previews/{token}/close") public ResponseEntity<?> close(@PathVariable String token, HttpServletRequest request) throws IOException {
        command(request); previews.remove(token); return ok(true);
    }
    private static JsonNode command(HttpServletRequest request) throws IOException {
        if (!origin(request).equals(request.getHeader("Origin"))) throw ComponentLibrary.invalid("PAGE_COMPONENT_ORIGIN");
        return ComponentLibrary.parse(request.getInputStream().readNBytes(ComponentLibrary.MAX_SOURCE_BYTES + 1));
    }
    static String origin(HttpServletRequest request) {
        try {
            var uri = new URI(request.getRequestURL().toString());
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw ComponentLibrary.invalid("PAGE_COMPONENT_ORIGIN");
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (java.net.URISyntaxException error) { throw ComponentLibrary.invalid("PAGE_COMPONENT_ORIGIN"); }
    }
    private static long revision(JsonNode command) {
        JsonNode revision = command.path("revision");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong() || revision.asLong() < 0)
            throw ComponentLibrary.invalid("PAGE_REVISION_INVALID");
        return revision.asLong();
    }
    private static ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<?> failure(RuntimeException failure) {
        String code = failure.getMessage();
        if (code == null || !code.matches("PAGE_[A-Z_]+")) code = "PAGE_OPERATION_FAILED";
        int status = code.equals("PAGE_REVISION_CONFLICT") ? 409 : code.equals("PAGE_COMPONENT_ORIGIN") ? 403
                : code.equals("PAGE_COMPONENT_STORAGE_FAILED") ? 503 : 422;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(code, messages.get("error.request.param.invalid")));
    }
}
