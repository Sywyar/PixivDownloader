package top.sywyar.pixivdownload.plugin.api.schedule.execution;

/**
 * 跨插件边界传递的安全失败投影。它不携带 {@link Throwable}、请求头、凭证或上游响应正文。
 */
public record ScheduledFailure(
        Category category,
        String code,
        long retryAfterMillis
) {

    public enum Category {
        CANCELLED,
        RETRYABLE_NETWORK,
        CREDENTIAL_INVALID,
        RATE_LIMITED,
        CHALLENGE,
        ACCESS_UNAVAILABLE,
        NOT_FOUND,
        INVALID_DEFINITION,
        PAYLOAD_UNSUPPORTED,
        INTERNAL
    }

    public ScheduledFailure {
        if (category == null) {
            throw new IllegalArgumentException("failure category must not be null");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("failure code must not be blank");
        }
        if (retryAfterMillis < 0) {
            throw new IllegalArgumentException("retry delay must not be negative");
        }
        code = code.trim();
    }
}
