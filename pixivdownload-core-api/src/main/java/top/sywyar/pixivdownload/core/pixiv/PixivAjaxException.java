package top.sywyar.pixivdownload.core.pixiv;

import java.util.Objects;

/**
 * Pixiv JSON 请求的受控失败，不携带响应体、凭证或具体 HTTP 客户端异常。
 */
public final class PixivAjaxException extends RuntimeException {

    private final PixivAjaxFailure failure;
    private final int statusCode;

    public PixivAjaxException(PixivAjaxFailure failure, int statusCode) {
        super(message(failure, statusCode));
        this.failure = Objects.requireNonNull(failure, "failure");
        if (failure == PixivAjaxFailure.HTTP_STATUS) {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("HTTP status code must be between 100 and 599");
            }
            this.statusCode = statusCode;
        } else {
            if (statusCode != 0) {
                throw new IllegalArgumentException("non-HTTP failure must use status code 0");
            }
            this.statusCode = 0;
        }
    }

    public PixivAjaxFailure failure() {
        return failure;
    }

    /**
     * 返回上游 HTTP 状态码；非 HTTP 失败返回 {@code 0}。
     */
    public int statusCode() {
        return statusCode;
    }

    private static String message(PixivAjaxFailure failure, int statusCode) {
        Objects.requireNonNull(failure, "failure");
        return failure == PixivAjaxFailure.HTTP_STATUS
                ? "Pixiv JSON request failed with HTTP status " + statusCode
                : "Pixiv JSON request failed: " + failure;
    }
}
