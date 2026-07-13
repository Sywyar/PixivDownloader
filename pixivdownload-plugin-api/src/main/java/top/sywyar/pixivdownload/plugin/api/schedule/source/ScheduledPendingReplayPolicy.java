package top.sywyar.pixivdownload.plugin.api.schedule.source;

/** 来源对耐久 pending 作品的重放策略。 */
public enum ScheduledPendingReplayPolicy {

    /** 每轮在来源发现之外重放全部尚可重试的 pending。 */
    ALWAYS,

    /** 仅当本轮来源再次发现相同 {@code workType + id} 时重放对应 pending。 */
    REDISCOVERED_ONLY
}
