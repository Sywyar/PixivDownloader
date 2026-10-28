package top.sywyar.pixivdownload.schedule;

/** 任务凭证挂起后的中性通知数据；不解释具体凭证格式或恢复方式。 */
public final class ScheduleCredentialSuspensionNotice {

    public enum Reason {CREDENTIAL_REJECTED, FAILURE_CIRCUIT_OPEN}

    private final Reason reason;
    /** 熔断时的连续失败次数（仅 {@code FAILURE_CIRCUIT_OPEN} 有意义）。 */
    private final int consecutiveFailures;
    /** 熔断时最近一次失败原因摘要（已脱敏、可空）。 */
    private final String lastErrorExcerpt;

    public ScheduleCredentialSuspensionNotice(Reason reason) {
        this(reason, 0, null);
    }

    public ScheduleCredentialSuspensionNotice(
            Reason reason,
            int consecutiveFailures,
            String lastErrorExcerpt) {
        this.reason = reason;
        this.consecutiveFailures = consecutiveFailures;
        this.lastErrorExcerpt = lastErrorExcerpt;
    }

    public Reason reason() {
        return reason;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public String lastErrorExcerpt() {
        return lastErrorExcerpt;
    }
}
