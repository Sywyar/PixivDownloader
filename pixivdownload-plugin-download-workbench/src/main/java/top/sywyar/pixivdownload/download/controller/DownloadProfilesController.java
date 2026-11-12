package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.state.DownloadProfiles;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;

/** 路由以 ADMIN 声明，凭据和队列不进入方案文档。 */
@RestController
@RequestMapping("/api/batch/profiles")
@Slf4j
public final class DownloadProfilesController {
    private final DownloadProfiles profiles;
    private final top.sywyar.pixivdownload.i18n.MessageResolver messages;
    public DownloadProfilesController(DownloadProfiles profiles, top.sywyar.pixivdownload.i18n.MessageResolver messages) {
        this.profiles = profiles;
        this.messages = messages;
    }

    @GetMapping
    public ResponseEntity<?> read() throws IOException {
        try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(profiles.read()); }
        catch (IOException failure) { return failure(503, "profiles.failed", "error.io"); }
    }

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
