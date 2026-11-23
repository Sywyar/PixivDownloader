package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.common.web.HttpJsonInput;
import top.sywyar.pixivdownload.web.HttpParameterBinding;
import top.sywyar.pixivdownload.core.web.HttpParameterException;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.web.BoundedBodyRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;

/** 工具 HTTP 共用严格有界输入与纯值错误翻译，不保留请求和凭据。 */
@Component
public final class ToolApiRequests {
    public static final String INVALID_TOOL_REQUEST = "INVALID_TOOL_REQUEST";

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record InvalidRequest(@Schema(allowableValues = INVALID_TOOL_REQUEST) String code, String error,
                                 @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String in,
                                 @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String field,
                                 @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String reason) implements ApiErrorResponse {}
    public record BodyLimit(@Schema(allowableValues = BoundedBodyRequest.HTTP_BODY_LIMIT) String code, String error) implements ApiErrorResponse {}

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final JsonMapper STRICT_STRINGS = HttpJsonInput.strictStrings(JSON.rebuild()).build();
    private final AppMessages messages;
    private final WebI18nService i18n;

    public ToolApiRequests(AppMessages messages, WebI18nService i18n) {
        this.messages = messages;
        this.i18n = i18n;
    }

    public static String requiredQuery(String name, String value) {
        if (value.isBlank()) throw new HttpParameterException("query", name, "required");
        return value;
    }

    public static <T> T read(HttpServletRequest request, Class<T> type, int limit) throws IOException {
        return read(request, type, limit, JSON);
    }

    static <T> T readStrictStrings(HttpServletRequest request, Class<T> type, int limit) throws IOException {
        return read(request, type, limit, STRICT_STRINGS);
    }

    private static <T> T read(HttpServletRequest request, Class<T> type, int limit, JsonMapper mapper) throws IOException {
        if (request.getContentLengthLong() > limit) throw new IllegalArgumentException(BoundedBodyRequest.HTTP_BODY_LIMIT);
        var body = mapper.readValue(new BoundedBodyRequest(request).readBody(limit), type);
        if (body == null) throw new IllegalArgumentException(INVALID_TOOL_REQUEST);
        return body;
    }

    public ResponseEntity<ApiErrorResponse> rejected(DesktopUiText token, Locale locale) {
        String message = messages.getOrDefault(locale, token.key(), token.fallback(), token.arguments().toArray());
        if (token.namespace() != null) {
            try {
                String pattern = i18n.loadMessages(token.namespace(), locale, List.of(token.key())).get(token.key());
                if (pattern != null) message = new MessageFormat(pattern, locale).format(token.arguments().toArray());
            } catch (top.sywyar.pixivdownload.i18n.LocalizedException withdrawn) {
                // publication 撤回同时撤销字典，保留契约自带的失败说明。
            }
        }
        String code = token.key().isBlank() ? "TOOL_OPERATION_REJECTED" : token.key();
        return ResponseEntity.status(409).body(ApiErrorResponse.of(code, message));
    }

    public ResponseEntity<ApiErrorResponse> invalid(Exception failure, Locale locale, int limit) {
        boolean oversized = BoundedBodyRequest.HTTP_BODY_LIMIT.equals(failure.getMessage());
        if (oversized) return ResponseEntity.status(413).body(new BodyLimit(BoundedBodyRequest.HTTP_BODY_LIMIT,
                messages.get(locale, "tools.api.body-limit", limit)));
        if (failure instanceof InputFieldException input)
            return invalidField("body", input.field(), input.reason(), locale);
        if (failure instanceof HttpParameterException input)
            return invalidField(input.in(), input.field(), input.reason(), locale);
        if (failure instanceof tools.jackson.core.JacksonException binding) {
            String field = HttpJsonInput.pointer(binding);
            if (field != null) return invalidField("body", field, HttpJsonInput.reason(binding), locale);
        }
        return ResponseEntity.badRequest().body(new InvalidRequest(INVALID_TOOL_REQUEST,
                messages.get(locale, "tools.api.invalid-request", limit), null, null, null));
    }

    private ResponseEntity<ApiErrorResponse> invalidField(String in, String field, String reason, Locale locale) {
        var detail = HttpParameterBinding.error(INVALID_TOOL_REQUEST, in, field, reason, messages, locale);
        return ResponseEntity.badRequest().body(new InvalidRequest(detail.code(), detail.error(), in, field, reason));
    }
}
