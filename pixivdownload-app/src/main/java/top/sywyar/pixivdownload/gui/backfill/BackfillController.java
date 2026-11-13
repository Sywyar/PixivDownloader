package top.sywyar.pixivdownload.gui.backfill;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.common.web.BoundedBodyRequest;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.BackfillTask;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;

/** 管理员回填入口，共用桌面及无头启动的执行器和当前数据库目标。 */
@RestController
@RequestMapping("/api/tools/backfill")
public final class BackfillController {
    static final int MAX_BODY_BYTES = 4096;
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final DesktopBackfillRunner runner;
    private final DatabaseTarget target;
    private final ProxyConfig proxy;
    private final DownloadConfig download;
    private final AppMessages messages;

    public BackfillController(DesktopBackfillRunner runner, DatabaseTarget target, ProxyConfig proxy,
                              DownloadConfig download, AppMessages messages) {
        this.runner = runner;
        this.target = target;
        this.proxy = proxy;
        this.download = download;
        this.messages = messages;
    }

    public record Request(Long delayMs, Integer limit, Boolean dryRun, Boolean alwaysNetwork) {}
    public record Candidates(int count) {}

    @GetMapping("/defaults")
    public Request defaults() {
        var defaults = ArtworksBackFill.Options.defaults();
        return new Request(defaults.delayMs(), defaults.limit(), defaults.dryRun(), defaults.alwaysNetwork());
    }

    @PostMapping(value = "/candidates", consumes = "application/json")
    public Candidates candidates(HttpServletRequest request) throws Exception {
        return new Candidates(runner.countCandidates(options(read(request))));
    }

    @PostMapping(value = "/tasks", consumes = "application/json")
    public ResponseEntity<BackfillTask> start(HttpServletRequest request) throws IOException {
        var task = runner.start(options(read(request)), ignored -> {});
        return ResponseEntity.accepted().location(URI.create("/api/tools/backfill/tasks/" + task.executionId())).body(task);
    }

    @GetMapping("/tasks/current")
    public ResponseEntity<BackfillTask> current() {
        return runner.snapshot().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/tasks/{id}")
    public BackfillTask status(@PathVariable UUID id) {
        return runner.snapshot().filter(value -> value.executionId().equals(id))
                .orElseThrow(() -> new IllegalArgumentException("BACKFILL_TASK_NOT_FOUND"));
    }

    @PostMapping("/tasks/{id}/cancel")
    public BackfillTask cancel(@PathVariable UUID id) {
        var task = status(id);
        runner.cancel(id);
        return runner.snapshot().filter(value -> value.executionId().equals(id)).orElse(task);
    }

    private ArtworksBackFill.Options options(Request request) {
        var defaults = defaults();
        long delay = request.delayMs() == null ? defaults.delayMs() : request.delayMs();
        int limit = request.limit() == null ? defaults.limit() : request.limit();
        if (delay < 0 || limit < 0) throw new IllegalArgumentException("BACKFILL_INVALID_REQUEST");
        if (!target.available() || !runner.isRunning()) throw new IllegalStateException("BACKFILL_UNAVAILABLE");
        return new ArtworksBackFill.Options(target.displayName(), proxy.getHost(), proxy.getPort(), proxy.isEnabled(),
                delay, limit, Boolean.TRUE.equals(request.dryRun()), target)
                .withSources(download.getRootFolder(), Boolean.TRUE.equals(request.alwaysNetwork()));
    }

    private Request read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw new IllegalArgumentException("HTTP_BODY_LIMIT");
        var value = JSON.readValue(new BoundedBodyRequest(request).readBody(MAX_BODY_BYTES), Request.class);
        if (value == null) throw new IllegalArgumentException("BACKFILL_INVALID_REQUEST");
        return value;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, tools.jackson.core.JacksonException.class})
    public ResponseEntity<ApiErrorResponse> error(Exception failure, Locale locale) {
        String code = failure.getMessage();
        int status = switch (code == null ? "" : code) {
            case "HTTP_BODY_LIMIT" -> 413;
            case "BACKFILL_BUSY" -> 409;
            case "BACKFILL_UNAVAILABLE" -> 503;
            case "BACKFILL_TASK_NOT_FOUND" -> 404;
            default -> 400;
        };
        if (status == 400) code = "BACKFILL_INVALID_REQUEST";
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, messages.get(locale, "backfill.api." + code)));
    }
}
