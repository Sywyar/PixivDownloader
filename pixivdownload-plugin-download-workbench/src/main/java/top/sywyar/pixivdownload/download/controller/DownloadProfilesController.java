package top.sywyar.pixivdownload.download.controller;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.state.DownloadProfiles;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 路由以 ADMIN 声明，凭据和队列不进入方案文档。 */
@HttpApiGroup("download-workbench-workbench")
@RestController
@RequestMapping("/api/batch/profiles")
@Slf4j
public final class DownloadProfilesController {
    // The store validates JSON trees before persistence. Expose their wire shape without Jackson tree internals.
    @Schema(requiredProperties = {"id", "name", "settings", "filters"})
    public record ProfilePayload(@Schema(pattern = "[a-zA-Z0-9-]{1,64}") String id,
                                 @Schema(maxLength = 80) String name,
                                 Map<String, Object> settings, Map<String, Object> filters) { }
    @Schema(requiredProperties = {"revision", "profiles"})
    public record ProfilesPayload(String revision, List<ProfilePayload> profiles) { }

    private final DownloadProfiles profiles;
    private final top.sywyar.pixivdownload.i18n.MessageResolver messages;
    public DownloadProfilesController(DownloadProfiles profiles, top.sywyar.pixivdownload.i18n.MessageResolver messages) {
        this.profiles = profiles;
        this.messages = messages;
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProfilesPayload.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping
    public ResponseEntity<?> read() throws IOException {
        try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(profiles.read()); }
        catch (IOException failure) { return failure(503, "profiles.failed", "error.io"); }
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = ProfilesPayload.class),
            examples = @ExampleObject(value = "{\"revision\":\"0\",\"profiles\":[{\"id\":\"example\",\"name\":\"Example\",\"settings\":{},\"filters\":{}}]}")))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProfilesPayload.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PutMapping(consumes = "application/json")
    public ResponseEntity<?> save(HttpServletRequest request) throws IOException {
        try {
            if (request.getContentLengthLong() > DownloadProfiles.MAX_BYTES) return tooLarge();
            byte[] bytes = request.getInputStream().readNBytes(DownloadProfiles.MAX_BYTES + 1);
            if (bytes.length > DownloadProfiles.MAX_BYTES) return tooLarge();
            DownloadProfiles.Snapshot result = profiles.save(DownloadProfiles.parse(bytes));
            log.info("Download profiles saved: count={}", result.profiles().size());
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (DownloadProfiles.Conflict conflict) {
            return failure(409, "profiles.conflict", "error.request.param.invalid");
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
            return failure(400, "profiles.invalid", "error.request.param.invalid");
        } catch (IOException failure) {
            log.warn("Download profile save failed: errorType={}", failure.getClass().getSimpleName());
            return failure(503, "profiles.failed", "error.io");
        }
    }

    private ResponseEntity<?> failure(int status, String code, String key) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(ApiErrorResponse.of(code, messages.get(key)));
    }

    private ResponseEntity<?> tooLarge() {
        return failure(413, "profiles.too-large", "error.request.param.invalid");
    }
}
