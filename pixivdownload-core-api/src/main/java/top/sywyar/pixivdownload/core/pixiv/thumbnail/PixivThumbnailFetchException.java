package top.sywyar.pixivdownload.core.pixiv.thumbnail;

import java.util.Objects;

/**
 * Pixiv 缩略图请求的受控失败，不携带目标地址、凭证或具体 HTTP 客户端异常。
 */
public final class PixivThumbnailFetchException extends RuntimeException {

    private final PixivThumbnailFailure failure;
    private final int statusCode;

    public PixivThumbnailFetchException(PixivThumbnailFailure failure, int statusCode) {
        super(message(failure, statusCode));
        this.failure = Objects.requireNonNull(failure, "failure");
        if (failure == PixivThumbnailFailure.HTTP_STATUS) {
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

    public PixivThumbnailFailure failure() {
        return failure;
    }

    /**
     * 返回上游 HTTP 状态码；非 HTTP 失败返回 {@code 0}。
     */
    public int statusCode() {
        return statusCode;
    }

    private static String message(PixivThumbnailFailure failure, int statusCode) {
        Objects.requireNonNull(failure, "failure");
        return failure == PixivThumbnailFailure.HTTP_STATUS
                ? "Pixiv thumbnail request failed with HTTP status " + statusCode
                : "Pixiv thumbnail request failed: " + failure;
    }
}
