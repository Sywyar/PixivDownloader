package top.sywyar.pixivdownload.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.RequestBodyLimit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Locale;

/** 对宿主和插件声明的正文预算使用同一 MVC 边界，继续复用原 DTO 转换器。 */
@RestControllerAdvice
@Order(0)
public final class RequestBodyLimitAdvice extends RequestBodyAdviceAdapter {
    private final AppMessages messages;

    public RequestBodyLimitAdvice(AppMessages messages) { this.messages = messages; }

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return parameter.hasParameterAnnotation(RequestBodyLimit.class);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
                                           Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        int limit = parameter.getParameterAnnotation(RequestBodyLimit.class).value();
        if (limit < 1 || limit == Integer.MAX_VALUE) throw new IllegalStateException("INVALID_HTTP_BODY_LIMIT");
        if (input.getHeaders().getContentLength() > limit) throw new Exceeded(limit);
        byte[] bytes = input.getBody().readNBytes(limit + 1);
        if (bytes.length > limit) throw new Exceeded(limit);
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    private static final class Exceeded extends RuntimeException {
        final int limit;
        Exceeded(int limit) { super("HTTP_BODY_LIMIT"); this.limit = limit; }
    }

    @ExceptionHandler(Exceeded.class)
    public ResponseEntity<ApiErrorResponse> exceeded(Exceeded failure, Locale locale) {
        return ResponseEntity.status(413).body(ApiErrorResponse.of("HTTP_BODY_LIMIT",
                messages.get(locale, "tools.api.body-limit", failure.limit)));
    }
}
