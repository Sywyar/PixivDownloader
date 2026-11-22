package top.sywyar.pixivdownload.download.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.links.Link;
import io.swagger.v3.oas.annotations.links.LinkParameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ManualBatchSubmission;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@PluginManagedBean
@HttpApiGroup("download-workbench-pixiv-art")
@RestController
public final class AuthorBatchController {
    private final ManualBatchSubmission batches;
    private final ObjectMapper json;

    public AuthorBatchController(ManualBatchSubmission batches, ObjectMapper json) {
        this.batches = batches;
        this.json = json;
    }

    public record Receipt(UUID id) {}

    @ApiResponse(responseCode = "202", useReturnTypeSchema = true,
            headers = @Header(name = "Location", required = true, schema = @Schema(type = "string", format = "uri-reference", example = "/api/download/batches/00000000-0000-4000-8000-000000000042")),
            links = @Link(name = "inspectBatch", operationRef = "#/paths/~1api~1download~1batches~1%7Bid%7D/get",
                    parameters = @LinkParameter(name = "id", expression = "$response.body#/id")))
    @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "batch.not-found"))))
    @KnownErrorCodes(status = "409", value = {"batch.exists", "batch.stopping"})
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @Operation(extensions = @Extension(properties = @ExtensionProperty(name = "x-manual-batch-source", value = "user-new")))
    @PostMapping(value = "/api/pixiv/user/{userId}/downloads", produces = "application/json")
    public ResponseEntity<Receipt> create(@Parameter(schema = @Schema(implementation = String.class)) @PathVariable long userId,
            @Parameter(schema = @Schema(defaultValue = "illust", allowableValues = {"illust", "novel"}))
            @RequestParam(defaultValue = "illust") String kind,
            @Parameter(schema = @Schema(type = "string", format = "password", maxLength = AcquisitionCredentialResolver.MAX_LENGTH))
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) {
        if (userId <= 0 || !Set.of("illust", "novel").contains(kind)) throw new IllegalArgumentException("Invalid author batch parameters");
        var program = ProgramIdentity.from(request.getAttribute(ProgramIdentity.REQUEST_ATTRIBUTE)).orElse(null);
        String definition = json.writeValueAsString(Map.of("kind", kind, "source", Map.of("userId", Long.toString(userId))));
        UUID id = batches.submit(DownloadWorkbenchPlugin.ID, "user-new", "Pixiv " + userId + " / " + kind,
                definition, AcquisitionCredentialResolver.resolve(cookie), program);
        return ResponseEntity.accepted().location(URI.create("/api/download/batches/" + id))
                .cacheControl(CacheControl.noStore()).body(new Receipt(id));
    }
}
