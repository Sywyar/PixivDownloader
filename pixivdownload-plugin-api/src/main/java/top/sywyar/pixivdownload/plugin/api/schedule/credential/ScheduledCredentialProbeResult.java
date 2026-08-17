package top.sywyar.pixivdownload.plugin.api.schedule.credential;

/** 凭证格式检查与主动探活的安全结果；账号键与机器码不得包含原始凭据或可逆派生材料。 */
public record ScheduledCredentialProbeResult(
        Status status,
        String accountKey,
        String code,
        long retryAfterMillis
) {

    /** 凭证探活状态。 */
    public enum Status {
        /**
         * 表示 {@code VALID} 状态。
         */
        VALID,
        /**
         * 表示 {@code INVALID} 状态。
         */
        INVALID,
        /** 暂时无法完成探活，应在指定延迟后重试。 */
        RETRY_LATER
    }

    /**
     * 创建并校验凭证探活结果。
     *
     * @param status 探活状态
     * @param accountKey 非敏感账号键
     * @param code 结果机器码
     * @param retryAfterMillis 建议重试延迟毫秒数
     */
    public ScheduledCredentialProbeResult {
        if (status == null) {
            throw new IllegalArgumentException("credential status must not be null");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("credential result code must not be blank");
        }
        if (retryAfterMillis < 0) {
            throw new IllegalArgumentException("retry delay must not be negative");
        }
        accountKey = accountKey == null || accountKey.isBlank() ? null : accountKey.trim();
        code = code.trim();
        if (status == Status.VALID && accountKey == null) {
            throw new IllegalArgumentException("valid credential must provide a non-sensitive account key");
        }
        if (status != Status.RETRY_LATER && retryAfterMillis != 0) {
            throw new IllegalArgumentException("only retry-later result may carry a retry delay");
        }
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param accountKey 账号键
     * @return 方法返回的 {@code ScheduledCredentialProbeResult} 实例
     */
    public static ScheduledCredentialProbeResult valid(String accountKey) {
        return new ScheduledCredentialProbeResult(Status.VALID, accountKey, "credential.valid", 0L);
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param code 代码
     * @return 方法返回的 {@code ScheduledCredentialProbeResult} 实例
     */
    public static ScheduledCredentialProbeResult invalid(String code) {
        return new ScheduledCredentialProbeResult(Status.INVALID, null, code, 0L);
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param code 代码
     * @param retryAfterMillis 重试后毫秒数
     * @return 方法返回的 {@code ScheduledCredentialProbeResult} 实例
     */
    public static ScheduledCredentialProbeResult retryLater(String code, long retryAfterMillis) {
        return new ScheduledCredentialProbeResult(Status.RETRY_LATER, null, code, retryAfterMillis);
    }
}
