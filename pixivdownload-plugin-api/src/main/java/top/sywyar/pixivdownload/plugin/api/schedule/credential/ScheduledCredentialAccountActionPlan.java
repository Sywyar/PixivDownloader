package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 凭证策略给宿主的账号级恢复计划。宿主仍拥有事务、逐任务 CAS 与按账号精确恢复；策略只解释自己的
 * 挂起机器码、下一运行时间和策略状态变换。
 */
public record ScheduledCredentialAccountActionPlan(
        String expectedSuspendCode,
        long nextRunTime,
        List<ScheduledCredentialTaskStateUpdate> stateUpdates
) {

    public static final int MAX_STATE_UPDATES = ScheduledCredentialAccountActionRequest.MAX_TASKS;

    public ScheduledCredentialAccountActionPlan {
        expectedSuspendCode = ScheduledCredentialAccountActionRequest.validateMachineCode(
                expectedSuspendCode, "credential account expected suspend code");
        if (nextRunTime < 0) {
            throw new IllegalArgumentException(
                    "credential account next run time must not be negative");
        }
        if (stateUpdates == null || stateUpdates.isEmpty()) {
            stateUpdates = List.of();
        } else {
            if (stateUpdates.size() > MAX_STATE_UPDATES) {
                throw new IllegalArgumentException(
                        "credential account action has too many state updates");
            }
            List<ScheduledCredentialTaskStateUpdate> copy = List.copyOf(stateUpdates);
            Set<Long> taskIds = new LinkedHashSet<>();
            for (ScheduledCredentialTaskStateUpdate update : copy) {
                if (update == null) {
                    throw new IllegalArgumentException(
                            "credential account action contains a null state update");
                }
                if (!taskIds.add(update.taskId())) {
                    throw new IllegalArgumentException(
                            "credential account action contains duplicate state updates");
                }
            }
            stateUpdates = copy;
        }
    }
}
