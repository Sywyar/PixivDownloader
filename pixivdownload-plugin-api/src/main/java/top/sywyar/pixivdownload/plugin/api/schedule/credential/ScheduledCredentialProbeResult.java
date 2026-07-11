package top.sywyar.pixivdownload.plugin.api.schedule.credential;

/** 凭证格式检查与主动探活的安全结果。 */
public record ScheduledCredentialProbeResult(
        Status status,
        String accountKey,
        String code,
        long retryAfterMillis
) {

    public enum Status {
        VALID,
        INVALID,
        RETRY_LATER
    }

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

    public static ScheduledCredentialProbeResult valid(String accountKey) {
        return new ScheduledCredentialProbeResult(Status.VALID, accountKey, "credential.valid", 0L);
    }

    public static ScheduledCredentialProbeResult invalid(String code) {
        return new ScheduledCredentialProbeResult(Status.INVALID, null, code, 0L);
    }

    public static ScheduledCredentialProbeResult retryLater(String code, long retryAfterMillis) {
        return new ScheduledCredentialProbeResult(Status.RETRY_LATER, null, code, retryAfterMillis);
    }
}
