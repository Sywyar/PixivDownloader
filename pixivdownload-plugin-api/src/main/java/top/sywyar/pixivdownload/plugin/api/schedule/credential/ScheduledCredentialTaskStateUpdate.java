package top.sywyar.pixivdownload.plugin.api.schedule.credential;

/** 账号级动作计划中的单任务策略状态 CAS 更新。 */
public record ScheduledCredentialTaskStateUpdate(
        long taskId,
        long expectedStateVersion,
        String expectedPolicyStateJson,
        String nextPolicyStateJson
) {

    /**
     * 创建 {@code ScheduledCredentialTaskStateUpdate} 实例。
     *
     * @param taskId 任务标识
     * @param expectedStateVersion 期望值状态版本
     * @param expectedPolicyStateJson 期望值策略状态JSON
     * @param nextPolicyStateJson 下次值策略状态JSON
     */
    public ScheduledCredentialTaskStateUpdate {
        if (taskId <= 0) {
            throw new IllegalArgumentException("credential state update task id must be positive");
        }
        if (expectedStateVersion < 0) {
            throw new IllegalArgumentException(
                    "credential state update version must not be negative");
        }
        expectedPolicyStateJson = ScheduledCredentialTaskSnapshot.validateSafeText(
                expectedPolicyStateJson,
                "expected credential policy state",
                ScheduledCredentialTaskSnapshot.MAX_POLICY_STATE_BYTES,
                false);
        nextPolicyStateJson = ScheduledCredentialTaskSnapshot.validateSafeText(
                nextPolicyStateJson,
                "next credential policy state",
                ScheduledCredentialTaskSnapshot.MAX_POLICY_STATE_BYTES,
                false);
        if (nextPolicyStateJson == null) {
            throw new IllegalArgumentException("next credential policy state must not be blank");
        }
    }
}
