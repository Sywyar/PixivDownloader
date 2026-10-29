package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;

/** 宿主在连续凭证分类失败达到阈值时生成的安全熔断投影。 */
public final class ScheduleCredentialCircuitOpenException extends ScheduledExecutionException {

    private final int consecutiveFailures;
    private final String lastFailureCode;

    public ScheduleCredentialCircuitOpenException(
            int consecutiveFailures,
            String lastFailureCode) {
        super(ScheduledFailure.Category.CREDENTIAL_INVALID,
                "schedule.credential.failure-circuit-open");
        if (consecutiveFailures <= 0) {
            throw new IllegalArgumentException("consecutive failures must be positive");
        }
        if (lastFailureCode == null || lastFailureCode.isBlank()) {
            throw new IllegalArgumentException("last failure code must not be blank");
        }
        this.consecutiveFailures = consecutiveFailures;
        this.lastFailureCode = lastFailureCode.trim();
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public String lastFailureCode() {
        return lastFailureCode;
    }
}
