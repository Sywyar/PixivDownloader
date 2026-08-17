package top.sywyar.pixivdownload.plugin.api.schedule.guard;

/** Guard 返回给宿主的稳定动作；插件不得借此直接修改核心持久化。 */
public record ScheduledGuardDecision(
        Action action,
        String reasonCode,
        long retryAfterMillis
) {

    /** 守卫要求宿主执行的稳定动作。 */
    public enum Action {
        /**
         * 表示 {@code CONTINUE} 状态。
         */
        CONTINUE,
        /**
         * 表示 {@code REVOKE_CREDENTIAL_AND_CONTINUE} 状态。
         */
        REVOKE_CREDENTIAL_AND_CONTINUE,
        /**
         * 表示 {@code SUSPEND_CREDENTIAL} 状态。
         */
        SUSPEND_CREDENTIAL,
        /**
         * 表示 {@code SUSPEND_POLICY_TASK} 状态。
         */
        SUSPEND_POLICY_TASK,
        /**
         * 表示 {@code SUSPEND_POLICY_ACCOUNT} 状态。
         */
        SUSPEND_POLICY_ACCOUNT,
        /**
         * 表示 {@code RETRY_LATER} 状态。
         */
        RETRY_LATER,
        /** 终止执行并报告失败。 */
        FAIL
    }

    /**
     * 创建并校验守卫决策。
     *
     * @param action 宿主动作
     * @param reasonCode 原因机器码
     * @param retryAfterMillis 建议重试延迟毫秒数
     */
    public ScheduledGuardDecision {
        if (action == null) {
            throw new IllegalArgumentException("guard action must not be null");
        }
        if (retryAfterMillis < 0) {
            throw new IllegalArgumentException("retry delay must not be negative");
        }
        reasonCode = reasonCode == null || reasonCode.isBlank() ? null : reasonCode.trim();
        if (action == Action.CONTINUE && (reasonCode != null || retryAfterMillis != 0)) {
            throw new IllegalArgumentException("continue decision must not carry failure details");
        }
        if (action != Action.RETRY_LATER && retryAfterMillis != 0) {
            throw new IllegalArgumentException("only retry-later decision may carry a retry delay");
        }
        if (action != Action.CONTINUE && reasonCode == null) {
            throw new IllegalArgumentException("non-continue decision must provide a reason code");
        }
    }

    /**
     * 返回对应值。
     *
     * @return 方法返回的 {@code ScheduledGuardDecision} 实例
     */
    public static ScheduledGuardDecision proceed() {
        return new ScheduledGuardDecision(Action.CONTINUE, null, 0L);
    }
}
