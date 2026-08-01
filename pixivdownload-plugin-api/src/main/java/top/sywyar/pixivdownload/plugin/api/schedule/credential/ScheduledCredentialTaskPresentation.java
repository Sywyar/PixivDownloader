package top.sywyar.pixivdownload.plugin.api.schedule.credential;

/** 凭证策略对单个任务的安全机器态展示投影；未提供投影时使用 {@link #empty()}。 */
public record ScheduledCredentialTaskPresentation(
        String statusCode,
        Long acknowledgedEventTime
) {

    public ScheduledCredentialTaskPresentation {
        if (statusCode != null && !statusCode.isBlank()) {
            statusCode = ScheduledCredentialAccountActionRequest.validateMachineCode(
                    statusCode, "credential task presentation status code");
        } else {
            statusCode = null;
        }
        if (acknowledgedEventTime != null && acknowledgedEventTime < 0) {
            throw new IllegalArgumentException(
                    "credential acknowledged event time must not be negative");
        }
    }

    public static ScheduledCredentialTaskPresentation empty() {
        return new ScheduledCredentialTaskPresentation(null, null);
    }
}
