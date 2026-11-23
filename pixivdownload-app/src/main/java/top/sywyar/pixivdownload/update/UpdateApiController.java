package top.sywyar.pixivdownload.update;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import io.swagger.v3.oas.annotations.media.SchemaProperty;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.Parameter;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.util.Locale;

/** 管理员更新操作；安装确认绑定摘要，不接受调用方指定的可执行文件。 */
@HttpApiGroup("core-updates")
@RestController
@RequestMapping("/api/admin/update")
public final class UpdateApiController {
    private static final int BODY_LIMIT = 4096;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UpdateApiController.class);
    private final UpdateService service;
    private final AppMessages messages;
    private final ToolApiRequests requests;

    public UpdateApiController(UpdateService service, AppMessages messages, ToolApiRequests requests) {
        this.service = service;
        this.messages = messages;
        this.requests = requests;
    }

    public record Install(String sha256) {}
    public record Progress(long received, long total, boolean done, boolean failed,
                           @HttpApiNullable String code, @HttpApiNullable String error,
                           @HttpApiNullable UpdateService.InstallerReceipt installer) {}

    @GetMapping("/check")
    public UpdateCheckResult check(@RequestParam(defaultValue = "false") boolean force) {
        return service.checkForUpdate(force);
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "204", content = @Content)
    @GetMapping("/last")
    public ResponseEntity<UpdateCheckResult> last() {
        var result = service.lastResult();
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }

    @ApiResponse(responseCode = "202", description = "Accepted", content = @io.swagger.v3.oas.annotations.media.Content)
    @Parameter(name = "channel", in = ParameterIn.QUERY, schema = @Schema(type = "string", defaultValue = "official", allowableValues = {"official", "nightly"}))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "UPDATE_DOWNLOAD_UNAVAILABLE"))))
    @PostMapping("/download")
    public ResponseEntity<?> download(@RequestParam(defaultValue = "official") String channel, Locale locale) {
        boolean nightly = UpdateService.isNightlyDownload(channel);
        try {
            service.startDownloadAsync(nightly);
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException unavailable) {
            return failure(409, "UPDATE_DOWNLOAD_UNAVAILABLE", "update.download.start-failed", locale);
        }
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "204", content = @Content)
    @GetMapping("/download/progress")
    public ResponseEntity<Progress> progress(Locale locale) {
        var value = service.getDownloadProgress();
        if (value == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(new Progress(value.received(), value.total(), value.done(), value.failed(),
                value.failed() ? "UPDATE_DOWNLOAD_FAILED" : null,
                value.failed() ? messages.get(locale, "update.download.failed") : null, service.installerReceipt()));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Install.class)))
    @ApiResponse(responseCode = "202", description = "Accepted", content = @io.swagger.v3.oas.annotations.media.Content)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"UPDATE_INSTALL_UNSUPPORTED", "UPDATE_INSTALLER_STALE"}))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/install", consumes = "application/json")
    public ResponseEntity<?> install(HttpServletRequest request, Locale locale) throws IOException {
        var body = ToolApiRequests.read(request, Install.class, BODY_LIMIT);
        if (body.sha256() == null || !body.sha256().matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException();
        if (!UpdateService.ASSET_WIN_X64_INSTALLER.equals(UpdateService.currentPlatformAssetKey()))
            return failure(409, "UPDATE_INSTALL_UNSUPPORTED", "update.error.installer.platform-unsupported", locale);
        try {
            service.launchInstallerAndExit(body.sha256());
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException stale) {
            return failure(409, "UPDATE_INSTALLER_STALE", "update.install.failed", locale);
        } catch (IOException failed) {
            LOG.warn("Update installer launch failed: type={}", failed.getClass().getSimpleName());
            return failure(500, "UPDATE_INSTALL_FAILED", "update.install.failed", locale);
        }
    }

    private ResponseEntity<ApiErrorResponse> failure(int status, String code, String key, Locale locale) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, messages.get(locale, key)));
    }

    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) {
        return requests.invalid(failure, locale, BODY_LIMIT);
    }
}
