package top.sywyar.pixivdownload.plugin.api.schedule.guard;

/** Guard 一次固定检查点调用的稳定决定与受控安全证据。 */
public record ScheduledGuardResult(
        ScheduledGuardDecision decision,
        ScheduledGuardEvidence evidence
) {

    public ScheduledGuardResult {
        if (decision == null) {
            throw new IllegalArgumentException("guard result decision must not be null");
        }
        evidence = evidence == null ? ScheduledGuardEvidence.empty() : evidence;
    }

    public static ScheduledGuardResult decision(ScheduledGuardDecision decision) {
        return new ScheduledGuardResult(decision, ScheduledGuardEvidence.empty());
    }
}
