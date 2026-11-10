package top.sywyar.pixivdownload.core.schedule;

/** 凭据存储专用的有序项，不得进入任务视图、定义、队列或日志。 */
public record ScheduledCredentialValue(String accountKey, String secret) {
    public ScheduledCredentialValue {
        if (secret == null || secret.isBlank() || secret.length() > 16_384)
            throw new IllegalArgumentException("Invalid scheduled credential");
    }
    @Override public String toString() { return "ScheduledCredentialValue[redacted]"; }
}
