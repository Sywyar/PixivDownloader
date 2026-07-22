package top.sywyar.pixivdownload.core.schedule.db;

/** Store 实现执行凭证身份与策略状态 CAS 所需的最小持久化投影。 */
public record ScheduledCredentialBinding(
        String policyOwnerPluginId,
        String policyId,
        String policyStateJson
) {
}
