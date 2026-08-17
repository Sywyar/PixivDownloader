package top.sywyar.pixivdownload.plugin.api.schedule.credential;

/** 一个执行计划对凭证的要求。 */
public enum ScheduledCredentialRequirement {
    /**
     * 表示 {@code NONE} 状态。
     */
    NONE,
    /**
     * 表示 {@code OPTIONAL} 状态。
     */
    OPTIONAL,
    /**
     * 表示 {@code REQUIRED}。
     */
    REQUIRED
}
