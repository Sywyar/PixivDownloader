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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import top.sywyar.pixivdownload.download.response.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e, Locale locale) {
        String message = messages.getOrDefault(locale, "error.request.body.invalid", "请求体格式错误");
        log.warn(logMessage("error.log.request.body.parse-failed", fallbackLogDetail(e.getMessage(), e.getClass().getSimpleName())));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn(logMessage("error.log.request.failed", fallbackLogDetail(e.getMessage(), e.getClass().getSimpleName())));
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
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
