package top.sywyar.pixivdownload.schedule.batch;

import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.links.Link;
import io.swagger.v3.oas.annotations.links.LinkParameter;
import io.swagger.v3.oas.annotations.media.SchemaProperty;

import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleManagementException;

import java.io.IOException;
import java.net.URI;
import top.sywyar.pixivdownload.setup.ProgramAccess;
import top.sywyar.pixivdownload.web.RequestFieldError;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

/** 管理员手动批次入口，来源与定义格式由既有来源清单声明。 */
@HttpApiGroup("core-batch")
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
    public record Create(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}") String sourceType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128) String activationToken,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "object", implementation = java.util.Map.class,
                    additionalProperties = Schema.AdditionalPropertiesValue.TRUE) tools.jackson.databind.node.ObjectNode definition,
            @Schema(maxLength = 512) String proxy) {
        @Override public String toString() { return "BatchCreate[redacted]"; }
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Create.class)))
    @Parameter(name = AcquisitionCredentialResolver.HEADER_NAME, in = ParameterIn.HEADER,
            schema = @Schema(type = "string", maxLength = AcquisitionCredentialResolver.MAX_LENGTH))
    @ApiResponse(responseCode = "202", useReturnTypeSchema = true,
            headers = @Header(name = "Location", required = true, schema = @Schema(type = "string", format = "uri-reference", example = "/api/download/batches/00000000-0000-4000-8000-000000000042")),
            links = @Link(name = "inspectBatch", operationRef = "#/paths/~1api~1download~1batches~1%7Bid%7D/get",
                    parameters = @LinkParameter(name = "id", expression = "$response.body#/id")))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = RequestFieldError.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = ToolApiRequests.INVALID_TOOL_REQUEST))))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "409", value = {"batch.exists", "batch.stopping"})
    @KnownErrorCodes(status = "500", value = {"batch.operation-failed", "error.io"})
    @PostMapping(consumes = "application/json")
    public ResponseEntity<ManualBatchService.View> create(HttpServletRequest request) throws IOException {
        var body = ToolApiRequests.readStrictStrings(request, Create.class, BODY_LIMIT);
        if (body.definition() == null) throw new InputFieldException("/definition", "required");
        String credential = AcquisitionCredentialResolver.resolve(request.getHeader(AcquisitionCredentialResolver.HEADER_NAME));
        var command = new ManualBatchService.Request(body.name(), body.sourceType(), body.activationToken(),
                body.definition().toString(), body.proxy());
        var batch = service.create(command, credential, ProgramAccess.identity(request).orElse(null));
        return ResponseEntity.accepted().location(URI.create("/api/download/batches/" + batch.id()))
                .cacheControl(CacheControl.noStore()).body(batch);
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "204", content = @Content)
    @GetMapping public ResponseEntity<ManualBatchService.View> current(HttpServletRequest request) {
        var view = service.current(ProgramAccess.identity(request).orElse(null));
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @GetMapping("/{id}") public ResponseEntity<ManualBatchService.View> get(@PathVariable UUID id, HttpServletRequest request) {
        authorize(id, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @Parameter(name = "offset", in = ParameterIn.QUERY,
            schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
    @Parameter(name = "limit", in = ParameterIn.QUERY,
            schema = @Schema(type = "integer", format = "int32", defaultValue = "100", minimum = "1", maximum = "200"))
    @GetMapping("/{id}/items") public ResponseEntity<ManualBatchService.Items> items(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        authorize(id, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.items(id, offset, limit));
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @PostMapping("/{id}/pause") public ManualBatchService.View pause(@PathVariable UUID id, HttpServletRequest request) {
        authorize(id, request); return service.stop(id, true);
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @PostMapping("/{id}/cancel") public ManualBatchService.View cancel(@PathVariable UUID id, HttpServletRequest request) {
        authorize(id, request); return service.stop(id, false);
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "409", value = "batch.not-idle")
    @PostMapping("/{id}/resume") public ManualBatchService.View resume(@PathVariable UUID id, HttpServletRequest request) {
        authorize(id, request); return service.resume(id, false, ProgramAccess.identity(request).orElse(null));
    }
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "409", value = {"batch.not-idle", "batch.no-failed-work"})
    @PostMapping("/{id}/retry") public ManualBatchService.View retry(@PathVariable UUID id, HttpServletRequest request) {
        authorize(id, request); return service.resume(id, true, ProgramAccess.identity(request).orElse(null));
    }
    public record Credentials(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int expectedRun) {}
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Credentials.class)))
    @Parameter(name = AcquisitionCredentialResolver.HEADER_NAME, in = ParameterIn.HEADER,
            schema = @Schema(type = "string", maxLength = AcquisitionCredentialResolver.MAX_LENGTH))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ToolApiRequests.InvalidRequest.class, ApiErrorResponse.Basic.class})))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @KnownErrorCodes(status = "400", value = {"schedule.error.credential-fixed", "schedule.error.credential-policy-unavailable"})
    @KnownErrorCodes(status = "409", value = {"batch.not-idle", "schedule.error.credential-policy-publication-changed"})
    @PutMapping(value = "/{id}/credentials", consumes = "application/json")
    public ManualBatchService.View credentials(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        authorize(id, request);
        var body = ToolApiRequests.read(request, Credentials.class, 16 * 1024);
        String credential = AcquisitionCredentialResolver.resolve(request.getHeader(AcquisitionCredentialResolver.HEADER_NAME));
        return service.credentials(id, body.expectedRun(), credential);
    }
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = ManualBatchService.Resolve.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @KnownErrorCodes(status = "409", value = {"batch.not-idle", "batch.action-changed"})
    @PostMapping(value = "/{id}/resolve", consumes = "application/json")
    public ManualBatchService.View resolve(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        authorize(id, request);
        return service.resolve(id, ToolApiRequests.readStrictStrings(request, ManualBatchService.Resolve.class, 16 * 1024));
    }
    @ApiResponse(responseCode = "204", content = @Content)
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @KnownErrorCodes(status = "409", value = "batch.not-idle")
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest request) { authorize(id, request); service.delete(id); return ResponseEntity.noContent().build(); }

    private void authorize(UUID id, HttpServletRequest request) {
        service.authorize(id, ProgramAccess.identity(request).orElse(null));
    }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale, HttpServletRequest request) {
        String path = request.getRequestURI();
        if (failure instanceof tools.jackson.core.JacksonException binding && path.endsWith("/batches") && !binding.getPath().isEmpty()) {
            String field = binding.getPath().getFirst().getPropertyName();
            if (field != null && java.util.Set.of("sourceType", "name", "activationToken", "proxy", "definition").contains(field))
                failure = new InputFieldException("/" + field, "type");
        }
        if (failure instanceof InputFieldException invalid)
            return ResponseEntity.badRequest().body(new RequestFieldError(ToolApiRequests.INVALID_TOOL_REQUEST,
                    messages.get(locale, "tools.api.invalid-request", BODY_LIMIT), invalid.field(), invalid.reason()));
        return requests.invalid(failure, locale, path.endsWith("/credentials") || path.endsWith("/resolve") ? 16 * 1024 : BODY_LIMIT);
    }
    @ExceptionHandler(ScheduleManagementException.class)
    public ResponseEntity<ApiErrorResponse> credentialPolicyError(ScheduleManagementException failure, Locale locale) {
        return ResponseEntity.status(failure.code() == ScheduleManagementException.Code.CONFLICT ? 409 : 400)
                .body(ApiErrorResponse.of(failure.messageCode(), messages.getOrDefault(locale, failure.messageCode(),
                        failure.defaultMessage(), failure.messageArgs())));
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
