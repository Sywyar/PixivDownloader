package top.sywyar.pixivdownload.plugin.api.schedule.guard;

/** Guard 返回给宿主的稳定动作；插件不得借此直接修改核心持久化。 */
public record ScheduledGuardDecision(
        Action action,
        String reasonCode,
        long retryAfterMillis
) {

    public enum Action {
        CONTINUE,
        REVOKE_CREDENTIAL_AND_CONTINUE,
        SUSPEND_CREDENTIAL,
        SUSPEND_POLICY_TASK,
        SUSPEND_POLICY_ACCOUNT,
        RETRY_LATER,
        FAIL
    }

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

    public static ScheduledGuardDecision proceed() {
        return new ScheduledGuardDecision(Action.CONTINUE, null, 0L);
    }
}
