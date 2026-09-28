package top.sywyar.pixivdownload.externalimport;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.web.LocalRequestTrust;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAdmissionRejectedException;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;

/** 仅 solo 本机、配置了源目录的采集桥；短期单次令牌不依赖浏览器会话 cookie。 */
@RestController
public final class ExternalImportController {
    private static final int MAX_BODY = 12 * 1024 * 1024;
    private final WorkFileImporter importer;
    private final NamespaceMessageResolver messages;
    private final String sourceRoot;
    private final ApplicationModeProvider mode;
    private final Semaphore admission = new Semaphore(1);
    private final Map<String, Long> tokens = new LinkedHashMap<>();
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(3_000_000).build())
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    ExternalImportController(WorkFileImporter importer, NamespaceMessageResolver messages, String sourceRoot, ApplicationModeProvider mode) {
        this.importer = importer; this.messages = messages; this.sourceRoot = sourceRoot; this.mode = mode;
    }
    private boolean local(HttpServletRequest request) {
        return "solo".equals(mode.getMode()) && "1".equals(request.getHeader("X-Pixiv-Collector"))
                && LocalRequestTrust.isLocalRequest(request.getRemoteAddr(), request.getHeader("Host"),
                    request.getHeader("X-Forwarded-For"), request.getHeader("X-Real-IP"), request.getHeader("Forwarded"));
    }
    @GetMapping("/api/external-download-import/token")
    public synchronized ResponseEntity<?> token(HttpServletRequest request) {
        if (!local(request)) return error(request, 403, "LOCAL_ONLY");
        if (sourceRoot.isBlank()) return error(request, 503, "SOURCE_ROOT_REQUIRED");
        long now = System.nanoTime();
        tokens.values().removeIf(expires -> expires < now);
        if (tokens.size() >= 128) return error(request, 429, "IMPORT_BUSY");
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        tokens.put(token, now + java.util.concurrent.TimeUnit.MINUTES.toNanos(1));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("token", token));
    }
    private synchronized boolean claim(String token) {
        Long expiry = tokens.remove(token);
        return expiry != null && expiry >= System.nanoTime();
    }
    @PostMapping(value = "/api/external-download-import", consumes = "application/json")
    public ResponseEntity<?> importWork(HttpServletRequest request) {
        if (!local(request)) return error(request, 403, "LOCAL_ONLY");
        if (sourceRoot.isBlank()) return error(request, 503, "SOURCE_ROOT_REQUIRED");
        if (!claim(request.getHeader("X-Import-Token"))) return error(request, 401, "INVALID_IMPORT_TOKEN");
        if (!admission.tryAcquire()) return error(request, 409, "IMPORT_BUSY");
        try {
            if (request.getContentLengthLong() > MAX_BODY) return error(request, 413, "IMPORT_TOO_LARGE");
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) return error(request, 413, "IMPORT_TOO_LARGE");
            var value = ObservationImport.parse(json.readTree(body), Path.of(sourceRoot));
            boolean imported = importer.importFiles(value);
            return ResponseEntity.ok(Map.of("code", imported ? "IMPORTED" : "ALREADY_RECORDED"));
        } catch (DownloadAdmissionRejectedException rejected) {
            return error(request, 409, "DOWNLOAD_ADMISSION_REJECTED");
        } catch (ObservationImport.InvalidObservation invalid) {
            return error(request, 400, invalid.getMessage());
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException invalid) {
            return error(request, 400, "IMPORT_REJECTED");
        } catch (IOException unavailable) {
            return error(request, 503, "IMPORT_FILE_UNAVAILABLE");
        } catch (RuntimeException failed) {
            return error(request, 500, "IMPORT_FAILED");
        } finally { admission.release(); }
    }
    private ResponseEntity<ApiErrorResponse> error(HttpServletRequest request, int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(ApiErrorResponse.of(code,
                messages.resolve("external-import", request.getLocale(), "error").orElse(code)));
    }
}
