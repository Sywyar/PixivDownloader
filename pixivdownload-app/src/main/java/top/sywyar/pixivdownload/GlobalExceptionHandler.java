package top.sywyar.pixivdownload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.ConstraintViolation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionException;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityDeniedException;

import java.io.IOException;
import java.util.Locale;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AppMessages messages;

    @ExceptionHandler(LocalizedException.class)
    public ResponseEntity<ErrorResponse> handleLocalized(LocalizedException e, Locale locale) {
        String message = messages.getOrDefault(locale, e.getMessageCode(), e.getDefaultMessage(), e.getMessageArgs());
        String logDetail = messages.getOrDefault(Locale.getDefault(), e.getMessageCode(), e.getDefaultMessage(), e.getMessageArgs());
        log.warn(logMessage("error.log.request.failed", logDetail));
        return ResponseEntity.status(e.getStatus()).body(new ErrorResponse(message));
    }

    @ExceptionHandler(WorkVisibilityDeniedException.class)
    public ResponseEntity<ErrorResponse> handleWorkVisibilityDenied(
            WorkVisibilityDeniedException e, Locale locale) {
        String code = e.workType() == WorkType.NOVEL
                ? "guest.invite.novel.forbidden"
                : "guest.invite.forbidden";
        String fallback = e.workType() == WorkType.NOVEL
                ? "该小说不在你的可见范围内"
                : "该作品不在你的可见范围内";
        String message = messages.getOrDefault(locale, code, fallback);
        String logDetail = messages.getOrDefault(Locale.getDefault(), code, fallback);
        log.warn(logMessage("error.log.request.failed",
                logDetail + " [workType=" + e.workType() + ", workId=" + e.workId() + "]"));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(message));
    }

    @ExceptionHandler(WorkDeletionException.class)
    public ResponseEntity<ErrorResponse> handleWorkDeletion(
            WorkDeletionException e, Locale locale) {
        String typeName = workTypeName(locale, e.workType());
        String logTypeName = workTypeName(Locale.getDefault(), e.workType());
        String message = switch (e.reason()) {
            case LOCAL_FILE_DELETE_FAILED -> messages.getOrDefault(
                    locale,
                    "work.delete.file-failed",
                    "{0} {1} 的磁盘文件未能全部删除，已中止数据库清理",
                    typeName,
                    e.workId());
        };
        String logDetail = switch (e.reason()) {
            case LOCAL_FILE_DELETE_FAILED -> messages.getOrDefault(
                    Locale.getDefault(),
                    "work.delete.file-failed",
                    "{0} {1} 的磁盘文件未能全部删除，已中止数据库清理",
                    logTypeName,
                    e.workId());
        };
        log.warn(logMessage("error.log.request.failed", logDetail));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(message));
    }

    @ExceptionHandler(QueueNotAcceptingException.class)
    public ResponseEntity<ErrorResponse> handleQueueNotAccepting(
            QueueNotAcceptingException e, Locale locale) {
        String message = messages.getOrDefault(locale, "plugin.unavailable.quiesced",
                "插件正在停用中，暂时不可用，请稍后重试");
        String logDetail = messages.getOrDefault(Locale.getDefault(), "plugin.unavailable.quiesced",
                "插件正在停用中，暂时不可用，请稍后重试");
        log.warn(logMessage("error.log.request.failed", logDetail + " [queueType=" + e.queueType() + "]"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(message));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException e) {
        log.warn(logMessage("error.log.request.failed", fallbackLogDetail(e.getReason(), e.getStatusCode().toString())));
        return ResponseEntity.status(e.getStatusCode()).body(new ErrorResponse(e.getReason()));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e, Locale locale) {
        log.debug(logMessage("error.log.request.not-found", fallbackLogDetail(e.getMessage(), e.getClass().getSimpleName())));
        String message = messages.getOrDefault(locale, "error.request.not-found", "请求的资源不存在");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(message));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurity(SecurityException e) {
        log.warn(logMessage("error.log.security.failed", fallbackLogDetail(e.getMessage(), e.getClass().getSimpleName())));
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e, Locale locale) {
        String message = buildValidationMessage(e, locale, false);
        String logDetail = buildValidationMessage(e, Locale.getDefault(), true);
        log.warn(logMessage("error.log.request.param.validation-failed", logDetail));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    /**
     * 上传文件超过 {@code spring.servlet.multipart.max-*} 硬上限：返回受控的 413，而不是 Tomcat 原始错误页。
     * 比 {@link MultipartException} 更具体，Spring 会优先匹配本处理器。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(MaxUploadSizeExceededException e, Locale locale) {
        String message = messages.getOrDefault(locale, "error.upload.too-large", "上传文件过大");
        log.warn(logMessage("error.log.request.failed", fallbackLogDetail(e.getMessage(), e.getClass().getSimpleName())));
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ErrorResponse(message));
    }

    /** 其它 multipart 解析失败（请求体损坏 / 非 multipart 等）：返回受控的 400。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException e, Locale locale) {
        String message = messages.getOrDefault(locale, "error.upload.invalid", "上传请求格式错误");
        log.warn(logMessage("error.log.request.failed", fallbackLogDetail(e.getMessage(), e.getClass().getSimpleName())));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e, Locale locale) {
        String message = messages.getOrDefault(locale, "error.request.body.invalid", "请求体格式错误");
        log.warn(logMessage("error.log.request.body.parse-failed", fallbackLogDetail(e.getMessage(), e.getClass().getSimpleName())));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e, Locale locale) {
        String rawMessage = e.getMessage();
        String message = rawMessage == null || rawMessage.isBlank()
                ? messages.getOrDefault(locale, "error.request.param.invalid", "请求参数错误")
                : rawMessage;
        log.warn(logMessage("error.log.request.failed", fallbackLogDetail(rawMessage, e.getClass().getSimpleName())));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> handleIOException(IOException e, Locale locale) {
        if (isClientDisconnect(e)) {
            log.debug(logMessage("error.log.client-disconnected",
                    fallbackLogDetail(e.getMessage(), e.getClass().getSimpleName())));
            return ResponseEntity.noContent().build();
        }
        String message = messages.getOrDefault(locale, "error.io", "服务器 IO 错误: {0}", e.getMessage());
        log.error(logMessage("error.log.io.exception", fallbackLogDetail(e.getMessage(), e.getClass().getSimpleName())), e);
        return ResponseEntity.internalServerError().body(new ErrorResponse(message));
    }

    /**
     * 转发给 Pixiv 的上游请求返回非 2xx（如 Cookie 失效时的 401、作品不存在的 404）。
     * 这是预期内的运营状况而非未处理 bug，因此 WARN 记录、向调用方返回 502 + 可读提示，
     * 不再落到 {@link #handleGeneric} 的 ERROR「未处理的异常」+ 500。
     */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleUpstream(RestClientResponseException e, Locale locale) {
        return upstreamFailure(e.getStatusCode().value(), locale, bodySnippet(e.getResponseBodyAsString()));
    }

    @ExceptionHandler(PixivAjaxException.class)
    public ResponseEntity<ErrorResponse> handlePixivAjax(PixivAjaxException e, Locale locale) {
        if (e.failure() != PixivAjaxFailure.HTTP_STATUS) {
            return handleGeneric(e, locale);
        }
        return upstreamFailure(e.statusCode(), locale, "<body unavailable>");
    }

    private ResponseEntity<ErrorResponse> upstreamFailure(int status, Locale locale, String bodyForLog) {
        boolean authIssue = status == 401 || status == 403;
        String message = authIssue
                ? messages.getOrDefault(locale, "error.pixiv.upstream.unauthorized",
                        "Pixiv 拒绝了请求：登录 Cookie 可能已失效或无权访问该内容，请重新获取并保存 Cookie 后重试。")
                : messages.getOrDefault(locale, "error.pixiv.upstream.failed",
                        "请求 Pixiv 失败（HTTP {0}），请稍后重试。", status);
        log.warn(logMessage("error.log.pixiv.upstream", status, bodyForLog));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e, Locale locale) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = messages.getOrDefault(locale, "error.unexpected", "发生未处理异常");
        }
        log.error(logMessage("error.log.unexpected.exception"), e);
        return ResponseEntity.internalServerError().body(new ErrorResponse(message));
    }

    private String buildValidationMessage(MethodArgumentNotValidException e, Locale locale, boolean forLog) {
        return e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + resolveFieldMessage(fe, locale, forLog))
                .collect(Collectors.joining("; "));
    }

    private String resolveFieldMessage(FieldError fieldError, Locale locale, boolean forLog) {
        String code = extractValidationMessageCode(fieldError);
        if (code != null) {
            return forLog
                    ? messages.getForLog(code)
                    : messages.getOrDefault(locale, code, fieldError.getDefaultMessage());
        }
        if (forLog) {
            return messages.getForLog(fieldError);
        }
        return messages.get(locale, fieldError);
    }

    private String extractValidationMessageCode(FieldError fieldError) {
        try {
            ConstraintViolation<?> violation = fieldError.unwrap(ConstraintViolation.class);
            String template = violation.getMessageTemplate();
            if (template != null && template.length() > 2 && template.startsWith("{") && template.endsWith("}")) {
                return template.substring(1, template.length() - 1);
            }
        } catch (IllegalArgumentException ignored) {
            // Non-Bean-Validation errors may not carry a ConstraintViolation source.
        }
        return null;
    }

    private String fallbackLogDetail(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String workTypeName(Locale locale, WorkType workType) {
        return switch (workType) {
            case ARTWORK -> messages.getOrDefault(locale, "work.type.artwork", "作品");
            case NOVEL -> messages.getOrDefault(locale, "work.type.novel", "小说");
        };
    }

    /** 上游响应体可能很长，日志里截断到 300 字符即可定位问题。 */
    private String bodySnippet(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }

    private boolean isClientDisconnect(IOException e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof AsyncRequestNotUsableException
                    || "org.apache.catalina.connector.ClientAbortException".equals(current.getClass().getName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("connection was aborted")
                        || normalized.contains("connection aborted")
                        || normalized.contains("forcibly closed")
                        || message.contains("你的主机中的软件中止")
                        || message.contains("远程主机强迫关闭")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
