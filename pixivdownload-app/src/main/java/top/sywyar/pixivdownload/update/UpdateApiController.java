package top.sywyar.pixivdownload.update;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.util.Locale;

/** 管理员更新操作；安装确认绑定摘要，不接受调用方指定的可执行文件。 */
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
                           String code, String error, UpdateService.InstallerReceipt installer) {}

    @GetMapping("/check")
    public UpdateCheckResult check(@RequestParam(defaultValue = "false") boolean force) {
        return service.checkForUpdate(force);
    }

    @GetMapping("/last")
    public ResponseEntity<UpdateCheckResult> last() {
        var result = service.lastResult();
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }

    @PostMapping("/download")
    public ResponseEntity<?> download(@RequestParam(defaultValue = "official") String channel, Locale locale) {
        if (!channel.equals("official") && !channel.equals("nightly")) throw new IllegalArgumentException();
        try {
            service.startDownloadAsync(channel.equals("nightly"));
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException unavailable) {
            return failure(409, "UPDATE_DOWNLOAD_UNAVAILABLE", "update.download.start-failed", locale);
        }
    }

    @GetMapping("/download/progress")
    public ResponseEntity<Progress> progress(Locale locale) {
        var value = service.getDownloadProgress();
        if (value == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(new Progress(value.received(), value.total(), value.done(), value.failed(),
                value.failed() ? "UPDATE_DOWNLOAD_FAILED" : null,
                value.failed() ? messages.get(locale, "update.download.failed") : null, service.installerReceipt()));
    }

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
