package top.sywyar.pixivdownload.pageeditor;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.pageeditor.components.ComponentLibraryController;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import top.sywyar.pixivdownload.plugin.api.web.page.PageCatalog;
import top.sywyar.pixivdownload.plugin.api.web.page.PageComponentCategory;
import top.sywyar.pixivdownload.plugin.api.web.page.PageInteraction;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 管理路由的身份和 CSRF 沿用宿主过滤链，正文在分配大对象前限量读取。 */
@HttpApiGroup("page-editor-editor")
@RestController
@RequestMapping("/api/page-editor")
public final class PageEditorController {
    private final PageCatalog catalog;
    private final PageStore store;
    private final MessageResolver messages;
    private final top.sywyar.pixivdownload.pageeditor.components.ComponentLibrary library;
    public PageEditorController(PageCatalog catalog, PageStore store, MessageResolver messages,
            top.sywyar.pixivdownload.pageeditor.components.ComponentLibrary library) {
        this.catalog = catalog; this.store = store; this.messages = messages; this.library = library;
    }

    @Schema
    public record Catalog(List<PageCatalog.AvailablePage> pages, List<PageCatalog.AvailableComponent> components,
                          List<String> styleProperties, List<PageComponentCategory> componentCategories,
                          List<ComponentLibraryController.Summary> customComponents) { }
    @Schema
    public record Frame(Map<String, Object> component) { }
    @Schema
    public record ProjectPage(List<Frame> frames) { }
    @Schema
    public record Project(List<ProjectPage> pages, List<Map<String, Object>> styles) { }
    @Schema(requiredProperties = {"format", "pageId", "title", "project"})
    public record Document(@Schema(minimum = "1", maximum = "1") int format, String pageId, String title, Project project,
                           List<Map<String, Object>> connections, List<Map<String, Object>> hooks,
                           List<Map<String, Object>> actions) { }
    @Schema
    public record State(long revision, long publicationRevision, Document draft, Document published, Document previous,
                        Boolean draftSaveFailed, List<String> draftSaveFailures) { }
    @Schema
    public record PreviewView(int format, String pageId, String title, String namespace, String publication,
                              String previewModuleUrl, List<String> stylesheets, List<PageInteraction.Target> targets,
                              Map<String, Object> body, List<Map<String, Object>> styles,
                              List<Map<String, Object>> connections, List<Map<String, Object>> actions) { }
    @Schema(requiredProperties = {"revision", "document"})
    public record SaveCommand(long revision, Document document) { }
    @Schema(requiredProperties = {"document"})
    public record PreviewCommand(Document document) { }
    @Schema(requiredProperties = {"revision", "decision"})
    public record PublishCommand(long revision, PageStore.Decision decision, List<PageStore.Draft> drafts) { }
    @Schema(requiredProperties = {"revision", "restoring"})
    public record PrepareCommand(long revision, boolean restoring, List<PageStore.Draft> drafts) { }
    @Schema(requiredProperties = {"type", "publication", "version", "configuration"})
    public record MigrationCommand(String type, String publication, int version, Map<String, Object> configuration) { }
    @Schema(requiredProperties = {"css"})
    public record StylesCommand(String css) { }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Catalog.class)))
    @GetMapping("/catalog") public ResponseEntity<?> catalog() {
        return ok(Map.of("pages", catalog.pages(), "components", catalog.components(), "styleProperties", PageStyles.properties(),
                "componentCategories", catalog.componentCategories(), "customComponents", library.catalog()));
    }
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = State.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/documents/{id}") public ResponseEntity<?> read(@PathVariable String id) { return ok(store.read(id)); }
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Document.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/documents/{id}/default") public ResponseEntity<?> defaults(@PathVariable String id) { return ok(store.defaults(id)); }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Read the document first and submit its revision and edited document.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = SaveCommand.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = State.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/documents/{id}/save") public ResponseEntity<?> save(@PathVariable String id, HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        return ok(store.save(id, revision(command), command.path("document")));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Submit the decision returned by prepare and approve the intended changes.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = PublishCommand.class),
                    examples = @ExampleObject(value = "{\"revision\":1,\"decision\":{\"revision\":1,\"fingerprint\":\"<from prepare>\",\"approvals\":[]},\"drafts\":[]}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = State.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/documents/{id}/publish") public ResponseEntity<?> publish(@PathVariable String id, HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        return ok(store.publish(id, revision(command), PageDocuments.JSON.treeToValue(command.path("decision"), PageStore.Decision.class), false, drafts(command)));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Prepare with restoring set to true, then submit the decision.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = PublishCommand.class),
                    examples = @ExampleObject(value = "{\"revision\":1,\"decision\":{\"revision\":1,\"fingerprint\":\"<from prepare>\",\"approvals\":[]},\"drafts\":[]}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = State.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/documents/{id}/restore") public ResponseEntity<?> restore(@PathVariable String id, HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        return ok(store.publish(id, revision(command), PageDocuments.JSON.treeToValue(command.path("decision"), PageStore.Decision.class), true, drafts(command)));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Check the current revision and review publication effects before publishing.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = PrepareCommand.class),
                    examples = @ExampleObject(value = "{\"revision\":1,\"restoring\":false,\"drafts\":[]}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PageStore.Preview.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/documents/{id}/prepare") public ResponseEntity<?> prepare(@PathVariable String id, HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        if (!command.path("restoring").isBoolean()) throw PageDocuments.invalid("PAGE_DOCUMENT_INVALID");
        return ok(store.prepare(id, revision(command), command.path("restoring").asBoolean(), drafts(command)));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Supply the edited document from the document endpoint.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = PreviewCommand.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PreviewView.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/documents/{id}/preview") public ResponseEntity<?> preview(@PathVariable String id, HttpServletRequest request) throws IOException {
        return ok(store.preview(id, command(request).path("document")));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Read type and publication from the component catalog.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = MigrationCommand.class),
                    examples = @ExampleObject(value = "{\"type\":\"<component type>\",\"publication\":\"<publication id>\",\"version\":1,\"configuration\":{}}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/migrate") public ResponseEntity<?> migrate(HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        String value = catalog.migrate(command.path("type").asString(), command.path("publication").asString(),
                command.path("version").asInt(), command.path("configuration").toString());
        return ok(PageDocuments.parse(value));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Parse CSS declarations.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = StylesCommand.class),
                    examples = @ExampleObject(value = "{\"css\":\"color: red\"}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", additionalPropertiesSchema = String.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/styles/parse") public ResponseEntity<?> parseStyles(HttpServletRequest request) throws IOException {
        JsonNode css = command(request).path("css");
        if (!css.isString()) throw PageDocuments.invalid("PAGE_STYLE_INVALID");
        return ok(PageStyles.parse(css.stringValue()));
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
    private static java.util.List<PageStore.Draft> drafts(JsonNode command) {
        if (!command.has("drafts")) return java.util.List.of();
        JsonNode candidates = command.path("drafts");
        if (!candidates.isArray() || candidates.size() >= 128) throw PageDocuments.invalid("PAGE_DOCUMENT_BUDGET");
        var drafts = new java.util.ArrayList<PageStore.Draft>();
        for (JsonNode candidate : candidates) {
            if (!candidate.path("page").isString()) throw PageDocuments.invalid("PAGE_DOCUMENT_INVALID");
            drafts.add(new PageStore.Draft(candidate.path("page").stringValue(), revision(candidate)));
        }
        return java.util.List.copyOf(drafts);
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
