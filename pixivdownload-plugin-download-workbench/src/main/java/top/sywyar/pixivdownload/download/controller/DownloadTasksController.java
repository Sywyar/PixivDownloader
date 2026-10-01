package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.util.UUID;

/** 下载工作台的管理员任务入口；业务类型与生命周期由 SDK 门面分派。 */
@PluginManagedBean
@RestController
@RequestMapping("/api/download/tasks")
public final class DownloadTasksController {
    private static final int MAX_COMMAND_BYTES = 128 * 1024;
    private static final com.fasterxml.jackson.databind.ObjectReader COMMANDS =
            new com.fasterxml.jackson.databind.ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
                    .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                            .maxNestingDepth(4).maxStringLength(16 * 1024).build())
                    .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                    .readerFor(DownloadSubmission.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DownloadTasks tasks;
    private final RequestOwnerIdentityResolver identities;
    private final MessageResolver messages;

    public DownloadTasksController(DownloadTasks tasks, RequestOwnerIdentityResolver identities, MessageResolver messages) {
        this.tasks = tasks;
        this.identities = identities;
        this.messages = messages;
    }

    @PostMapping
    public ResponseEntity<?> submit(HttpServletRequest request) throws java.io.IOException {
        var owner = admin(request);
        if (request.getContentLengthLong() > MAX_COMMAND_BYTES)
            return failure(413, "INVALID", "error.request.param.invalid");
        byte[] bytes = request.getInputStream().readNBytes(MAX_COMMAND_BYTES + 1);
        if (bytes.length > MAX_COMMAND_BYTES)
            return failure(413, "INVALID", "error.request.param.invalid");
        DownloadSubmission command = COMMANDS.readValue(bytes);
        if (command == null) throw new IllegalArgumentException("download command is required");
        String credential = AcquisitionCredentialResolver.resolve(
                request.getHeader(AcquisitionCredentialResolver.HEADER_NAME), null);
        var result = tasks.submit(command, credential, owner);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(new ReceiptView(TaskView.from(result.task()), result.duplicate()));
    }

    @GetMapping
    public ResponseEntity<SnapshotView> snapshot(HttpServletRequest request) {
        var snapshot = tasks.snapshot(admin(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SnapshotView(
                snapshot.epoch(), snapshot.revision(), snapshot.tasks().stream().map(TaskView::from).toList()));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> find(@PathVariable UUID taskId, HttpServletRequest request) {
        var value = tasks.find(taskId, admin(request));
        return value.isPresent() ? ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TaskView.from(value.get()))
                : failure(404, "NOT_FOUND", "download.status.not-found");
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID taskId, HttpServletRequest request) {
        var result = tasks.cancel(taskId, admin(request));
        return switch (result) {
            case REQUESTED, TERMINAL -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CancelResponse(result));
            case NOT_FOUND -> failure(404, "NOT_FOUND", "download.status.not-found");
            case NOT_CANCELLABLE -> failure(409, "NOT_CANCELLABLE", "download.tasks.not-cancellable");
        };
    }

    private RequestOwnerIdentity admin(HttpServletRequest request) {
        var owner = identities.resolve(request);
        if (!owner.admin()) throw new DownloadTaskException(DownloadTaskException.Code.FORBIDDEN);
        return owner;
    }

    @ExceptionHandler(DownloadTaskException.class)
    public ResponseEntity<ApiErrorResponse> rejected(DownloadTaskException failure) {
        int status = switch (failure.code()) {
            case FORBIDDEN -> 403;
            case CONFLICT -> 409;
            case UNAVAILABLE, CAPACITY_EXCEEDED -> 503;
            case REJECTED -> 422;
        };
        return failure(status, failure.code().name(), "download.tasks." + failure.code().name().toLowerCase(java.util.Locale.ROOT));
    }

    @ExceptionHandler({IllegalArgumentException.class,
            java.io.IOException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> invalid() {
        return failure(400, "INVALID", "error.request.param.invalid");
    }

    private ResponseEntity<ApiErrorResponse> failure(int status, String code, String messageKey) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of("DOWNLOAD_TASK_" + code, messages.get(messageKey)));
    }

    public record CancelResponse(DownloadTasks.CancelResult result) {}
    public record ReceiptView(TaskView task, boolean duplicate) {}
    public record SnapshotView(UUID epoch, long revision, java.util.List<TaskView> tasks) {}
    /** HTTP 时间固定为 ISO-8601 字符串，不依赖宿主 ObjectMapper 的日期格式。 */
    public record TaskView(DownloadAttempt attempt, String queueType, String title, DownloadEvent.Phase phase,
                           String updatedAt, long revision) {
        static TaskView from(DownloadTaskSnapshot task) {
            return new TaskView(task.attempt(), task.queueType(), task.title(), task.phase(),
                    task.updatedAt().toString(), task.revision());
        }
    }
}
