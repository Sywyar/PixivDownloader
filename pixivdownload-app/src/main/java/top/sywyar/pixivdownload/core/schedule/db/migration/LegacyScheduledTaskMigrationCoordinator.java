package top.sywyar.pixivdownload.core.schedule.db.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry.ReservedMigrationSnapshot;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityReservation;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationAdapter;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult.Credential;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult.Migrated;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult.PendingWork;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationResult.Rejected;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationRoute;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationService.OwnerMigrationReport;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskSnapshot;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskSnapshot.PendingRow;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把旧格式计划任务按预留 owner 转换为中性任务、检查点、凭证与 pending 事实。
 *
 * <p>调用方必须传入 registry 当前有效的不透明 reservation。协调器从绑定 registry 的预留项推导 owner 与
 * legacy alias → canonical source type 快照，只扫描该快照声明的旧类型，
 * 并强制适配结果使用对应 canonical type、当前 owner 与已声明 credential policy，防止适配器跨 owner 占用数据。
 * 适配器仅在方法栈上同步调用，
 * 本类不保留它或任何插件异常引用。
 *
 * <p>每条任务使用独立事务。成功路径在回读确认新 secret 和新 pending 后才清理旧值，
 * 最后以 CAS 把 {@code storage_version} 从 0 改为 1。可保全拒绝与适配器异常保留版本 0、旧 secret 与旧 pending；
 * 相同错误不重复增加 {@code state_version}，因此连续重试的磁盘快照稳定。
 */
public final class LegacyScheduledTaskMigrationCoordinator implements LegacyScheduledTaskMigrationService {

    public static final int LEGACY_STORAGE_VERSION = 0;
    public static final int CANONICAL_STORAGE_VERSION = 1;

    private static final String SUSPEND_MIGRATION_ERROR = "MIGRATION_ERROR";
    private static final String ADAPTER_FAILURE_CODE = "ADAPTER_FAILURE";
    public static final String MIGRATION_SPEC_UNAVAILABLE = "MIGRATION_SPEC_UNAVAILABLE";
    private static final String EMPTY_DETAIL_JSON = "{}";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final MigrationFaultInjector faultInjector;
    private final ScheduleCapabilityRegistry capabilityRegistry;

    public LegacyScheduledTaskMigrationCoordinator(JdbcTemplate jdbcTemplate,
                                                   ObjectMapper objectMapper,
                                                   PlatformTransactionManager transactionManager,
                                                   ScheduleCapabilityRegistry capabilityRegistry) {
        this(jdbcTemplate, objectMapper, new TransactionTemplate(transactionManager), taskId -> {},
                capabilityRegistry);
    }

    /** 显式事务模板构造器供真实 SQLite 事务与故障注入测试使用。 */
    public LegacyScheduledTaskMigrationCoordinator(JdbcTemplate jdbcTemplate,
                                                   ObjectMapper objectMapper,
                                                   TransactionTemplate transactionTemplate,
                                                   MigrationFaultInjector faultInjector,
                                                   ScheduleCapabilityRegistry capabilityRegistry) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.faultInjector = Objects.requireNonNull(faultInjector, "faultInjector");
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry");
    }

    /**
     * 迁移一个 owner 当前 reservation 所拥有的全部旧任务。
     *
     * @param reservation registrar 当前栈内持有、尚未 commit/release 的对象身份 token
     * @param adapter     owner child context 提供的短生命适配器
     */
    @Override
    public synchronized OwnerMigrationReport migrateReservedOwner(
            ScheduleCapabilityReservation reservation,
            LegacyScheduledTaskMigrationAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        ReservedMigrationSnapshot reserved = capabilityRegistry.reservedMigrationSnapshot(reservation);
        String owner = requireText(reserved.ownerPluginId(), "reserved owner plugin id");
        Map<String, LegacyScheduledTaskMigrationRoute> routes = normalizeRoutes(reserved.routes());
        if (routes.isEmpty()) {
            return new OwnerMigrationReport(owner, 0, 0, 0, 0);
        }

        List<Long> taskIds = findLegacyTaskIds(routes.keySet());
        int migrated = 0;
        int rejected = 0;
        int failed = 0;
        for (long taskId : taskIds) {
            try {
                TaskOutcome outcome = transactionTemplate.execute(status ->
                        migrateOne(owner, routes, adapter, taskId));
                if (outcome == TaskOutcome.MIGRATED) {
                    migrated++;
                } else if (outcome == TaskOutcome.REJECTED) {
                    rejected++;
                }
            } catch (Throwable adapterOrValidationFailure) {
                if (adapterOrValidationFailure instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                if (adapterOrValidationFailure instanceof ThreadDeath fatal) {
                    throw fatal;
                }
                // 不保存、不记录、不抛出插件异常或 cause，避免 child classloader 被长生命对象持有。
                markMigrationError(taskId, ADAPTER_FAILURE_CODE, EMPTY_DETAIL_JSON);
                failed++;
            }
        }
        return new OwnerMigrationReport(owner, taskIds.size(), migrated, rejected, failed);
    }

    private TaskOutcome migrateOne(String owner,
                                   Map<String, LegacyScheduledTaskMigrationRoute> routes,
                                   LegacyScheduledTaskMigrationAdapter adapter,
                                   long taskId) {
        LegacyScheduledTaskSnapshot snapshot = readSnapshot(taskId);
        if (snapshot == null || !routes.containsKey(snapshot.sourceType())) {
            return TaskOutcome.SKIPPED;
        }
        LegacyScheduledTaskMigrationRoute route = routes.get(snapshot.sourceType());
        if (!route.hasPersistenceContract()) {
            markMigrationErrorInCurrentTransaction(
                    taskId, MIGRATION_SPEC_UNAVAILABLE, EMPTY_DETAIL_JSON);
            return TaskOutcome.REJECTED;
        }

        LegacyScheduledTaskMigrationResult result = adapter.migrate(snapshot);
        if (result == null) {
            throw new IllegalArgumentException("legacy schedule adapter returned null");
        }
        if (result instanceof Rejected rejection) {
            validateJson(rejection.safeDetailJson(), "migration rejection detail");
            markMigrationErrorInCurrentTransaction(taskId, rejection.code(), rejection.safeDetailJson());
            return TaskOutcome.REJECTED;
        }

        Migrated plan = (Migrated) result;
        validatePlan(owner, snapshot, route, plan);

        String legacySecret = readLegacySecret(taskId);
        if (snapshot.hasLegacySecret() != (legacySecret != null)) {
            throw new IllegalStateException("legacy schedule secret changed during migration");
        }
        if (legacySecret != null && plan.credential() == null) {
            throw new IllegalArgumentException("legacy secret has no credential migration metadata");
        }
        if (plan.credential() != null) {
            validateJson(plan.credential().policyStateJson(), "credential policy state");
        }

        StateProjection state = projectState(snapshot, plan.canonicalSuspendCode());
        writeCanonicalTask(owner, plan, state, taskId);
        writeCredential(taskId, plan.credential(), legacySecret);
        writePending(taskId, plan.pending());
        faultInjector.afterCanonicalWrites(taskId);

        verifyCredential(taskId, plan.credential(), legacySecret);
        verifyPending(taskId, plan.pending());

        jdbcTemplate.update("UPDATE scheduled_tasks SET cookie_snapshot = NULL WHERE id = ?", taskId);
        jdbcTemplate.update("DELETE FROM scheduled_task_pending WHERE task_id = ?", taskId);
        int completed = jdbcTemplate.update(
                "UPDATE scheduled_tasks SET storage_version = ? WHERE id = ? AND storage_version = ?",
                CANONICAL_STORAGE_VERSION, taskId, LEGACY_STORAGE_VERSION);
        if (completed != 1) {
            throw new IllegalStateException("legacy schedule storage version CAS failed");
        }
        return TaskOutcome.MIGRATED;
    }

    private List<Long> findLegacyTaskIds(Set<String> aliases) {
        List<String> ordered = aliases.stream().sorted().toList();
        String placeholders = String.join(",", ordered.stream().map(alias -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(LEGACY_STORAGE_VERSION);
        args.addAll(ordered);
        return jdbcTemplate.queryForList(
                "SELECT id FROM scheduled_tasks WHERE storage_version = ? AND type IN ("
                        + placeholders + ") ORDER BY id",
                Long.class, args.toArray());
    }

    private LegacyScheduledTaskSnapshot readSnapshot(long taskId) {
        List<LegacyTaskFields> rows = jdbcTemplate.query(
                "SELECT id, name, enabled, type, params_json, trigger_kind, interval_minutes, cron_expr,"
                        + " cookie_mode, CASE WHEN cookie_snapshot IS NULL THEN 0 ELSE 1 END AS has_secret,"
                        + " proxy_snapshot, next_run_time, last_run_time, last_status, last_message,"
                        + " watermark_id, run_started_time, account_id, ack_warning_time,"
                        + " pending_retry_armed, created_time"
                        + " FROM scheduled_tasks WHERE id = ? AND storage_version = ?",
                (rs, rowNum) -> new LegacyTaskFields(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("enabled") != 0,
                        rs.getString("type"),
                        rs.getString("params_json"),
                        rs.getString("trigger_kind"),
                        nullableInt(rs, "interval_minutes"),
                        rs.getString("cron_expr"),
                        rs.getString("cookie_mode"),
                        rs.getInt("has_secret") != 0,
                        rs.getString("proxy_snapshot"),
                        nullableLong(rs, "next_run_time"),
                        nullableLong(rs, "last_run_time"),
                        rs.getString("last_status"),
                        rs.getString("last_message"),
                        nullableLong(rs, "watermark_id"),
                        nullableLong(rs, "run_started_time"),
                        rs.getString("account_id"),
                        nullableLong(rs, "ack_warning_time"),
                        rs.getInt("pending_retry_armed"),
                        rs.getLong("created_time")),
                taskId, LEGACY_STORAGE_VERSION);
        return rows.isEmpty() ? null : rows.get(0).toSnapshot(readLegacyPending(taskId));
    }

    private List<PendingRow> readLegacyPending(long taskId) {
        return jdbcTemplate.query(
                "SELECT task_id, CAST(work_id AS TEXT) AS work_id, reason, attempts,"
                        + " first_seen_time, last_attempt_time"
                        + " FROM scheduled_task_pending WHERE task_id = ? ORDER BY work_id",
                (rs, rowNum) -> new PendingRow(
                        rs.getLong("task_id"),
                        rs.getString("work_id"),
                        rs.getString("reason"),
                        rs.getInt("attempts"),
                        nullableLong(rs, "first_seen_time"),
                        nullableLong(rs, "last_attempt_time")),
                taskId);
    }

    private String readLegacySecret(long taskId) {
        List<String> values = jdbcTemplate.query(
                "SELECT cookie_snapshot FROM scheduled_tasks WHERE id = ? AND storage_version = ?",
                (rs, rowNum) -> rs.getString(1), taskId, LEGACY_STORAGE_VERSION);
        return values.isEmpty() ? null : values.get(0);
    }

    private void validatePlan(String owner,
                              LegacyScheduledTaskSnapshot snapshot,
                              LegacyScheduledTaskMigrationRoute route,
                              Migrated plan) {
        ScheduledTaskDefinition definition = plan.definition();
        if (definition.taskId() != snapshot.id()) {
            throw new IllegalArgumentException("migrated definition belongs to another task");
        }
        if (!route.canonicalSourceType().equals(definition.sourceType())) {
            throw new IllegalArgumentException("migrated source type is outside the owner publication");
        }
        if (route.hasPersistenceContract()
                && (!route.definitionSchema().equals(definition.definitionSchema())
                || route.definitionVersion() != definition.definitionVersion())) {
            throw new IllegalArgumentException("migrated definition contract differs from owner publication");
        }
        validateJson(definition.definitionJson(), "migrated definition");
        if (plan.checkpoint() != null) {
            validateJson(plan.checkpoint().payloadJson(), "migrated checkpoint");
        }
        Credential credential = plan.credential();
        if (credential != null) {
            var policyTarget = route.credentialPolicyTarget(credential.policyId()).orElse(null);
            if (policyTarget == null) {
                throw new IllegalArgumentException(
                        "migrated credential policy is outside the source persistence descriptor");
            }
            if (!policyTarget.policyOwnerPluginId().equals(credential.policyOwnerPluginId())) {
                throw new IllegalArgumentException(
                        "migrated credential policy owner differs from reservation stamp");
            }
        }

        Map<String, PendingRow> legacyById = new LinkedHashMap<>();
        for (PendingRow row : snapshot.pending()) {
            if (legacyById.put(row.workId(), row) != null) {
                throw new IllegalArgumentException("duplicate legacy pending work id");
            }
        }
        Set<String> newKeys = new HashSet<>();
        for (PendingWork pending : plan.pending()) {
            PendingRow legacy = legacyById.remove(pending.legacyWorkId());
            if (legacy == null) {
                throw new IllegalArgumentException("migrated pending work has no legacy source row");
            }
            if (!pending.legacyWorkId().equals(pending.work().key().id())) {
                throw new IllegalArgumentException("migrated pending work id changed");
            }
            if (!newKeys.add(pending.work().key().workType() + '\u0000' + pending.work().key().id())) {
                throw new IllegalArgumentException("duplicate migrated pending work key");
            }
            if (route.hasPersistenceContract()
                    && !route.allowedWorkTypes().contains(pending.work().key().workType())) {
                throw new IllegalArgumentException(
                        "migrated pending work type is outside the source descriptor");
            }
            if (pending.attempts() != legacy.attempts()
                    || !Objects.equals(pending.firstSeenTime(), legacy.firstSeenTime())
                    || !Objects.equals(pending.lastAttemptTime(), legacy.lastAttemptTime())) {
                throw new IllegalArgumentException("migrated pending retry history changed");
            }
            validateJson(pending.work().payloadJson(), "migrated pending payload");
            if (pending.reasonDetailJson() != null) {
                validateJson(pending.reasonDetailJson(), "migrated pending reason detail");
            }
        }
        if (!legacyById.isEmpty() || plan.pending().size() != snapshot.pending().size()) {
            throw new IllegalArgumentException("legacy pending work was not fully accounted for");
        }
    }

    private void writeCanonicalTask(String owner,
                                    Migrated plan,
                                    StateProjection state,
                                    long taskId) {
        ScheduledTaskDefinition definition = plan.definition();
        ScheduledCheckpoint checkpoint = plan.checkpoint();
        int updated = jdbcTemplate.update(
                "UPDATE scheduled_tasks SET type = ?, params_json = ?, source_owner_plugin_id = ?,"
                        + " definition_schema = ?, definition_version = ?, presentation_json = ?,"
                        + " checkpoint_schema = ?, checkpoint_version = ?, checkpoint_json = ?,"
                        + " next_run_time = CASE WHEN run_started_time IS NOT NULL AND enabled = 1"
                        + " THEN 0 ELSE next_run_time END,"
                        + " run_started_time = NULL, run_state = NULL, run_claim_token = NULL,"
                        + " last_outcome = ?, outcome_code = ?, outcome_message = ?,"
                        + " suspend_reason = ?, suspend_code = ?, suspend_detail_json = ?"
                        + " WHERE id = ? AND storage_version = ?",
                definition.sourceType(),
                definition.definitionJson(),
                owner,
                definition.definitionSchema(),
                definition.definitionVersion(),
                serialize(definition.presentation()),
                checkpoint == null ? null : checkpoint.schema(),
                checkpoint == null ? null : checkpoint.version(),
                checkpoint == null ? null : checkpoint.payloadJson(),
                state.lastOutcome(),
                state.outcomeCode(),
                state.outcomeMessage(),
                state.suspendReason(),
                state.suspendCode(),
                state.suspendDetailJson(),
                taskId,
                LEGACY_STORAGE_VERSION);
        if (updated != 1) {
            throw new IllegalStateException("legacy schedule canonical update lost its row");
        }
    }

    private void writeCredential(long taskId, Credential credential, String legacySecret) {
        if (credential == null) {
            jdbcTemplate.update("DELETE FROM scheduled_task_credentials WHERE task_id = ?", taskId);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO scheduled_task_credentials(task_id, policy_owner_plugin_id, policy_id,"
                        + " account_key, secret, secret_reference, policy_state_json, updated_time)"
                        + " VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(task_id) DO UPDATE SET"
                        + " policy_owner_plugin_id = excluded.policy_owner_plugin_id,"
                        + " policy_id = excluded.policy_id, account_key = excluded.account_key,"
                        + " secret = excluded.secret, secret_reference = excluded.secret_reference,"
                        + " policy_state_json = excluded.policy_state_json, updated_time = excluded.updated_time",
                taskId, credential.policyOwnerPluginId(), credential.policyId(), credential.accountKey(),
                legacySecret, credential.secretReference(), credential.policyStateJson(), credential.updatedTime());
    }

    private void writePending(long taskId, List<PendingWork> pending) {
        jdbcTemplate.update("DELETE FROM scheduled_task_pending_work WHERE task_id = ?", taskId);
        for (PendingWork item : pending) {
            jdbcTemplate.update(
                    "INSERT INTO scheduled_task_pending_work(task_id, work_type, work_id,"
                            + " payload_schema, payload_version, payload_json, presentation_json, relations_json,"
                            + " reason_code, reason_detail_json, attempts, first_seen_time, last_attempt_time)"
                            + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    taskId,
                    item.work().key().workType(),
                    item.work().key().id(),
                    item.work().payloadSchema(),
                    item.work().payloadVersion(),
                    item.work().payloadJson(),
                    serialize(item.work().presentation()),
                    serialize(item.work().relations()),
                    item.reasonCode(),
                    item.reasonDetailJson(),
                    item.attempts(),
                    item.firstSeenTime(),
                    item.lastAttemptTime());
        }
    }

    private void verifyCredential(long taskId, Credential expected, String expectedSecret) {
        List<CredentialRow> rows = jdbcTemplate.query(
                "SELECT policy_owner_plugin_id, policy_id, account_key, secret, secret_reference,"
                        + " policy_state_json, updated_time"
                        + " FROM scheduled_task_credentials WHERE task_id = ?",
                (rs, rowNum) -> new CredentialRow(
                        rs.getString("policy_owner_plugin_id"),
                        rs.getString("policy_id"),
                        rs.getString("account_key"),
                        rs.getString("secret"),
                        rs.getString("secret_reference"),
                        rs.getString("policy_state_json"),
                        rs.getLong("updated_time")),
                taskId);
        if (expected == null) {
            if (!rows.isEmpty()) {
                throw new IllegalStateException("unexpected migrated credential row");
            }
            return;
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("migrated credential row is missing or duplicated");
        }
        CredentialRow actual = rows.get(0);
        if (!expected.policyOwnerPluginId().equals(actual.policyOwnerPluginId())
                || !expected.policyId().equals(actual.policyId())
                || !Objects.equals(expected.accountKey(), actual.accountKey())
                || !Objects.equals(expected.secretReference(), actual.secretReference())
                || !expected.policyStateJson().equals(actual.policyStateJson())
                || expected.updatedTime() != actual.updatedTime()
                || !secretEquals(expectedSecret, actual.secret())) {
            throw new IllegalStateException("migrated credential readback mismatch");
        }
    }

    private void verifyPending(long taskId, List<PendingWork> expected) {
        List<PendingStoredRow> actualRows = jdbcTemplate.query(
                "SELECT work_type, work_id, payload_schema, payload_version, payload_json,"
                        + " presentation_json, relations_json, reason_code, reason_detail_json, attempts,"
                        + " first_seen_time, last_attempt_time"
                        + " FROM scheduled_task_pending_work WHERE task_id = ? ORDER BY work_type, work_id",
                (rs, rowNum) -> new PendingStoredRow(
                        rs.getString("work_type"),
                        rs.getString("work_id"),
                        rs.getString("payload_schema"),
                        rs.getInt("payload_version"),
                        rs.getString("payload_json"),
                        rs.getString("presentation_json"),
                        rs.getString("relations_json"),
                        rs.getString("reason_code"),
                        rs.getString("reason_detail_json"),
                        rs.getInt("attempts"),
                        nullableLong(rs, "first_seen_time"),
                        nullableLong(rs, "last_attempt_time")),
                taskId);
        List<PendingStoredRow> expectedRows = expected.stream()
                .map(item -> new PendingStoredRow(
                        item.work().key().workType(),
                        item.work().key().id(),
                        item.work().payloadSchema(),
                        item.work().payloadVersion(),
                        item.work().payloadJson(),
                        serialize(item.work().presentation()),
                        serialize(item.work().relations()),
                        item.reasonCode(),
                        item.reasonDetailJson(),
                        item.attempts(),
                        item.firstSeenTime(),
                        item.lastAttemptTime()))
                .sorted((left, right) -> {
                    int type = left.workType().compareTo(right.workType());
                    return type != 0 ? type : left.workId().compareTo(right.workId());
                })
                .toList();
        if (!expectedRows.equals(actualRows)) {
            throw new IllegalStateException("migrated pending readback mismatch");
        }
    }

    private StateProjection projectState(
            LegacyScheduledTaskSnapshot snapshot, String canonicalSuspendCode) {
        String status = snapshot.lastStatus();
        String suspendReason = null;
        String suspendCode = null;
        if ("AUTH_EXPIRED".equals(status)) {
            suspendReason = "CREDENTIAL";
            suspendCode = status;
        } else if ("OVERUSE_PAUSED".equals(status)) {
            suspendReason = "POLICY";
            suspendCode = status;
        } else if ("PAUSED".equals(status)) {
            suspendReason = "MANUAL";
            suspendCode = status;
        } else if ("SOURCE_UNAVAILABLE".equals(status)) {
            suspendReason = "SOURCE_UNAVAILABLE";
            suspendCode = status;
        }
        if (suspendReason == null && canonicalSuspendCode != null) {
            throw new IllegalArgumentException(
                    "canonical suspend code requires a suspended legacy status");
        }
        if (suspendReason != null && canonicalSuspendCode != null) {
            suspendCode = canonicalSuspendCode;
        }

        String outcome;
        String outcomeCode = null;
        if (snapshot.runStartedTime() != null) {
            outcome = "INTERRUPTED";
            outcomeCode = "LEGACY_RUN_INTERRUPTED";
        } else if (status == null) {
            outcome = "NEVER";
        } else {
            outcome = switch (status) {
                case "OK" -> "OK";
                case "PAUSED" -> "CANCELLED";
                case "ERROR", "AUTH_EXPIRED", "OVERUSE_PAUSED", "SOURCE_UNAVAILABLE" -> "ERROR";
                default -> throw new IllegalArgumentException("unknown legacy schedule status");
            };
            if ("ERROR".equals(outcome)) {
                outcomeCode = "LEGACY_" + status;
            }
        }
        String suspendDetail = suspendReason == null
                ? null
                : serialize(Map.of("legacyStatus", status));
        return new StateProjection(outcome, outcomeCode, snapshot.lastMessage(),
                suspendReason, suspendCode, suspendDetail);
    }

    private void markMigrationError(long taskId, String code, String detailJson) {
        transactionTemplate.executeWithoutResult(status ->
                markMigrationErrorInCurrentTransaction(taskId, code, detailJson));
    }

    private void markMigrationErrorInCurrentTransaction(long taskId, String code, String detailJson) {
        jdbcTemplate.update(
                "UPDATE scheduled_tasks SET suspend_reason = ?, suspend_code = ?, suspend_detail_json = ?,"
                        + " state_version = state_version + 1"
                        + " WHERE id = ? AND storage_version = ?"
                        + " AND NOT (suspend_reason IS ? AND suspend_code IS ? AND suspend_detail_json IS ?)",
                SUSPEND_MIGRATION_ERROR, code, detailJson,
                taskId, LEGACY_STORAGE_VERSION,
                SUSPEND_MIGRATION_ERROR, code, detailJson);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("schedule migration value is not serializable");
        }
    }

    private void validateJson(String value, String label) {
        try {
            objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(label + " is not valid JSON");
        }
    }

    private static Map<String, LegacyScheduledTaskMigrationRoute> normalizeRoutes(
            Map<String, LegacyScheduledTaskMigrationRoute> routes) {
        Objects.requireNonNull(routes, "allowedLegacyRoutes");
        Map<String, LegacyScheduledTaskMigrationRoute> normalized = new LinkedHashMap<>();
        routes.forEach((legacy, route) -> {
            String normalizedLegacy = requireText(legacy, "legacy source alias");
            Objects.requireNonNull(route, "legacy migration route");
            LegacyScheduledTaskMigrationRoute previous = normalized.putIfAbsent(normalizedLegacy, route);
            if (previous != null && !previous.equals(route)) {
                throw new IllegalArgumentException("legacy source alias has conflicting canonical routes");
            }
        });
        return Map.copyOf(normalized);
    }

    private static boolean secretEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static Integer nullableInt(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    /** 只用于在真实事务中注入「写入后、确认前」故障的窄接口。 */
    @FunctionalInterface
    public interface MigrationFaultInjector {
        void afterCanonicalWrites(long taskId);
    }

    private enum TaskOutcome {MIGRATED, REJECTED, SKIPPED}

    private record StateProjection(
            String lastOutcome,
            String outcomeCode,
            String outcomeMessage,
            String suspendReason,
            String suspendCode,
            String suspendDetailJson
    ) {}

    private record CredentialRow(
            String policyOwnerPluginId,
            String policyId,
            String accountKey,
            String secret,
            String secretReference,
            String policyStateJson,
            long updatedTime
    ) {}

    private record PendingStoredRow(
            String workType,
            String workId,
            String payloadSchema,
            int payloadVersion,
            String payloadJson,
            String presentationJson,
            String relationsJson,
            String reasonCode,
            String reasonDetailJson,
            int attempts,
            Long firstSeenTime,
            Long lastAttemptTime
    ) {}

    private record LegacyTaskFields(
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
            long createdTime
    ) {
        LegacyScheduledTaskSnapshot toSnapshot(List<PendingRow> pending) {
            return new LegacyScheduledTaskSnapshot(id, name, enabled, sourceType, definitionJson,
                    triggerKind, intervalMinutes, cronExpr, credentialMode, hasLegacySecret,
                    proxySnapshot, nextRunTime, lastRunTime, lastStatus, lastMessage, watermarkId,
                    runStartedTime, accountId, acknowledgedWarningTime, pendingRetryArmed, createdTime,
                    pending);
        }
    }
}
