package top.sywyar.pixivdownload.core.schedule.migration;

import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 旧计划任务适配结果：完整转换，或明确保全原始数据的拒绝。 */
public sealed interface LegacyScheduledTaskMigrationResult
        permits LegacyScheduledTaskMigrationResult.Migrated,
        LegacyScheduledTaskMigrationResult.Rejected {

    int MAX_SAFE_DETAIL_BYTES = 262_144;

    /** 可原子写入中性事实表的完整转换计划。 */
    record Migrated(
            ScheduledTaskDefinition definition,
            ScheduledCheckpoint checkpoint,
            Credential credential,
            List<PendingWork> pending,
            String canonicalSuspendCode
    ) implements LegacyScheduledTaskMigrationResult {

        public Migrated {
            if (definition == null) {
                throw new IllegalArgumentException("migrated definition must not be null");
            }
            pending = pending == null ? List.of() : List.copyOf(pending);
            if (pending.stream().anyMatch(item -> item == null)) {
                throw new IllegalArgumentException("migrated pending work must not contain null");
            }
            canonicalSuspendCode = normalize(canonicalSuspendCode);
        }
    }

    /**
     * 可保全但无法安全推断的旧数据。协调器保留 storage version、secret 与旧 pending，
     * 并把任务置为 {@code MIGRATION_ERROR}。
     */
    record Rejected(String code, String safeDetailJson) implements LegacyScheduledTaskMigrationResult {

        public Rejected {
            code = requireText(code, "migration rejection code");
            safeDetailJson = requireText(safeDetailJson, "migration rejection detail JSON");
            if (safeDetailJson.getBytes(StandardCharsets.UTF_8).length > MAX_SAFE_DETAIL_BYTES) {
                throw new IllegalArgumentException("migration rejection detail exceeds size limit");
            }
        }
    }

    /** 凭证策略与账号元数据；不携带 secret。 */
    record Credential(
            String policyOwnerPluginId,
            String policyId,
            String accountKey,
            String secretReference,
            String policyStateJson,
            long updatedTime
    ) {

        public Credential {
            policyOwnerPluginId = requireText(policyOwnerPluginId, "credential policy owner");
            policyId = requireText(policyId, "credential policy id");
            accountKey = normalize(accountKey);
            secretReference = normalize(secretReference);
            policyStateJson = policyStateJson == null || policyStateJson.isBlank()
                    ? "{}" : policyStateJson.trim();
            if (policyStateJson.getBytes(StandardCharsets.UTF_8).length > MAX_SAFE_DETAIL_BYTES) {
                throw new IllegalArgumentException("credential policy state exceeds size limit");
            }
            if (updatedTime < 0) {
                throw new IllegalArgumentException("credential updated time must not be negative");
            }
        }
    }

    /** 一条旧 pending 行的无损中性信封。 */
    record PendingWork(
            String legacyWorkId,
            ScheduledWork work,
            String reasonCode,
            String reasonDetailJson,
            int attempts,
            Long firstSeenTime,
            Long lastAttemptTime
    ) {

        public PendingWork {
            legacyWorkId = requireText(legacyWorkId, "legacy pending work id");
            if (work == null) {
                throw new IllegalArgumentException("migrated pending work must not be null");
            }
            reasonCode = normalize(reasonCode);
            reasonDetailJson = normalize(reasonDetailJson);
            if (attempts < 0) {
                throw new IllegalArgumentException("migrated pending attempts must not be negative");
            }
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
