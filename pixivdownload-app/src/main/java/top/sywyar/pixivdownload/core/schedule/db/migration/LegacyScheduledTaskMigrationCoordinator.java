package top.sywyar.pixivdownload.core.schedule.db.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Query;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;
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
    private static final String EMPTY_DETAIL_JSON = "{}";

    private final MainDatabase database;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final MigrationFaultInjector faultInjector;
    private final ScheduleCapabilityRegistry capabilityRegistry;

    public LegacyScheduledTaskMigrationCoordinator(MainDatabase database,
                                                   ObjectMapper objectMapper,
                                                   PlatformTransactionManager transactionManager,
                                                   ScheduleCapabilityRegistry capabilityRegistry) {
        this(database, objectMapper, new TransactionTemplate(transactionManager), taskId -> {},
                capabilityRegistry);
    }

    /** 显式事务模板构造器供真实 SQLite 事务与故障注入测试使用。 */
    public LegacyScheduledTaskMigrationCoordinator(MainDatabase database,
                                                   ObjectMapper objectMapper,
                                                   TransactionTemplate transactionTemplate,
                                                   MigrationFaultInjector faultInjector,
                                                   ScheduleCapabilityRegistry capabilityRegistry) {
        this.database = Objects.requireNonNull(database, "database");
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
        validatePlan(snapshot, route, plan);

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

        database.transaction(s -> s.update("scheduled_tasks", Map.of("cookie_snapshot", value(null)),
                eq("scheduled_tasks", "id", taskId)));
        database.transaction(s -> s.delete("scheduled_task_pending", eq("scheduled_task_pending", "task_id", taskId)));
        int completed = database.transaction(s -> s.update("scheduled_tasks",
                Map.of("storage_version", value(CANONICAL_STORAGE_VERSION)), legacyTask(taskId)));
        if (completed != 1) {
            throw new IllegalStateException("legacy schedule storage version CAS failed");
        }
        return TaskOutcome.MIGRATED;
    }

    private static Expression eq(String table, String key, Object value) {
        return call(EQUAL, column(table, key), value(value));
    }

    private static Expression legacyTask(long taskId) {
        return call(AND, eq("scheduled_tasks", "id", taskId),
                eq("scheduled_tasks", "storage_version", LEGACY_STORAGE_VERSION));
    }

    private static Map<String, Object> values(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], value(pairs[i + 1]).value());
        return result;
    }

    private static Map<String, Expression> changes(Map<String, Object> values) {
        var result = new LinkedHashMap<String, Expression>();
        values.forEach((name, v) -> result.put(name, value(v)));
        return result;
    }

    private List<Map<String, Object>> rows(String table, Expression where, List<Query.Order> order, String... names) {
        var select = new LinkedHashMap<String, Expression>();
        for (String name : names) select.put(name, column(table, name));
        return database.read(s -> s.query(new Query(table, select, where, List.of(), order, Integer.MAX_VALUE, 0)));
    }

    private static Query.Order ascending(String table, String name) {
        return new Query.Order(column(table, name), false, true);
    }

    private static String text(Map<String, Object> row, String name) { return (String) row.get(name); }
    private static Long number(Map<String, Object> row, String name) { return (Long) row.get(name); }
    private static Integer integer(Map<String, Object> row, String name) {
        Long result = number(row, name);
        return result == null ? null : Math.toIntExact(result);
    }

    private List<Long> findLegacyTaskIds(Set<String> aliases) {
        var types = new ArrayList<Expression>();
        types.add(column("scheduled_tasks", "type"));
        aliases.stream().sorted().forEach(alias -> types.add(value(alias)));
        return rows("scheduled_tasks", call(AND,
                eq("scheduled_tasks", "storage_version", LEGACY_STORAGE_VERSION), new Call(IN, types)),
                List.of(ascending("scheduled_tasks", "id")), "id").stream().map(row -> number(row, "id")).toList();
    }

    private LegacyScheduledTaskSnapshot readSnapshot(long taskId) {
        String table = "scheduled_tasks";
        var select = new LinkedHashMap<String, Expression>();
        for (String name : List.of("id", "name", "enabled", "type", "params_json", "trigger_kind", "interval_minutes",
                "cron_expr", "cookie_mode", "proxy_snapshot", "next_run_time", "last_run_time", "last_status",
                "last_message", "watermark_id", "run_started_time", "account_id", "ack_warning_time",
                "pending_retry_armed", "created_time")) select.put(name, column(table, name));
        select.put("has_secret", new Conditional(call(IS_NULL, column(table, "cookie_snapshot")), value(0), value(1)));
        var rows = database.read(s -> s.query(new Query(table, select, legacyTask(taskId), List.of(), List.of(), 1, 0)));
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        return new LegacyTaskFields(number(row, "id"), text(row, "name"), number(row, "enabled") != 0,
                text(row, "type"), text(row, "params_json"), text(row, "trigger_kind"), integer(row, "interval_minutes"),
                text(row, "cron_expr"), text(row, "cookie_mode"), number(row, "has_secret") != 0,
                text(row, "proxy_snapshot"), number(row, "next_run_time"), number(row, "last_run_time"),
                text(row, "last_status"), text(row, "last_message"), number(row, "watermark_id"),
                number(row, "run_started_time"), text(row, "account_id"), number(row, "ack_warning_time"),
                integer(row, "pending_retry_armed"), number(row, "created_time")).toSnapshot(readLegacyPending(taskId));
    }

    private List<PendingRow> readLegacyPending(long taskId) {
        String table = "scheduled_task_pending";
        return rows(table, eq(table, "task_id", taskId), List.of(ascending(table, "work_id")),
                "task_id", "work_id", "reason", "attempts", "first_seen_time", "last_attempt_time").stream()
                .map(row -> new PendingRow(number(row, "task_id"), number(row, "work_id").toString(),
                        text(row, "reason"), integer(row, "attempts"), number(row, "first_seen_time"),
                        number(row, "last_attempt_time"))).toList();
    }

    private String readLegacySecret(long taskId) {
        var rows = rows("scheduled_tasks", legacyTask(taskId), List.of(), "cookie_snapshot");
        return rows.isEmpty() ? null : text(rows.get(0), "cookie_snapshot");
    }

    private void validatePlan(LegacyScheduledTaskSnapshot snapshot,
                              LegacyScheduledTaskMigrationRoute route,
                              Migrated plan) {
        ScheduledTaskDefinition definition = plan.definition();
        if (definition.taskId() != snapshot.id()) {
            throw new IllegalArgumentException("migrated definition belongs to another task");
        }
        if (!route.canonicalSourceType().equals(definition.sourceType())) {
            throw new IllegalArgumentException("migrated source type is outside the owner publication");
        }
        if (!route.definitionSchema().equals(definition.definitionSchema())
                || route.definitionVersion() != definition.definitionVersion()) {
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
            if (!route.allowedWorkTypes().contains(pending.work().key().workType())) {
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
        var updates = changes(values("type", definition.sourceType(), "params_json", definition.definitionJson(),
                "source_owner_plugin_id", owner, "definition_schema", definition.definitionSchema(),
                "definition_version", definition.definitionVersion(), "presentation_json", serialize(definition.presentation()),
                "checkpoint_schema", checkpoint == null ? null : checkpoint.schema(),
                "checkpoint_version", checkpoint == null ? null : checkpoint.version(),
                "checkpoint_json", checkpoint == null ? null : checkpoint.payloadJson(),
                "run_started_time", null, "run_state", null, "run_claim_token", null,
                "last_outcome", state.lastOutcome(), "outcome_code", state.outcomeCode(),
                "outcome_message", state.outcomeMessage(), "suspend_reason", state.suspendReason(),
                "suspend_code", state.suspendCode(), "suspend_detail_json", state.suspendDetailJson()));
        updates.put("next_run_time", new Conditional(call(AND,
                call(NOT, call(IS_NULL, column("scheduled_tasks", "run_started_time"))),
                eq("scheduled_tasks", "enabled", 1)), value(0), column("scheduled_tasks", "next_run_time")));
        int updated = database.transaction(s -> s.update("scheduled_tasks", updates, legacyTask(taskId)));
        if (updated != 1) {
            throw new IllegalStateException("legacy schedule canonical update lost its row");
        }
    }

    private void writeCredential(long taskId, Credential credential, String legacySecret) {
        database.transaction(s -> {
            if (credential == null) return s.delete("scheduled_task_credentials",
                    eq("scheduled_task_credentials", "task_id", taskId));
            var row = values("task_id", taskId, "policy_owner_plugin_id", credential.policyOwnerPluginId(),
                    "policy_id", credential.policyId(), "account_key", credential.accountKey(), "secret", legacySecret,
                    "secret_reference", credential.secretReference(), "policy_state_json", credential.policyStateJson(),
                    "updated_time", credential.updatedTime());
            var updates = new LinkedHashMap<String, Expression>();
            row.keySet().stream().filter(key -> !key.equals("task_id")).forEach(key -> updates.put(key, new Incoming(key)));
            return s.insert("scheduled_task_credentials", row,
                    new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("task_id"), updates));
        });
    }

    private void writePending(long taskId, List<PendingWork> pending) {
        database.transaction(s -> {
            s.delete("scheduled_task_pending_work", eq("scheduled_task_pending_work", "task_id", taskId));
            for (PendingWork item : pending) {
                s.insert("scheduled_task_pending_work", values("task_id", taskId,
                        "work_type", item.work().key().workType(), "work_id", item.work().key().id(),
                        "payload_schema", item.work().payloadSchema(), "payload_version", item.work().payloadVersion(),
                        "payload_json", item.work().payloadJson(), "presentation_json", serialize(item.work().presentation()),
                        "relations_json", serialize(item.work().relations()), "reason_code", item.reasonCode(),
                        "reason_detail_json", item.reasonDetailJson(), "attempts", item.attempts(),
                        "first_seen_time", item.firstSeenTime(), "last_attempt_time", item.lastAttemptTime()),
                        DatabaseSession.Conflict.error());
            }
            return null;
        });
    }

    private void verifyCredential(long taskId, Credential expected, String expectedSecret) {
        List<CredentialRow> rows = rows("scheduled_task_credentials",
                eq("scheduled_task_credentials", "task_id", taskId), List.of(),
                "policy_owner_plugin_id", "policy_id", "account_key", "secret", "secret_reference", "policy_state_json",
                "updated_time").stream().map(row -> new CredentialRow(text(row, "policy_owner_plugin_id"),
                        text(row, "policy_id"), text(row, "account_key"), text(row, "secret"),
                        text(row, "secret_reference"), text(row, "policy_state_json"), number(row, "updated_time"))).toList();
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
        String table = "scheduled_task_pending_work";
        List<PendingStoredRow> actualRows = rows(table, eq(table, "task_id", taskId),
                List.of(ascending(table, "work_type"), ascending(table, "work_id")),
                "work_type", "work_id", "payload_schema", "payload_version", "payload_json", "presentation_json",
                "relations_json", "reason_code", "reason_detail_json", "attempts", "first_seen_time", "last_attempt_time")
                .stream().map(row -> new PendingStoredRow(text(row, "work_type"), text(row, "work_id"),
                        text(row, "payload_schema"), integer(row, "payload_version"), text(row, "payload_json"),
                        text(row, "presentation_json"), text(row, "relations_json"), text(row, "reason_code"),
                        text(row, "reason_detail_json"), integer(row, "attempts"), number(row, "first_seen_time"),
                        number(row, "last_attempt_time"))).toList();
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
        var updates = changes(values("suspend_reason", SUSPEND_MIGRATION_ERROR, "suspend_code", code,
                "suspend_detail_json", detailJson));
        updates.put("state_version", call(ADD, column("scheduled_tasks", "state_version"), value(1)));
        // COALESCE 明确把未知值视为不同，保持拒绝结果幂等。
        Expression same = call(AND, eq("scheduled_tasks", "suspend_reason", SUSPEND_MIGRATION_ERROR),
                eq("scheduled_tasks", "suspend_code", code), eq("scheduled_tasks", "suspend_detail_json", detailJson));
        database.transaction(s -> s.update("scheduled_tasks", updates,
                call(AND, legacyTask(taskId), call(NOT, call(COALESCE, same, call(EQUAL, value(0), value(1)))))));
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
