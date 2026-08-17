package top.sywyar.pixivdownload.douyin.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DouyinParsedView(
        boolean supported,
        String kind,
        String id,
        String originalUrl,
        String canonicalUrl,
        String messageKey,
        String code,
        String error
) implements ApiErrorResponse {

    public DouyinParsedView(boolean supported, String kind, String id, String originalUrl,
                            String canonicalUrl, String messageKey) {
        this(supported, kind, id, originalUrl, canonicalUrl, messageKey, null, null);
    }

    public static DouyinParsedView unsupported(String messageKey) {
        return new DouyinParsedView(false, null, null, null, null, messageKey,
                "INVALID_URL", "Invalid Douyin URL");
    }

    public static DouyinParsedView from(DouyinParsedInput input) {
        return new DouyinParsedView(
                true,
                input.kind().name(),
                input.id(),
                input.originalUrl(),
                input.canonicalUrl(),
                null);
    }
}
