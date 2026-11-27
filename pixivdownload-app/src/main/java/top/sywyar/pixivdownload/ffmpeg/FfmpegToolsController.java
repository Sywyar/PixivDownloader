package top.sywyar.pixivdownload.ffmpeg;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.util.Locale;

@HttpApiGroup("core-tools")
@RestController
@RequestMapping("/api/tools/ffmpeg")
public final class FfmpegToolsController {
    private final FfmpegInstallService service;
    private final ProxyConfig proxy;
    private final AppMessages messages;
    private final ToolApiRequests requests;
    public FfmpegToolsController(FfmpegInstallService service, ProxyConfig proxy, AppMessages messages, ToolApiRequests requests) {
        this.service = service; this.proxy = proxy; this.messages = messages; this.requests = requests;
    }
    public record Status(boolean downloadSupported, boolean installing, @HttpApiNullable FfmpegInstallation installation,
                         @HttpApiNullable Task task) {}
    public record Task(java.util.UUID executionId, @Schema(allowableValues = {"RUNNING", "CANCEL_REQUESTED", "CANCELLED", "COMPLETED", "FAILED"}) String state, FfmpegInstaller.ProgressStage stage,
                       long current, long total, String failureCode, @HttpApiNullable String error) {}
    public record InstallRequest(FfmpegInstaller.ProxySettings proxy) {}

    @GetMapping public Status status(Locale locale) {
        return new Status(FfmpegInstaller.supportsManagedDownload(), FfmpegInstaller.isInstalling(),
                FfmpegLocator.locateForStatus().orElse(null), view(service.snapshot(), locale));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = InstallRequest.class)))
    @ApiResponse(responseCode = "202", description = "Accepted", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @Schema(implementation = Task.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.InvalidRequest.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ToolApiRequests.BodyLimit.class)))
    @PostMapping(value = "/tasks", consumes = "application/json")
    public ResponseEntity<?> start(HttpServletRequest request, Locale locale) throws IOException {
        var body = ToolApiRequests.read(request, InstallRequest.class, 4096);
        if (!FfmpegInstaller.supportsManagedDownload()) return ResponseEntity.status(409).body(
                ApiErrorResponse.of("FFMPEG_INSTALL_UNSUPPORTED", messages.get(locale, "gui.ffmpeg.install.unsupported")));
        var settings = body.proxy() == null ? new FfmpegInstaller.ProxySettings(proxy.isEnabled(), proxy.getHost(), proxy.getPort()) : body.proxy();
        return ResponseEntity.accepted().body(view(service.start(settings), locale));
    }

    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "202", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", content = @Content)
    @ApiResponse(responseCode = "409", useReturnTypeSchema = true)
    @PostMapping("/tasks/{executionId}/cancel")
    public ResponseEntity<Task> cancel(@PathVariable java.util.UUID executionId, Locale locale) {
        var task = service.cancel(executionId);
        if (task == null) return ResponseEntity.notFound().build();
        int status = switch (task.state()) {
            case "CANCEL_REQUESTED" -> 202;
            case "RUNNING" -> 409;
            default -> 200;
        };
        return ResponseEntity.status(status).body(view(task, locale));
    }

    private Task view(FfmpegInstallService.Snapshot value, Locale locale) {
        if (value == null) return null;
        return new Task(value.executionId(), value.state(), value.stage(), value.current(), value.total(), value.failureCode(),
                value.failure() == null ? null : messages.get(locale, value.failure().key(), value.failure().arguments().toArray()));
    }

    @ExceptionHandler(FfmpegInstaller.BusyException.class)
    public ResponseEntity<ApiErrorResponse> busy(Locale locale) {
        return ResponseEntity.status(409).body(ApiErrorResponse.of("FFMPEG_INSTALL_BUSY", messages.get(locale, "gui.message.backend-busy")));
    }
    @ExceptionHandler({IllegalArgumentException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale) { return requests.invalid(failure, locale, 4096); }
}
