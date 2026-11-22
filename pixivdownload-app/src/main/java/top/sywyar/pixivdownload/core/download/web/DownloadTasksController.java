package top.sywyar.pixivdownload.core.download.web;

import top.sywyar.pixivdownload.common.web.HttpJsonInput;
import top.sywyar.pixivdownload.setup.AuthFilter.ProgramCredentialError;
import java.net.URI;
import top.sywyar.pixivdownload.common.web.RequestFieldError;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.links.LinkParameter;
import io.swagger.v3.oas.annotations.links.Link;
import io.swagger.v3.oas.annotations.headers.Header;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import top.sywyar.pixivdownload.common.web.HttpParameterBinding;
import top.sywyar.pixivdownload.core.web.HttpParameterError;
import top.sywyar.pixivdownload.core.web.HttpParameterException;
import tools.jackson.core.json.JsonFactory;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskProgress;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.util.UUID;
import top.sywyar.pixivdownload.setup.ProgramAccess;

/** 宿主的管理员下载任务入口；业务类型与生命周期由 SDK 门面分派。 */
@HttpApiGroup("core-downloads")
@RestController
@RequestMapping("/api/download/tasks")
public final class DownloadTasksController {
    private static final String TASK_PROGRESS_EXAMPLE = """
            {"attempt":{"attemptId":"47925123-6ab8-4cb8-ba11-253cdd091a00","workType":"artwork","workId":"42"},
             "queueType":"illust","title":"Example","phase":"STARTED","updatedAt":"2026-11-22T07:36:15Z",
             "revision":5,"failureReason":null,"progress":{"unit":"PAGES","completed":1,"total":3},"outputPath":"/downloads/42"}
            """;
    private static final int MAX_COMMAND_BYTES = 128 * 1024;
    private static final tools.jackson.databind.ObjectReader COMMANDS =
            HttpJsonInput.strictStrings(tools.jackson.databind.json.JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
                            .maxNestingDepth(4).maxStringLength(16 * 1024).build())
                    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).configureForJackson2()).build()
                    .readerFor(DownloadSubmission.class)
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DownloadTasks tasks;
    private final RequestOwnerIdentityResolver identities;
    private final MessageResolver messages;

    public DownloadTasksController(DownloadTasks tasks, RequestOwnerIdentityResolver identities, MessageResolver messages) {
        this.tasks = tasks;
        this.identities = identities;
        this.messages = messages;
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = DownloadSubmission.class)))
    @ApiResponse(responseCode = "202", headers = @Header(name = "Location", required = true, schema = @Schema(type = "string", format = "uri-reference", example = "/api/download/tasks/00000000-0000-4000-8000-000000000042")),
            links = {
                @Link(name = "inspectTask", operationRef = "#/paths/~1api~1download~1tasks~1%7BtaskId%7D/get",
                        parameters = @LinkParameter(name = "taskId", expression = "$response.body#/task/attempt/attemptId")),
                @Link(name = "cancelTask", operationRef = "#/paths/~1api~1download~1tasks~1%7BtaskId%7D~1cancel/post",
                        parameters = @LinkParameter(name = "taskId", expression = "$response.body#/task/attempt/attemptId"))
            }, content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReceiptView.class),
                    examples = @ExampleObject(value = """
                            {"duplicate":false,"task":{"attempt":{"attemptId":"47925123-6ab8-4cb8-ba11-253cdd091a00","workType":"artwork","workId":"42"},
                             "queueType":"illust","title":"Example","phase":"QUEUED","updatedAt":"2026-11-22T07:36:13.645561900Z",
                             "revision":4,"failureReason":null,"progress":null,"outputPath":"C:\\\\PixivDownload\\\\42"}}
                            """)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {RequestFieldError.class, HttpParameterError.class}),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = {"DOWNLOAD_TASK_INVALID", "DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE"}))))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "WORK_NOT_FOUND"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DOWNLOAD_TASK_CONFLICT"))))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DOWNLOAD_TASK_INVALID"))))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = "application/json", schema = @Schema(allOf = RequestFieldError.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DOWNLOAD_TASK_REJECTED"))))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", examples = {"DOWNLOAD_TASK_UNAVAILABLE", "DOWNLOAD_TASK_CAPACITY_EXCEEDED", "auth.maintenance", "auth.setup-required", "auth.program.unavailable"}))))
    @Parameter(name = AcquisitionCredentialResolver.HEADER_NAME, in = ParameterIn.HEADER,
            schema = @Schema(type = "string", format = "password", maxLength = AcquisitionCredentialResolver.MAX_LENGTH))
    @io.swagger.v3.oas.annotations.Operation(operationId = "submitDownloadTask")
    @PostMapping
    public ResponseEntity<?> submit(HttpServletRequest request) throws java.io.IOException {
        var owner = authorizedOwner(request);
        if (request.getContentLengthLong() > MAX_COMMAND_BYTES)
            return failure(413, "DOWNLOAD_TASK_INVALID", "error.request.param.invalid");
        byte[] bytes = request.getInputStream().readNBytes(MAX_COMMAND_BYTES + 1);
        if (bytes.length > MAX_COMMAND_BYTES)
            return failure(413, "DOWNLOAD_TASK_INVALID", "error.request.param.invalid");
        DownloadSubmission command = COMMANDS.readValue(bytes);
        if (command == null) throw new IllegalArgumentException("download command is required");
        String credential = AcquisitionCredentialResolver.resolve(
                request.getHeader(AcquisitionCredentialResolver.HEADER_NAME));
        var program = ProgramAccess.identity(request);
        var result = program.isPresent() ? tasks.submitForProgram(command, credential, program.get())
                : tasks.submit(command, credential, owner);
        return ResponseEntity.accepted().location(URI.create("/api/download/tasks/" + result.task().attempt().attemptId()))
                .cacheControl(CacheControl.noStore())
                .body(new ReceiptView(TaskView.from(result.task()), result.duplicate()));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SnapshotView.class),
            examples = @ExampleObject(value = "{\"epoch\":\"4475bd74-a689-4a14-a85d-b665bd3edc0e\",\"revision\":5,\"tasks\":["
                    + TASK_PROGRESS_EXAMPLE + "]}")))
    @GetMapping
    public ResponseEntity<SnapshotView> snapshot(HttpServletRequest request) {
        var snapshot = tasks.snapshot(authorizedOwner(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SnapshotView(
                snapshot.epoch(), snapshot.revision(), snapshot.tasks().stream().map(TaskView::from).toList()));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = TaskView.class), examples = @ExampleObject(value = TASK_PROGRESS_EXAMPLE)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DOWNLOAD_TASK_NOT_FOUND"))))
    @io.swagger.v3.oas.annotations.Operation(operationId = "getDownloadTask")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = HttpParameterError.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DOWNLOAD_TASK_INVALID"))))
    @GetMapping("/{taskId}")
    public ResponseEntity<?> find(@PathVariable UUID taskId, HttpServletRequest request) {
        var value = tasks.find(taskId, authorizedOwner(request));
        return value.isPresent() ? ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TaskView.from(value.get()))
                : failure(404, "DOWNLOAD_TASK_NOT_FOUND", "download.tasks.not-found");
    }

    @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int32", defaultValue = "50", minimum = "1", maximum = "200"))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = HistoryView.class),
            examples = @ExampleObject(value = """
                    {"tasks":[],"beforeTime":null,"beforeId":null}
                    """)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = HttpParameterError.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DOWNLOAD_TASK_INVALID"))))
    @Parameter(name = "beforeTime", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date-time"))
    @GetMapping("/history")
    public ResponseEntity<HistoryView> history(@RequestParam(defaultValue = "50") int limit,
                                              @RequestParam(required = false) String beforeTime,
                                              @RequestParam(required = false) UUID beforeId,
                                              HttpServletRequest request) {
        var owner = authorizedOwner(request);
        if (limit < 1 || limit > 200) throw new HttpParameterException("query", "limit", "out_of_range");
        if ((beforeTime == null) != (beforeId == null))
            throw new HttpParameterException("query", beforeTime == null ? "beforeTime" : "beforeId", "required");
        DownloadTasks.HistoryCursor cursor = null;
        if (beforeTime != null) {
            try {
                cursor = new DownloadTasks.HistoryCursor(java.time.Instant.parse(beforeTime), beforeId);
            } catch (java.time.format.DateTimeParseException invalid) {
                throw new HttpParameterException("query", "beforeTime", "invalid_format");
            }
        }
        var page = tasks.history(cursor, limit, owner);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new HistoryView(
                page.tasks().stream().map(TaskView::from).toList(),
                page.next() == null ? null : page.next().updatedAt().toString(), page.next() == null ? null : page.next().taskId()));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = CancelResponse.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DOWNLOAD_TASK_NOT_FOUND"))))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(allOf = ApiErrorResponse.Basic.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DOWNLOAD_TASK_NOT_CANCELLABLE"))))
    @io.swagger.v3.oas.annotations.Operation(operationId = "cancelDownloadTask")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(allOf = HttpParameterError.class),
            schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string", allowableValues = "DOWNLOAD_TASK_INVALID"))))
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID taskId, HttpServletRequest request) {
        var result = tasks.cancel(taskId, authorizedOwner(request));
        return switch (result) {
            case REQUESTED, TERMINAL -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CancelResponse(result));
            case NOT_FOUND -> failure(404, "DOWNLOAD_TASK_NOT_FOUND", "download.tasks.not-found");
            case NOT_CANCELLABLE -> failure(409, "DOWNLOAD_TASK_NOT_CANCELLABLE", "download.tasks.not-cancellable");
        };
    }

    private RequestOwnerIdentity authorizedOwner(HttpServletRequest request) {
        var owner = ProgramAccess.identity(request).map(program -> RequestOwnerIdentity.owner(program.id().toString()))
                .orElseGet(() -> identities.resolve(request));
        if (!owner.admin() && ProgramAccess.identity(request).isEmpty()) throw new DownloadTaskException(DownloadTaskException.Code.FORBIDDEN);
        return owner;
    }

    @ExceptionHandler(DownloadTaskException.class)
    public ResponseEntity<ApiErrorResponse> rejected(DownloadTaskException failure) {
        int status = switch (failure.code()) {
            case FORBIDDEN -> 403;
            case CONFLICT -> 409;
            case UNSUPPORTED_WORK_TYPE -> 400;
            case UNAVAILABLE, CAPACITY_EXCEEDED -> 503;
            case WORK_NOT_FOUND -> 404;
            case REJECTED -> 422;
        };
        if (failure.code() == DownloadTaskException.Code.UNSUPPORTED_WORK_TYPE)
            return fieldError(400, "DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE", "download.tasks.unsupported_work_type", "/workType", "unsupported");
        if (failure.failure().field() != null)
            return fieldError(status, "DOWNLOAD_TASK_" + failure.code().name(), "download.tasks.rejected",
                    failure.failure().field(), failure.failure().reason());
        if (failure.programPermission() != null)
            return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ProgramCredentialError(
                    "DOWNLOAD_TASK_FORBIDDEN", messages.get("auth.program.forbidden.permission", failure.programPermission()), failure.programPermission()));
        if (failure.code() == DownloadTaskException.Code.WORK_NOT_FOUND)
            return failure(status, failure.code().name(), "error.work.not-found");
        return failure(status, "DOWNLOAD_TASK_" + failure.code().name(), "download.tasks." + failure.code().name().toLowerCase(java.util.Locale.ROOT));
    }

    @ExceptionHandler({IllegalArgumentException.class,
            java.time.DateTimeException.class,
            tools.jackson.core.JacksonException.class,
            java.io.IOException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponse> invalid(Exception failure) {
        // Jackson wraps exceptions raised by record constructors.
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof InputFieldException invalid)
                return fieldError(400, "DOWNLOAD_TASK_INVALID", "error.request.param.invalid", invalid.field(), invalid.reason());
        if (failure instanceof tools.jackson.core.JacksonException binding) {
            String field = HttpJsonInput.pointer(binding);
            if (field != null) return fieldError(400, "DOWNLOAD_TASK_INVALID", "error.request.param.invalid", field, "type");
        }
        return failure(400, "DOWNLOAD_TASK_INVALID", "error.request.param.invalid");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<HttpParameterError> invalidParameter(MethodArgumentTypeMismatchException invalid, java.util.Locale locale) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(HttpParameterBinding.mismatch(invalid, "DOWNLOAD_TASK_INVALID", messages, locale));
    }

    @ExceptionHandler(HttpParameterException.class)
    public ResponseEntity<HttpParameterError> invalidParameter(HttpParameterException invalid, java.util.Locale locale) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(HttpParameterBinding.error(
                "DOWNLOAD_TASK_INVALID", invalid.in(), invalid.field(), invalid.reason(), messages, locale));
    }

    private ResponseEntity<ApiErrorResponse> fieldError(int status, String code, String messageKey, String field, String reason) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new RequestFieldError(code, messages.get(messageKey), field, reason));
    }

    private ResponseEntity<ApiErrorResponse> failure(int status, String code, String messageKey) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(code, messages.get(messageKey)));
    }

    public record CancelResponse(DownloadTasks.CancelResult result) {}
    public record ReceiptView(TaskView task, boolean duplicate) {}
    public record SnapshotView(UUID epoch, long revision, java.util.List<TaskView> tasks) {}
    public record HistoryView(java.util.List<TaskView> tasks, @HttpApiNullable @Schema(format = "date-time") String beforeTime, @HttpApiNullable UUID beforeId) {}
    /** HTTP 时间固定为 ISO-8601 字符串，不依赖宿主 ObjectMapper 的日期格式。 */
    public record TaskView(DownloadAttempt attempt, String queueType, String title, DownloadEvent.Phase phase,
                           @Schema(format = "date-time") String updatedAt, long revision, @HttpApiNullable DownloadEvent.FailureReason failureReason,
                           @HttpApiNullable String outputPath, @HttpApiNullable DownloadTaskProgress progress) {
        static TaskView from(DownloadTaskSnapshot task) {
            return new TaskView(task.attempt(), task.queueType(), task.title(), task.phase(),
                    task.updatedAt().toString(), task.revision(), task.failureReason(), task.outputPath(), task.progress());
        }
    }
}
