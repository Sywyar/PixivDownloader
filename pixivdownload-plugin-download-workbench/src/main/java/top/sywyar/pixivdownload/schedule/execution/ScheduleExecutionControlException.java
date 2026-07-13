package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;

/** Guard 或凭证策略要求宿主执行的稳定控制决定；不携带插件异常或凭证材料。 */
public final class ScheduleExecutionControlException extends Exception {

    private final ScheduledGuardDecision.Action action;
    private final String reasonCode;
    private final long retryAfterMillis;
    private final ScheduledGuardEvidence evidence;

    public ScheduleExecutionControlException(
            ScheduledGuardDecision.Action action,
            String reasonCode,
            long retryAfterMillis,
            ScheduledGuardEvidence evidence) {
        super(reasonCode);
        this.action = action;
        this.reasonCode = reasonCode;
        this.retryAfterMillis = retryAfterMillis;
        this.evidence = evidence == null ? ScheduledGuardEvidence.empty() : evidence;
    }

    public ScheduledGuardDecision.Action action() {
        return action;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public ScheduledGuardEvidence evidence() {
        return evidence;
    }
}
