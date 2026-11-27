package top.sywyar.pixivdownload.plugin.api.download.context;

/** 本次有序凭据均不可用；调用方应保留待处理项，不再重放整项操作。 */
public final class AcquisitionCredentialsUnavailableException extends RuntimeException {
    /** 是否有候选因已知上游限流而进入冷却。 */
    private final boolean rateLimited;
    /** 最早可重试的等待毫秒数。 */
    private final long retryAfterMillis;

    /**
     * 创建凭据均不可用的失败信息。
     * @param retryAfterMillis 最早可重试的等待毫秒数；零表示需要更新凭据
     */
    public AcquisitionCredentialsUnavailableException(long retryAfterMillis) {
        this(retryAfterMillis, false);
    }

    /** 创建带有上游限流归因的凭据耗尽诊断。
     * @param retryAfterMillis 最早可重试的等待毫秒数
     * @param rateLimited 是否有候选因上游限流而进入冷却 */
    public AcquisitionCredentialsUnavailableException(long retryAfterMillis, boolean rateLimited) {
        super("Acquisition credentials unavailable");
        this.rateLimited = rateLimited;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    /** {@return 是否有候选因上游限流而进入冷却} */
    public boolean rateLimited() { return rateLimited; }

    /** {@return 冷却等待毫秒数，不包含凭据或账号信息} */
    public long retryAfterMillis() { return retryAfterMillis; }
}
