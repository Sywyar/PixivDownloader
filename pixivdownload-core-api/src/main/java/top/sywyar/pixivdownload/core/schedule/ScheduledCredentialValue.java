package top.sywyar.pixivdownload.core.schedule;

/**
 * 凭据存储专用的有序项，不得进入任务视图、定义、队列或日志。
 * @param accountKey 策略验证后的账号标识
 * @param secret 非空凭据材料
 */
public record ScheduledCredentialValue(String accountKey, String secret) {
    /**
     * 创建并校验凭据存储项。
     * @param accountKey 策略验证后的账号标识
     * @param secret 非空凭据材料
     */
    public ScheduledCredentialValue {
        if (secret == null || secret.isBlank() || secret.length() > 16_384)
            throw new IllegalArgumentException("Invalid scheduled credential");
    }
    @Override public String toString() { return "ScheduledCredentialValue[redacted]"; }
}
