package top.sywyar.pixivdownload.core.schedule.migration;

import java.util.List;

/**
 * 旧 {@code scheduled_tasks} 行与它的旧 pending 行快照。
 *
 * <p>快照故意不含 {@code cookie_snapshot}；{@link #hasLegacySecret()} 只是无法反推原值的布尔提示。
 * 旧 pending 的 ID 以文本读取，避免迁移契约继续扩散 SQLite INTEGER affinity。
 */
public record LegacyScheduledTaskSnapshot(
        long id,
        String name,
        boolean enabled,
        String sourceType,
        String definitionJson,
        String triggerKind,
        Integer intervalMinutes,
        String cronExpr,
        String credentialMode,
        boolean hasLegacySecret,
        String proxySnapshot,
        Long nextRunTime,
        Long lastRunTime,
        String lastStatus,
        String lastMessage,
        Long watermarkId,
        Long runStartedTime,
        String accountId,
        Long acknowledgedWarningTime,
        int pendingRetryArmed,
        long createdTime,
        List<PendingRow> pending
) {

    public LegacyScheduledTaskSnapshot {
        if (id <= 0) {
            throw new IllegalArgumentException("legacy task id must be positive");
        }
        name = requireText(name, "legacy task name");
        sourceType = requireText(sourceType, "legacy source type");
        definitionJson = requireText(definitionJson, "legacy definition JSON");
        triggerKind = requireText(triggerKind, "legacy trigger kind");
        credentialMode = requireText(credentialMode, "legacy credential mode");
        pending = pending == null ? List.of() : List.copyOf(pending);
        for (PendingRow row : pending) {
            if (row == null || row.taskId() != id) {
                throw new IllegalArgumentException("legacy pending row belongs to another task");
            }
        }
    }

    /** 旧 {@code scheduled_task_pending} 的无凭证行快照。 */
    public record PendingRow(
            long taskId,
            String workId,
            String reason,
            int attempts,
            Long firstSeenTime,
            Long lastAttemptTime
    ) {

        public PendingRow {
            if (taskId <= 0) {
                throw new IllegalArgumentException("legacy pending task id must be positive");
            }
            workId = requireText(workId, "legacy pending work id");
            if (attempts < 0) {
                throw new IllegalArgumentException("legacy pending attempts must not be negative");
            }
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
