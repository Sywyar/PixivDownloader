package top.sywyar.pixivdownload.gui;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.web.BoundedBodyRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;

/** 工具 HTTP 共用严格有界输入与纯值错误翻译，不保留请求和凭据。 */
@Component
public final class ToolApiRequests {
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final AppMessages messages;
    private final WebI18nService i18n;

    public ToolApiRequests(AppMessages messages, WebI18nService i18n) {
        this.messages = messages;
        this.i18n = i18n;
    }

    public static <T> T read(HttpServletRequest request, Class<T> type, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) throw new IllegalArgumentException("HTTP_BODY_LIMIT");
        var body = JSON.readValue(new BoundedBodyRequest(request).readBody(limit), type);
        if (body == null) throw new IllegalArgumentException("INVALID_TOOL_REQUEST");
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
        boolean oversized = "HTTP_BODY_LIMIT".equals(failure.getMessage());
        return ResponseEntity.status(oversized ? 413 : 400).body(ApiErrorResponse.of(
                oversized ? "HTTP_BODY_LIMIT" : "INVALID_TOOL_REQUEST",
                messages.get(locale, oversized ? "tools.api.body-limit" : "tools.api.invalid-request", limit)));
    }
}
