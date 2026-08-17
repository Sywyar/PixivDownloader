package top.sywyar.pixivdownload.plugin.api.schedule.guard;

/** Guard 一次固定检查点调用的稳定决定与受控安全证据；决定与证据均不得包含原始凭据或可逆派生材料。 */
public record ScheduledGuardResult(
        ScheduledGuardDecision decision,
        ScheduledGuardEvidence evidence
) {

    /**
     * 创建 {@code ScheduledGuardResult} 实例。
     *
     * @param decision 决策
     * @param evidence 证据
     */
    public ScheduledGuardResult {
        if (decision == null) {
            throw new IllegalArgumentException("guard result decision must not be null");
        }
        evidence = evidence == null ? ScheduledGuardEvidence.empty() : evidence;
    }

    /**
     * 执行决策并返回结果。
     *
     * @param decision 决策
     * @return 方法返回的 {@code ScheduledGuardResult} 实例
     */
    public static ScheduledGuardResult decision(ScheduledGuardDecision decision) {
        return new ScheduledGuardResult(decision, ScheduledGuardEvidence.empty());
    }
}
