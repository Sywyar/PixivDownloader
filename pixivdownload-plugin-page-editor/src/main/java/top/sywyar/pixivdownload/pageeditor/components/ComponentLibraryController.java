package top.sywyar.pixivdownload.pageeditor.components;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** 所有源码写入都要求明确的同源 Origin，匿名端点只读取已发布版本或短期预览。 */
@HttpApiGroup("page-editor-editor")
@RestController
@RequestMapping("/api/page-editor/components")
public final class ComponentLibraryController {
    private final ComponentLibrary library;
    private final ComponentPreviews previews;
    private final MessageResolver messages;
    public ComponentLibraryController(ComponentLibrary library, ComponentPreviews previews, MessageResolver messages) {
        this.library = library; this.previews = previews; this.messages = messages;
    }

    @Schema
    public record Property(@Schema(allowableValues = {"string", "number", "boolean"}) String type,
                           String label, @JsonProperty("default") Object defaultValue) { }
    @Schema(requiredProperties = {"name", "html", "css", "javascript", "properties"})
    public record Source(String name, String html, String css, String javascript, Map<String, Property> properties) { }
    @Schema
    public record Summary(String id, String name, long revision, String latest, boolean enabled, Boolean unavailable) { }
    @Schema
    public record State(String id, long revision, Source draft, List<String> versions, String latest, boolean enabled) { }
    @Schema(requiredProperties = {"id", "revision", "source"})
    public record SaveCommand(@Schema(format = "uuid") String id, long revision, Source source) { }
    @Schema(requiredProperties = {"source"})
    public record PreviewCommand(Source source) { }
    @Schema(requiredProperties = {"revision"})
    public record PublishCommand(long revision) { }
    @Schema(requiredProperties = {"revision", "enabled"})
    public record AvailabilityCommand(long revision, boolean enabled) { }

    public record PreviewReference(String token, String url) { }
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = Summary.class))))
    @GetMapping public ResponseEntity<?> catalog() { return ok(library.catalog()); }
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = State.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/{id}") public ResponseEntity<?> read(@PathVariable String id) { return ok(library.read(id)); }
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Source.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/{id}/versions/{version}") public ResponseEntity<?> version(@PathVariable String id, @PathVariable String version) {
        return ok(library.version(id, version));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Supply the current revision, component source and a same-origin Origin header.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = SaveCommand.class),
                    examples = @ExampleObject(value = "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"revision\":0,\"source\":{\"name\":\"Example\",\"html\":\"<p>Hello</p>\",\"css\":\"\",\"javascript\":\"\",\"properties\":{}}}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = State.class)))
    @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string", format = "uri"))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/save") public ResponseEntity<?> save(HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        if (!command.path("id").isString()) throw ComponentLibrary.invalid("PAGE_COMPONENT_ID_INVALID");
        return ok(library.save(command.path("id").asString(), revision(command), command.path("source")));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Publish the saved revision. Send the same-origin Origin header.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = PublishCommand.class),
                    examples = @ExampleObject(value = "{\"revision\":1}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = State.class)))
    @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string", format = "uri"))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/{id}/publish") public ResponseEntity<?> publish(@PathVariable String id, HttpServletRequest request) throws IOException {
        return ok(library.publish(id, revision(command(request))));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Set availability for this revision. Send the same-origin Origin header.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = AvailabilityCommand.class),
                    examples = @ExampleObject(value = "{\"revision\":1,\"enabled\":true}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = State.class)))
    @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string", format = "uri"))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/{id}/availability") public ResponseEntity<?> availability(@PathVariable String id, HttpServletRequest request) throws IOException {
        JsonNode command = command(request);
        if (!command.path("enabled").isBoolean()) throw ComponentLibrary.invalid("PAGE_COMPONENT_SOURCE_INVALID");
        return ok(library.availability(id, revision(command), command.path("enabled").asBoolean()));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Create a preview for a component source. Send the same-origin Origin header.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = PreviewCommand.class),
                    examples = @ExampleObject(value = "{\"source\":{\"name\":\"Example\",\"html\":\"<p>Hello</p>\",\"css\":\"\",\"javascript\":\"\",\"properties\":{}}}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PreviewReference.class)))
    @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string", format = "uri"))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/preview") public ResponseEntity<?> preview(HttpServletRequest request) throws IOException {
        String token = previews.create(command(request).path("source"));
        return ok(new PreviewReference(token, "/page-components/previews/" + token + "/frame"));
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Close the preview using its token and a same-origin Origin header.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(type = "boolean")))
    @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string", format = "uri"))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
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
