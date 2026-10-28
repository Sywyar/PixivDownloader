package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkNotificationPresentation;

/** 一轮通用计划执行在持久化最终 outcome 前返回给 claim 外壳的安全结果。 */
public record ScheduleExecutionResult(
        int completedWorkCount,
        ScheduledCheckpoint candidateCheckpoint,
        boolean credentialRevoked
) {

    public ScheduleExecutionResult {
        if (completedWorkCount < 0) {
            throw new IllegalArgumentException("completed work count must not be negative");
        }
    }

    public record PendingExhausted(
            String workType,
            String workId,
            int attempts,
            long triggerTime,
            String reasonCode,
            ScheduledWorkNotificationPresentation presentation
    ) {
        public PendingExhausted {
            presentation = presentation == null
                    ? ScheduledWorkNotificationPresentation.empty()
                    : new ScheduledWorkNotificationPresentation(
                    presentation.displayNamespace(),
                    presentation.displayNameKey(),
                    presentation.referenceUrl());
        }
    }
}
