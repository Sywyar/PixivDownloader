package top.sywyar.pixivdownload.core.pixiv;

import java.util.Objects;

/**
 * Pixiv JSON 请求的受控失败，不携带响应体、凭证或具体 HTTP 客户端异常。
 */
public final class PixivAjaxException extends RuntimeException {

    /**
     * 失败信息。
     */
    private final PixivAjaxFailure failure;
    /**
     * 状态码。
     */
    private final int statusCode;
    private final long retryAfterMillis;

    /**
     * 创建 {@code PixivAjaxException} 实例。
     *
     * @param failure 失败信息
     * @param statusCode 状态码
     */
    public PixivAjaxException(PixivAjaxFailure failure, int statusCode) {
        this(failure, statusCode, 0);
    }

    /** @param failure 失败类别 @param statusCode 上游状态 @param retryAfterMillis 上游冷却等待毫秒数 */
    public PixivAjaxException(PixivAjaxFailure failure, int statusCode, long retryAfterMillis) {
        super(message(failure, statusCode));
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
        this.failure = failure;
        if (failure == PixivAjaxFailure.HTTP_STATUS) {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("HTTP status code must be between 100 and 599");
            }
        } else if (statusCode != 0) {
            throw new IllegalArgumentException("non-HTTP failure must use status code 0");
        }
        this.statusCode = statusCode;
    }

    /**
     * 返回失败信息。
     *
     * @return 方法返回的 {@code PixivAjaxFailure} 实例
     */
    public PixivAjaxFailure failure() {
        return failure;
    }

    /**
     * 返回上游 HTTP 状态码；非 HTTP 失败返回 {@code 0}。
     *
     * @return 方法返回的数值
     */
    public int statusCode() {
        return statusCode;
    }

    /** {@return 上游要求的冷却时间；零表示未提供有效值} */
    public long retryAfterMillis() { return retryAfterMillis; }

    private static String message(PixivAjaxFailure failure, int statusCode) {
        Objects.requireNonNull(failure, "failure");
        return failure == PixivAjaxFailure.HTTP_STATUS
                ? "Pixiv JSON request failed with HTTP status " + statusCode
                : "Pixiv JSON request failed: " + failure;
    }
}
