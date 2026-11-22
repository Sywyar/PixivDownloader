package top.sywyar.pixivdownload.plugin.api.schedule.credential;

/**
 * 凭证策略对单个任务的安全机器态展示投影；未提供投影时使用 {@link #empty()}。
 *
 * @param statusCode 状态码
 * @param acknowledgedEventTime 已确认事件时间
 * @param accountActionsSchemaJson 此策略 publication 支持的 actionId 与 parameters 的 JSON Schema，可为空。只含公开声明与示例，不得含账号数据
 */
public record ScheduledCredentialTaskPresentation(
        String statusCode,
        Long acknowledgedEventTime,
        String accountActionsSchemaJson
) {

    /**
     * 创建没有账号动作声明的任务状态。
     * @param statusCode 策略提供的状态码
     * @param acknowledgedEventTime 最近确认的事件时间（Unix 毫秒）
     */
    public ScheduledCredentialTaskPresentation(String statusCode, Long acknowledgedEventTime) {
        this(statusCode, acknowledgedEventTime, null);
    }

    /**
     * 创建 {@code ScheduledCredentialTaskPresentation} 实例。
     *
     * @param statusCode 状态码
     * @param acknowledgedEventTime 已确认事件时间
     * @param accountActionsSchemaJson 账号动作的公开 JSON Schema，未声明时为空
     */
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

    /**
     * 返回空的 {@code ScheduledCredentialTaskPresentation} 实例。
     *
     * @return 方法返回的 {@code ScheduledCredentialTaskPresentation} 实例
     */
    public static ScheduledCredentialTaskPresentation empty() {
        return new ScheduledCredentialTaskPresentation(null, null);
    }
}
