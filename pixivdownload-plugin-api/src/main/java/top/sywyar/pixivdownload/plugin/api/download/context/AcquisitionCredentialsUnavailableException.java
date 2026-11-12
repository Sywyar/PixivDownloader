package top.sywyar.pixivdownload.plugin.api.download.context;

/** 本次有序凭据均不可用；调用方应保留待处理项，不再重放整项操作。 */
public final class AcquisitionCredentialsUnavailableException extends RuntimeException {
    /** 最早可重试的等待毫秒数。 */
    private final long retryAfterMillis;

    /**
     * 创建凭据均不可用的失败信息。
     * @param retryAfterMillis 最早可重试的等待毫秒数；零表示需要更新凭据
     */
    public AcquisitionCredentialsUnavailableException(long retryAfterMillis) {
        super("Acquisition credentials unavailable");
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    /** {@return 冷却等待毫秒数，不包含凭据或账号信息} */
    public long retryAfterMillis() { return retryAfterMillis; }
}
