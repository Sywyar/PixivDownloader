package top.sywyar.pixivdownload.plugin.api.schedule.execution;

/**
 * 跨插件边界传递的安全失败投影。它不携带 {@link Throwable}、请求头、凭证或上游响应正文。
 */
public record ScheduledFailure(
        Category category,
        String code,
        long retryAfterMillis
) {

    /** 计划任务失败类别。 */
    public enum Category {
        /**
         * 表示 {@code CANCELLED} 状态。
         */
        CANCELLED,
        /**
         * 表示 {@code RETRYABLE_NETWORK} 状态。
         */
        RETRYABLE_NETWORK,
        /**
         * 表示 {@code CREDENTIAL_INVALID} 状态。
         */
        CREDENTIAL_INVALID,
        /**
         * 表示 {@code RATE_LIMITED} 状态。
         */
        RATE_LIMITED,
        /**
         * 表示 {@code CHALLENGE} 状态。
         */
        CHALLENGE,
        /**
         * 表示 {@code ACCESS_UNAVAILABLE} 状态。
         */
        ACCESS_UNAVAILABLE,
        /**
         * 表示 {@code NOT_FOUND} 状态。
         */
        NOT_FOUND,
        /**
         * 表示 {@code INVALID_DEFINITION} 状态。
         */
        INVALID_DEFINITION,
        /**
         * 表示 {@code PAYLOAD_UNSUPPORTED} 状态。
         */
        PAYLOAD_UNSUPPORTED,
        /** 插件内部失败。 */
        INTERNAL
    }

    /**
     * 创建并校验安全失败投影。
     *
     * @param category 失败类别
     * @param code 失败机器码
     * @param retryAfterMillis 建议重试延迟毫秒数
     */
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
