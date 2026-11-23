package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiAccess;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.history.BrowserHistoryImport;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAdmissionRejectedException;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.web.LocalRequestTrust;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.slf4j.event.Level.*;

/** 仅 solo 本机采集；目录确认前只保留候选，短期单次令牌不依赖浏览器会话 cookie。 */
@HttpApiGroup("pixiv-batch-downloader-import-batch-import")
@HttpApiAccess(HttpApiAccess.Condition.SOLO_LOCAL_ONLY)
@RestController
public final class PixivBatchDownloaderImportController {
    private static final int MAX_BODY = 12 * 1024 * 1024;
    private final WorkFileImporter importer;
    private final NamespaceMessageResolver messages;
    private final PixivBatchDownloaderImportDirectory directory;
    private final ApplicationModeProvider mode;
    private final PixivBatchDownloaderImportStatistics statistics;
    private final BrowserHistoryImport history;
    private final ImportDiagnostics diagnostics;
    private String lastAutomaticFailure;
    private final Semaphore admission = new Semaphore(1);
    private final Semaphore historyBodies = new Semaphore(1);
    private final Map<String, Long> tokens = new LinkedHashMap<>();
    private final ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(3_000_000).build())
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).configureForJackson2()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    PixivBatchDownloaderImportController(WorkFileImporter importer, NamespaceMessageResolver messages, PixivBatchDownloaderImportDirectory directory, ApplicationModeProvider mode,
                                        PixivBatchDownloaderImportStatistics statistics, BrowserHistoryImport history, ImportDiagnostics diagnostics) {
        this.importer = importer; this.messages = messages; this.directory = directory; this.mode = mode;
        this.statistics = statistics;
        this.history = history;
        this.diagnostics = diagnostics;
    }
    private boolean local(HttpServletRequest request) {
        return "solo".equals(mode.getMode()) && "1".equals(request.getHeader("X-Pixiv-Collector"))
                && LocalRequestTrust.isLocalRequest(request.getRemoteAddr(), request.getHeader("Host"),
                    request.getHeader("X-Forwarded-For"), request.getHeader("X-Real-IP"), request.getHeader("Forwarded"));
    }
    @Parameter(name = "X-Pixiv-Collector", in = ParameterIn.HEADER, required = true, schema = @Schema(allowableValues = "1"))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ImportToken.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "429", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/api/pixiv-batch-downloader-import/token")
    public synchronized ResponseEntity<?> token(HttpServletRequest request) {
        if (!local(request)) return error(request, 403, "LOCAL_ONLY");
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
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Local browser import. Send a fresh X-Import-Token and a completed observation from the browser.",
            content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = Observation.class),
                    examples = @ExampleObject(value = "{\"schemaVersion\":1,\"source\":\"pixiv-batch-downloader\",\"taskBatch\":1,\"tabId\":1,\"metadata\":{\"id\":\"123\",\"title\":\"Sample\",\"type\":0,\"pageCount\":1,\"authorId\":\"42\",\"aiType\":0,\"restriction\":0,\"tags\":[]},\"files\":[{\"fileId\":\"123_p0\",\"page\":0,\"outcome\":\"success\",\"path\":\"123_p0.jpg\"}]}")))
    @Parameter(name = "X-Pixiv-Collector", in = ParameterIn.HEADER, required = true, schema = @Schema(allowableValues = "1"))
    @Parameter(name = "X-Import-Token", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string"))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ImportOutcome.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/api/pixiv-batch-downloader-import", consumes = "application/json")
    public ResponseEntity<?> importWork(HttpServletRequest request) {
        if (!local(request)) return error(request, 403, "LOCAL_ONLY");
        if (!claim(request.getHeader("X-Import-Token"))) return error(request, 401, "INVALID_IMPORT_TOKEN");
        if (!admission.tryAcquire()) return error(request, 409, "IMPORT_BUSY");
        String work = "-";
        String stage = "input";
        boolean committed = false;
        long started = System.nanoTime();
        try {
            if (request.getContentLengthLong() > MAX_BODY) return automaticError(request, 413, "IMPORT_TOO_LARGE", stage, work, false, null);
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) return automaticError(request, 413, "IMPORT_TOO_LARGE", stage, work, false, null);
            var observation = json.readTree(body);
            stage = "source-root";
            String sourceRoot = directory.configuredRoot();
            if (sourceRoot.isBlank()) {
                directory.observe(observation);
                return automaticError(request, 503, "SOURCE_ROOT_REQUIRED", stage, work, false, null);
            }
            stage = "validate";
            var value = PixivBatchDownloaderImportObservation.parse(observation, Path.of(sourceRoot));
            work = value.workType() + ":" + value.workId();
            diagnostics.event(DEBUG, "log.automatic.start", work, value.pageFiles().size());
            stage = "statistics";
            statistics.flushPending();
            stage = "register";
            committed = importer.importFiles(value);
            if (committed) {
                stage = "statistics";
                statistics.recordImported(value);
            }
            lastAutomaticFailure = null;
            String code = committed ? "IMPORTED" : "ALREADY_RECORDED";
            diagnostics.event(INFO, "log.automatic.result", work, code, value.pageFiles().size(),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return ResponseEntity.ok(Map.of("code", code));
        } catch (DownloadAdmissionRejectedException rejected) {
            return automaticError(request, 409, "DOWNLOAD_ADMISSION_REJECTED", stage, work, committed, rejected);
        } catch (PixivBatchDownloaderImportObservation.InvalidObservation invalid) {
            return automaticError(request, 400, invalid.getMessage(), stage, work, committed, invalid);
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
            return automaticError(request, 400, "IMPORT_REJECTED", stage, work, committed, invalid);
        } catch (IOException unavailable) {
            return automaticError(request, 503, "IMPORT_FILE_UNAVAILABLE", stage, work, committed, unavailable);
        } catch (java.sql.SQLException unavailable) {
            return automaticError(request, 503, "IMPORT_STATISTICS_UNAVAILABLE", stage, work, committed, unavailable);
        } catch (RuntimeException failed) {
            return automaticError(request, 500, "IMPORT_FAILED", stage, work, committed, failed);
        } finally { admission.release(); }
    }
    private ResponseEntity<ApiErrorResponse> automaticError(HttpServletRequest request, int status, String code,
            String stage, String work, boolean committed, Throwable failure) {
        String identity = stage + ":" + work + ":" + code + ":" + committed + ":" + ImportDiagnostics.detail(failure);
        if (!identity.equals(lastAutomaticFailure)) {
            lastAutomaticFailure = identity;
            diagnostics.failure(status == 500 ? ERROR : WARN, "automatic/" + stage, "-", work, code, failure);
            if (committed) diagnostics.event(WARN, "log.automatic.registered", work);
        }
        return error(request, status, code);
    }
    private ResponseEntity<ApiErrorResponse> error(HttpServletRequest request, int status, String code) {
        diagnostics.event(DEBUG, "log.request.rejected", status, code);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(ApiErrorResponse.of(code,
                messages.resolve("pixiv-batch-downloader-import", request.getLocale(), "error").orElse(code)));
    }

    /** 历史会话和每次写入令牌分别校验，禁止浏览器提供本机路径。 */
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Use the action query parameter. Create takes an empty object. Upload takes binary bytes plus file and offset. For inspect, start, status or cancel send the returned X-History-Session. Each request requires a fresh X-Import-Token and a Pixiv Origin.",
            content = {@Content(
            mediaType = "application/json", schema = @Schema(type = "object"),
                    examples = @ExampleObject(value = "{}")), @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = HistoryResponse.class)))
    @Parameter(name = "X-Pixiv-Collector", in = ParameterIn.HEADER, required = true, schema = @Schema(allowableValues = "1"))
    @Parameter(name = "X-Import-Token", in = ParameterIn.HEADER, required = true, schema = @Schema(type = "string"))
    @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true, schema = @Schema(allowableValues = {"https://www.pixiv.net", "https://pixiv.net"}))
    @Parameter(name = "X-History-Session", in = ParameterIn.HEADER, schema = @Schema(type = "string"))
    @Parameter(name = "action", in = ParameterIn.QUERY, required = true, schema = @Schema(allowableValues = {"create", "upload", "inspect", "start", "status", "cancel"}))
    @Parameter(name = "file", in = ParameterIn.QUERY, schema = @Schema(type = "integer", minimum = "0"))
    @Parameter(name = "offset", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int64", minimum = "0"))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping(value = "/api/pixiv-batch-downloader-import/history",
            consumes = {"application/json", "application/octet-stream"})
    public ResponseEntity<?> history(HttpServletRequest request) {
        if (!local(request) || !Set.of("https://www.pixiv.net", "https://pixiv.net")
                .contains(Objects.toString(request.getHeader("Origin"), ""))) return error(request, 403, "LOCAL_ONLY");
        if (!claim(request.getHeader("X-Import-Token"))) return error(request, 401, "INVALID_IMPORT_TOKEN");
        String action = Objects.toString(request.getParameter("action"), "");
        String stage = switch (action) {
            case "create", "upload", "inspect", "start", "status", "cancel" -> "browser/" + action;
            default -> "browser/unknown";
        };
        boolean bulk = action.equals("upload");
        // 大请求的读取不能阻塞状态和取消，也不占用实时采集的准入槽。
        if (bulk && !historyBodies.tryAcquire()) return error(request, 409, "IMPORT_BUSY");
        try {
            int limit = action.equals("upload") ? BrowserHistoryImport.CHUNK_BYTES : 4096;
            if (request.getContentLengthLong() > limit) return error(request, 413, "IMPORT_TOO_LARGE");
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) return error(request, 413, "IMPORT_TOO_LARGE");
            String id = request.getHeader("X-History-Session");
            BrowserHistoryImport.View view;
            switch (action) {
                case "create" -> {
                    var options = json.readTree(body);
                    if (options == null || !options.isObject() || !options.isEmpty())
                        return error(request, 400, "IMPORT_REJECTED");
                    view = history.create(directory.configuredRoot());
                }
                case "upload" -> view = history.upload(id, Integer.parseInt(request.getParameter("file")),
                        Long.parseLong(request.getParameter("offset")), body);
                case "inspect" -> view = history.inspect(id);
                case "start" -> view = history.start(id, directory.configuredRoot());
                case "status" -> view = history.status(id);
                case "cancel" -> view = history.cancel(id);
                default -> { return error(request, 400, "IMPORT_REJECTED"); }
            }
            var status = view.status();
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(new HistoryResponse(
                    view.session(), view.phase(), view.preview(), status.total(), status.processed(), status.imported(),
                    status.skipped(), status.failed(), localized(view.error(), request.getLocale()),
                    status.problems().stream().map(problem -> new HistoryProblem(problem.workKey(),
                            localized(problem.reason(), request.getLocale()))).toList(),
                    view.results().stream().map(result -> new HistoryWorkResult(result.workKey(), result.state(),
                            localized(result.error(), request.getLocale()))).toList()));
        } catch (DesktopFileImportTool.OperationException rejected) {
            diagnostics.failure(WARN, stage, "-", "-", rejected.text().key(), null);
            return ResponseEntity.status(409).header("Cache-Control", "no-store").body(ApiErrorResponse.of(
                    "HISTORY_REJECTED", localized(rejected.text(), request.getLocale())));
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
            diagnostics.failure(WARN, stage, "-", "-", "IMPORT_REJECTED", invalid);
            return error(request, 400, "IMPORT_REJECTED");
        } catch (IOException unavailable) {
            diagnostics.failure(WARN, stage, "-", "-", "IMPORT_FILE_UNAVAILABLE", unavailable);
            return error(request, 503, "IMPORT_FILE_UNAVAILABLE");
        } catch (RuntimeException failure) {
            diagnostics.failure(ERROR, stage, "-", "-", "IMPORT_FAILED", failure);
            return error(request, 500, "IMPORT_FAILED");
        } finally { if (bulk) historyBodies.release(); }
    }

    @Schema(requiredProperties = {"schemaVersion", "source", "taskBatch", "tabId", "metadata", "files"})
    public record Observation(@Schema(minimum = "1", maximum = "1") int schemaVersion,
                              @Schema(allowableValues = "pixiv-batch-downloader") String source,
                              @Schema(minimum = "1") long taskBatch, long tabId, Metadata metadata,
                              List<FileObservation> files) { }
    @Schema(requiredProperties = {"id", "title", "type", "pageCount", "authorId", "aiType", "restriction", "tags"})
    public record Metadata(@Schema(oneOf = {String.class, Long.class}) Object id, String title,
                           @Schema(minimum = "0", maximum = "3") int type,
                           @Schema(minimum = "1", maximum = "1000") int pageCount,
                           @Schema(oneOf = {String.class, Long.class}) Object authorId, String authorName,
                           @Schema(minimum = "0", maximum = "2") int aiType, int restriction, List<String> tags,
                           @Schema(maxLength = 32768) String description,
                           @Schema(oneOf = {String.class, Long.class}) Object seriesId, Long seriesOrder,
                           String novelContent) { }
    @Schema(requiredProperties = {"fileId", "page", "outcome", "path"})
    public record FileObservation(String fileId, @Schema(minimum = "0") int page,
                                  @Schema(allowableValues = "success") String outcome,
                                  @Schema(maxLength = 4096) String path, Boolean noReply) { }

    @Schema
    public record ImportToken(String token) {}
    @Schema
    public record ImportOutcome(@Schema(allowableValues = {"IMPORTED", "ALREADY_RECORDED"}) String code) {}
    public record HistoryProblem(String work, String error) {}
    public record HistoryWorkResult(String workKey, @Schema(allowableValues = {"WAITING", "RUNNING", "IMPORTED", "SKIPPED", "FAILED"}) String state, String error) {}
    public record HistoryResponse(String session, @Schema(allowableValues = {"UPLOADING", "INSPECTING", "READY", "RUNNING", "CANCELLING", "COMPLETED", "CANCELLED", "FAILED"}) String phase, DesktopFileImportTool.Preview preview,
                                  int total, int processed, int imported, int skipped, int failed,
                                  String error, List<HistoryProblem> problems,
                                  List<HistoryWorkResult> results) {}

    private String localized(DesktopUiText text, Locale locale) {
        if (text == null) return null;
        String pattern = messages.resolve("pixiv-batch-downloader-import", locale, text.key()).orElse(text.fallback());
        return new java.text.MessageFormat(pattern, locale).format(text.arguments().toArray());
    }
}
