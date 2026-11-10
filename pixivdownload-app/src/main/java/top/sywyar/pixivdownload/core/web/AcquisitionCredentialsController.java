package top.sywyar.pixivdownload.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.config.credential.AcquisitionCredentialLibrary;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.io.IOException;

/** 管理员的共享采集凭据库；HTTP 投影从不返回 secret。 */
@RestController
@RequestMapping("/api/acquisition/credentials")
public final class AcquisitionCredentialsController {
    private static final Logger LOG = LoggerFactory.getLogger(AcquisitionCredentialsController.class);
    private final AcquisitionCredentialLibrary library;
    private final RequestOwnerIdentityResolver identities;
    private final top.sywyar.pixivdownload.i18n.AppMessages messages;
    public AcquisitionCredentialsController(AcquisitionCredentialLibrary library, RequestOwnerIdentityResolver identities,
                                            top.sywyar.pixivdownload.i18n.AppMessages messages) {
        this.library = library;
        this.identities = identities;
        this.messages = messages;
    }
    @GetMapping
    public ResponseEntity<?> read(HttpServletRequest request) throws IOException {
        if (!identities.isAdminAuthenticated(request)) return failure(403, "CREDENTIAL_FORBIDDEN");
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(library.read()); }
        catch (IOException failure) { return failure(503, "CREDENTIAL_IO"); }
    }
    @PutMapping(consumes = "application/json")
    public ResponseEntity<?> save(HttpServletRequest request) throws IOException {
        if (!identities.isAdminAuthenticated(request)) return failure(403, "CREDENTIAL_FORBIDDEN");
        if (request.getContentLengthLong() > AcquisitionCredentialLibrary.MAX_BYTES) return failure(413, "CREDENTIAL_LIMIT");
        byte[] bytes = request.getInputStream().readNBytes(AcquisitionCredentialLibrary.MAX_BYTES + 1);
        try {
            if (bytes.length > AcquisitionCredentialLibrary.MAX_BYTES) return failure(413, "CREDENTIAL_LIMIT");
            var saved = library.save(AcquisitionCredentialLibrary.parse(bytes));
            LOG.info("Acquisition credential library saved: count={}", saved.entries().size());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(saved);
        } catch (AcquisitionCredentialLibrary.Conflict conflict) {
            return failure(409, "CREDENTIAL_CONFLICT");
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
            return failure(400, "CREDENTIAL_INVALID");
        } catch (IOException failure) {
            LOG.warn("Acquisition credential library save failed: errorType={}", failure.getClass().getSimpleName());
            return failure(503, "CREDENTIAL_IO");
        } finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }
    private ResponseEntity<?> failure(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(code, messages.get(status == 503 ? "error.io" : "error.request.param.invalid")));
    }
}
