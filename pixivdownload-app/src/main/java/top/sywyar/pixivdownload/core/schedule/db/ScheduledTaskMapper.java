package top.sywyar.pixivdownload.core.schedule.db;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.schedule.*;
import top.sywyar.pixivdownload.core.schedule.state.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 核心计划事实的中性存储实现；claim 与版本必须在同一次 CAS 中返回。 */
@Repository
public class ScheduledTaskMapper {
    private static final String TASK = "scheduled_tasks";
    private static final String CREDENTIAL = "scheduled_task_credentials";
    private static final String PENDING = "scheduled_task_pending_work";
    private final MainDatabase database;
    public ScheduledTaskMapper(MainDatabase database) { this.database = database; }

    private static Object stored(Object value) {
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Boolean b) return b ? 1L : 0L;
        if (value instanceof Integer n) return n.longValue();
        return value;
    }
    private static Expression literal(Object value) { return value(stored(value)); }
    private static Expression eq(String table, String name, Object value) {
        return call(EQUAL, column(table, name), literal(value));
    }
    private static Expression eq(String name, Object value) { return eq(TASK, name, value); }
    private static Expression nil(String name) { return call(IS_NULL, column(TASK, name)); }
    private static Expression notNil(String name) { return call(NOT, nil(name)); }
    private static Expression choices(String name, String... values) {
        var args = new ArrayList<Expression>(); args.add(column(TASK, name));
        for (String value : values) args.add(value(value));
        return new Call(IN, args);
    }
    private static Expression state(long id, long expected) {
        return call(AND, eq("id", id), eq("storage_version", ScheduledTask.CURRENT_STORAGE_VERSION), eq("state_version", expected));
    }
    private static Expression token(long id, ScheduleRunToken token, ScheduleRunState state) {
        return call(AND, state(id, token.stateVersion()), eq("run_claim_token", token.claimToken()), eq("run_state", state));
    }
    private static Integer integer(Object value) { return value == null ? null : Math.toIntExact((Long) value); }
    private static int number(Object value) { return value == null ? 0 : Math.toIntExact((Long) value); }
    private static <E extends Enum<E>> E enumeration(Class<E> type, Object value) {
        return value == null ? null : Enum.valueOf(type, (String) value);
    }
    private static Map<String, Expression> values(Object... pairs) {
        var result = new LinkedHashMap<String, Expression>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], literal(pairs[i + 1]));
        return result;
    }
    private static Map<String, Expression> fields(String table, String... names) {
        var result = new LinkedHashMap<String, Expression>();
        for (String name : names) result.put(name, column(table, name));
        return result;
    }
    private List<Map<String, Object>> query(Query query) { return database.read(s -> s.query(query)); }
    private static void advance(Map<String, Expression> changes) {
        changes.put("state_version", call(ADD, column(TASK, "state_version"), value(1)));
    }
    private List<Map<String, Object>> cas(Map<String, Expression> changes, Expression where, List<String> returning) {
        advance(changes);
        return database.transaction(s -> s.updateReturning(TASK, changes, where, returning));
    }
    private Long version(Map<String, Expression> changes, Expression where) {
        var rows = cas(changes, where, List.of("state_version"));
        return rows.isEmpty() ? null : (Long) rows.get(0).get("state_version");
    }
    private ScheduleRunToken claim(Map<String, Expression> changes, Expression where) {
        var rows = cas(changes, where, List.of("run_claim_token", "state_version", "run_state"));
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        return new ScheduleRunToken((String) row.get("run_claim_token"), (Long) row.get("state_version"),
                enumeration(ScheduleRunState.class, row.get("run_state")));
    }
    public void insert(ScheduledTaskInsertRow task) {
        var row = new LinkedHashMap<String, Object>();
        row.put("name", stored(task.getName()));
        row.put("enabled", stored(task.isEnabled()));
        row.put("type", stored(task.getSourceType()));
        row.put("source_owner_plugin_id", stored(task.getSourceOwnerPluginId()));
        row.put("definition_schema", stored(task.getDefinitionSchema()));
        row.put("definition_version", stored(task.getDefinitionVersion()));
        row.put("params_json", stored(task.getDefinitionJson()));
        row.put("presentation_json", stored(task.getPresentationJson()));
        row.put("trigger_kind", stored(task.getTriggerKind()));
        row.put("interval_minutes", stored(task.getIntervalMinutes()));
        row.put("cron_expr", stored(task.getCronExpr()));
        row.put("proxy_snapshot", stored(task.getProxySnapshot()));
        row.put("next_run_time", stored(task.getNextRunTime()));
        row.put("last_run_time", stored(task.getLastRunTime()));
        row.put("checkpoint_schema", stored(task.getCheckpointSchema()));
        row.put("checkpoint_version", stored(task.getCheckpointVersion()));
        row.put("checkpoint_json", stored(task.getCheckpointJson()));
        row.put("storage_version", stored(task.getStorageVersion()));
        row.put("run_state", stored(task.getRunState()));
        row.put("run_claim_token", stored(task.getRunClaimToken()));
        row.put("last_outcome", stored(task.getLastOutcome()));
        row.put("outcome_code", stored(task.getOutcomeCode()));
        row.put("outcome_message", stored(task.getOutcomeMessage()));
        row.put("suspend_reason", stored(task.getSuspendReason()));
        row.put("suspend_code", stored(task.getSuspendCode()));
        row.put("suspend_detail_json", stored(task.getSuspendDetailJson()));
        row.put("state_version", stored(task.getStateVersion()));
        row.put("created_time", stored(task.getCreatedTime()));
        row.put("cookie_mode", "restricted");
        task.setId(database.transaction(s -> s.insertGenerated(TASK, row, "id")));
    }

    private List<ScheduledTask> tasks(Expression where, List<Query.Order> order, int limit) {
        var projection = fields("t", "id", "name", "enabled", "type", "source_owner_plugin_id",
                "definition_schema", "definition_version", "params_json", "presentation_json", "trigger_kind",
                "interval_minutes", "cron_expr", "proxy_snapshot", "next_run_time", "last_run_time",
                "checkpoint_schema", "checkpoint_version", "checkpoint_json", "storage_version",
                "run_state", "run_claim_token", "last_outcome", "outcome_code", "outcome_message",
                "suspend_reason", "suspend_code", "suspend_detail_json", "state_version");
        projection.put("credential_policy_owner_plugin_id", column("c", "policy_owner_plugin_id"));
        projection.put("credential_policy_id", column("c", "policy_id"));
        projection.put("credential_account_key", column("c", "account_key"));
        projection.put("credential_policy_state_json", column("c", "policy_state_json"));
        projection.put("credential_secret_reference", column("c", "secret_reference"));
        projection.put("created_time", column("t", "created_time"));
        return query(new Query(new Query.Table(TASK, "t"),
                List.of(new Query.Join(Query.JoinKind.LEFT, new Query.Table(CREDENTIAL, "c"),
                        call(EQUAL, column("c", "task_id"), column("t", "id")))), projection, where,
                List.of(), null, order, false, limit, 0)).stream().map(row -> new ScheduledTask(
                (Long) row.get("id"),
                (String) row.get("name"),
                Long.valueOf(1).equals(row.get("enabled")),
                (String) row.get("type"),
                (String) row.get("source_owner_plugin_id"),
                (String) row.get("definition_schema"),
                integer(row.get("definition_version")),
                (String) row.get("params_json"),
                (String) row.get("presentation_json"),
                (String) row.get("trigger_kind"),
                integer(row.get("interval_minutes")),
                (String) row.get("cron_expr"),
                (String) row.get("proxy_snapshot"),
                (Long) row.get("next_run_time"),
                (Long) row.get("last_run_time"),
                (String) row.get("checkpoint_schema"),
                integer(row.get("checkpoint_version")),
                (String) row.get("checkpoint_json"),
                number(row.get("storage_version")),
                enumeration(ScheduleRunState.class, row.get("run_state")),
                (String) row.get("run_claim_token"),
                enumeration(ScheduleLastOutcome.class, row.get("last_outcome")),
                (String) row.get("outcome_code"),
                (String) row.get("outcome_message"),
                enumeration(ScheduleSuspendReason.class, row.get("suspend_reason")),
                (String) row.get("suspend_code"),
                (String) row.get("suspend_detail_json"),
                (Long) row.get("state_version"),
                (String) row.get("credential_policy_owner_plugin_id"),
                (String) row.get("credential_policy_id"),
                (String) row.get("credential_account_key"),
                (String) row.get("credential_policy_state_json"),
                (String) row.get("credential_secret_reference"),
                (Long) row.get("created_time"))).toList();
    }
    public List<ScheduledTask> findAll() {
        return tasks(null, List.of(new Query.Order(column("t", "id"), true, false)), Integer.MAX_VALUE);
    }
    public ScheduledTask findById(long id) {
        return tasks(eq("t", "id", id), List.of(), 1).stream().findFirst().orElse(null);
    }
    public int countAll() { return number(query(Query.count(TASK, null)).get(0).get("count")); }
    public List<ScheduledTask> findDue(long now) {
        return tasks(call(AND, eq("t", "storage_version", ScheduledTask.CURRENT_STORAGE_VERSION), eq("t", "enabled", 1),
                call(IS_NULL, column("t", "suspend_reason")), call(IS_NULL, column("t", "run_state")),
                call(LESS_EQUAL, column("t", "next_run_time"), value(now))),
                List.of(new Query.Order(column("t", "next_run_time"), false, true),
                        new Query.Order(column("t", "id"), false, true)), Integer.MAX_VALUE);
    }
    public List<ScheduledTask> findByCredentialAccount(String policyOwnerPluginId, String policyId, String accountKey) {
        return tasks(call(AND, eq("c", "policy_owner_plugin_id", policyOwnerPluginId),
                eq("c", "policy_id", policyId), eq("c", "account_key", accountKey)),
                List.of(new Query.Order(column("t", "id"), true, false)), Integer.MAX_VALUE);
    }
    private static Expression queueable(long id, long expected) {
        return call(AND, state(id, expected), eq("enabled", 1), nil("suspend_reason"), nil("run_state"));
    }
    public ScheduleRunToken tryQueueDue(long id, long expectedStateVersion, String claimToken, long now) {
        return claim(values("run_state", ScheduleRunState.QUEUED, "run_claim_token", claimToken),
                call(AND, queueable(id, expectedStateVersion), call(LESS_EQUAL, column(TASK, "next_run_time"), value(now))));
    }
    public ScheduleRunToken tryQueueNow(long id, long expectedStateVersion, String claimToken) {
        return claim(values("run_state", ScheduleRunState.QUEUED, "run_claim_token", claimToken), queueable(id, expectedStateVersion));
    }
    public ScheduleRunToken startRun(long id, ScheduleRunToken queuedToken) {
        return claim(values("run_state", ScheduleRunState.RUNNING),
                call(AND, token(id, queuedToken, ScheduleRunState.QUEUED), eq("enabled", 1), nil("suspend_reason")));
    }
    public Long completeRun(long id, ScheduleRunToken runningToken, ScheduleRunCompletion completion) {
        var changes = values("run_state", null, "run_claim_token", null, "last_run_time", completion.finishedTime(),
                "last_outcome", completion.outcome(), "outcome_code", completion.outcomeCode(), "outcome_message", completion.outcomeMessage(),
                "next_run_time", completion.nextRunTime());
        if (completion.checkpointSchema() != null) changes.putAll(values("checkpoint_schema", completion.checkpointSchema(),
                "checkpoint_version", completion.checkpointVersion(), "checkpoint_json", completion.checkpointJson()));
        return version(changes, call(AND, token(id, runningToken, ScheduleRunState.RUNNING), nil("suspend_reason")));
    }
    public Long finishCancelled(long id, ScheduleRunToken activeToken, ScheduleLastOutcome outcome, long finishedTime,
                                String outcomeCode, String outcomeMessage, Long nextRunTime) {
        var cancelled = eq("run_state", ScheduleRunState.CANCEL_REQUESTED);
        var suspended = call(AND, cancelled, notNil("suspend_reason"));
        var manual = call(AND, cancelled, choices("suspend_reason", "MANUAL", "QUIESCED"));
        var changes = values("run_state", null, "run_claim_token", null, "last_run_time", finishedTime, "next_run_time", nextRunTime);
        changes.put("last_outcome", new Conditional(manual, value("CANCELLED"),
                new Conditional(suspended, value("ERROR"), literal(outcome))));
        changes.put("outcome_code", new Conditional(suspended, column(TASK, "suspend_code"), value(outcomeCode)));
        changes.put("outcome_message", new Conditional(suspended, column(TASK, "suspend_detail_json"), value(outcomeMessage)));
        Expression exact = call(AND, eq("state_version", activeToken.stateVersion()), eq("run_state", activeToken.runState()));
        Expression accepted = exact;
        if (activeToken.runState() == ScheduleRunState.QUEUED || activeToken.runState() == ScheduleRunState.RUNNING)
            accepted = call(OR, exact, call(AND, eq("state_version", Math.addExact(activeToken.stateVersion(), 1)),
                    cancelled, notNil("suspend_reason")));
        return version(changes, call(AND, eq("id", id), eq("storage_version", ScheduledTask.CURRENT_STORAGE_VERSION),
                eq("run_claim_token", activeToken.claimToken()), accepted));
    }
    public Long releaseQueued(long id, ScheduleRunToken queuedToken, Long nextRunTime) {
        var changes = values("run_state", null, "run_claim_token", null);
        if (nextRunTime != null) changes.put("next_run_time", value(nextRunTime));
        return version(changes, token(id, queuedToken, ScheduleRunState.QUEUED));
    }
    public Long suspend(long id, long expectedStateVersion, ScheduleSuspendReason reason, String code, String detailJson) {
        var changes = values("suspend_reason", reason, "suspend_code", code, "suspend_detail_json", detailJson);
        changes.put("run_state", new Conditional(choices("run_state", "QUEUED", "RUNNING"),
                value("CANCEL_REQUESTED"), column(TASK, "run_state")));
        return version(changes, call(AND, state(id, expectedStateVersion), nil("suspend_reason")));
    }
    public Long resume(long id, long expectedStateVersion, ScheduleSuspendReason expectedReason, String expectedCode, Long nextRunTime) {
        return version(values("suspend_reason", null, "suspend_code", null, "suspend_detail_json", null, "next_run_time", nextRunTime),
                call(AND, state(id, expectedStateVersion), nil("run_state"), eq("suspend_reason", expectedReason),
                        expectedCode == null ? nil("suspend_code") : eq("suspend_code", expectedCode)));
    }
    public Long updateDefinition(long id, long expectedStateVersion, ScheduleTaskDefinitionUpdate update) {
        var changes = values("name", update.name(), "type", update.sourceType(), "source_owner_plugin_id", update.sourceOwnerPluginId(),
                "definition_schema", update.definitionSchema(), "definition_version", update.definitionVersion(),
                "params_json", update.definitionJson(), "presentation_json", update.presentationJson(),
                "trigger_kind", update.triggerKind(), "interval_minutes", update.intervalMinutes(), "cron_expr", update.cronExpr(),
                "next_run_time", update.nextRunTime(), "checkpoint_schema", null, "checkpoint_version", null, "checkpoint_json", null);
        var recoverable = choices("suspend_reason", "MIGRATION_ERROR", "SOURCE_UNAVAILABLE", "EXECUTOR_UNAVAILABLE");
        for (String name : List.of("suspend_reason", "suspend_code", "suspend_detail_json"))
            changes.put(name, new Conditional(recoverable, value(null), column(TASK, name)));
        return version(changes, call(AND, state(id, expectedStateVersion), nil("run_state")));
    }
    public Long updateEnabled(long id, long expectedStateVersion, boolean enabled) {
        return version(values("enabled", enabled), call(AND, state(id, expectedStateVersion), nil("run_state")));
    }
    public Long updateProxy(long id, long expectedStateVersion, String proxySnapshot) {
        return version(values("proxy_snapshot", proxySnapshot), call(AND, state(id, expectedStateVersion), nil("run_state")));
    }
    public int deleteTaskByVersion(long id, long expectedStateVersion) {
        return database.transaction(s -> s.delete(TASK, call(AND, eq("id", id), eq("state_version", expectedStateVersion), nil("run_state"))));
    }
    public int recoverInterruptedRuns(long now) {
        var changes = values("run_state", null, "run_claim_token", null, "last_run_time", now);
        changes.put("last_outcome", new Conditional(choices("suspend_reason", "MANUAL", "QUIESCED"), value("CANCELLED"),
                new Conditional(notNil("suspend_reason"), value("ERROR"), value("INTERRUPTED"))));
        changes.put("outcome_code", new Conditional(notNil("suspend_reason"), column(TASK, "suspend_code"), value("host.restart")));
        changes.put("outcome_message", new Conditional(notNil("suspend_reason"), column(TASK, "suspend_detail_json"), value(null)));
        changes.put("next_run_time", new Conditional(nil("suspend_reason"), value(now), column(TASK, "next_run_time")));
        advance(changes);
        return database.transaction(s -> s.update(TASK, changes,
                call(AND, eq("storage_version", ScheduledTask.CURRENT_STORAGE_VERSION), notNil("run_state"))));
    }
    public Long advanceIdleStateVersion(long taskId, long expectedStateVersion) {
        return version(new LinkedHashMap<>(), call(AND, state(taskId, expectedStateVersion), nil("run_state")));
    }
    private static Expression credential(long taskId, String owner, String policy) {
        return call(AND, eq(CREDENTIAL, "task_id", taskId), eq(CREDENTIAL, "policy_owner_plugin_id", owner), eq(CREDENTIAL, "policy_id", policy));
    }
    public int upsertCredential(long taskId, String policyOwnerPluginId, String policyId, String accountKey,
                                String policyStateJson, String secret, String secretReference, long updatedTime) {
        var row = new LinkedHashMap<String, Object>();
        row.put("task_id", taskId); row.put("policy_owner_plugin_id", policyOwnerPluginId); row.put("policy_id", policyId);
        row.put("account_key", accountKey); row.put("policy_state_json", policyStateJson); row.put("secret", secret);
        row.put("secret_reference", secretReference); row.put("updated_time", updatedTime);
        var changes = new LinkedHashMap<String, Expression>();
        row.keySet().stream().filter(name -> !name.equals("task_id")).forEach(name -> changes.put(name, new Incoming(name)));
        return database.transaction(s -> s.insert(CREDENTIAL, row,
                new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("task_id"), changes)));
    }
    public int deleteCredential(long taskId, String policyOwnerPluginId, String policyId) {
        return database.transaction(s -> s.delete(CREDENTIAL, credential(taskId, policyOwnerPluginId, policyId)));
    }
    public int deleteCredentialByTask(long taskId) {
        return database.transaction(s -> s.delete(CREDENTIAL, eq(CREDENTIAL, "task_id", taskId)));
    }
    public int updateCredentialPolicyState(long taskId, String policyOwnerPluginId, String policyId,
                                           String expectedPolicyStateJson, String newPolicyStateJson, long updatedTime) {
        return database.transaction(s -> s.update(CREDENTIAL, values("policy_state_json", newPolicyStateJson, "updated_time", updatedTime),
                call(AND, credential(taskId, policyOwnerPluginId, policyId), eq(CREDENTIAL, "policy_state_json", expectedPolicyStateJson))));
    }
    public ScheduledCredentialBinding findCredentialBinding(long taskId) {
        var rows = query(new Query(CREDENTIAL, fields(CREDENTIAL, "policy_owner_plugin_id", "policy_id", "policy_state_json"),
                eq(CREDENTIAL, "task_id", taskId), List.of(), List.of(), 1, 0));
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        return new ScheduledCredentialBinding((String) row.get("policy_owner_plugin_id"),
                (String) row.get("policy_id"), (String) row.get("policy_state_json"));
    }
    public String findCredentialSecret(long taskId, String policyOwnerPluginId, String policyId) {
        var rows = query(new Query(CREDENTIAL, fields(CREDENTIAL, "secret"), credential(taskId, policyOwnerPluginId, policyId),
                List.of(), List.of(), 1, 0));
        return rows.isEmpty() ? null : (String) rows.get(0).get("secret");
    }
    private static Expression pending(long taskId, String workType, String workId) {
        return call(AND, eq(PENDING, "task_id", taskId), eq(PENDING, "work_type", workType), eq(PENDING, "work_id", workId));
    }
    public int resolvePendingWork(long taskId, String workType, String workId, String detail) {
        return database.transaction(s -> s.update(PENDING, values("reason_detail_json", detail), pending(taskId, workType, workId)));
    }
    public int upsertPendingWork(ScheduledPendingWork pendingWork) {
        var row = new LinkedHashMap<String, Object>();
        row.put("task_id", stored(pendingWork.taskId()));
        row.put("work_type", stored(pendingWork.workType()));
        row.put("work_id", stored(pendingWork.workId()));
        row.put("payload_schema", stored(pendingWork.payloadSchema()));
        row.put("payload_version", stored(pendingWork.payloadVersion()));
        row.put("payload_json", stored(pendingWork.payloadJson()));
        row.put("relations_json", stored(pendingWork.relationsJson()));
        row.put("presentation_json", stored(pendingWork.presentationJson()));
        row.put("reason_code", stored(pendingWork.reasonCode()));
        row.put("reason_detail_json", stored(pendingWork.reasonDetailJson()));
        row.put("attempts", stored(pendingWork.attempts()));
        row.put("first_seen_time", stored(pendingWork.firstSeenTime()));
        row.put("last_attempt_time", stored(pendingWork.lastAttemptTime()));
        var changes = new LinkedHashMap<String, Expression>();
        row.keySet().stream().filter(name -> !List.of("task_id", "work_type", "work_id", "first_seen_time").contains(name))
                .forEach(name -> changes.put(name, new Incoming(name)));
        changes.put("attempts", new Conditional(call(GREATER_EQUAL, column(PENDING, "attempts"), new Incoming("attempts")),
                column(PENDING, "attempts"), new Incoming("attempts")));
        return database.transaction(s -> s.insert(PENDING, row,
                new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("task_id", "work_type", "work_id"), changes)));
    }
    public List<ScheduledPendingWork> listPendingWork(long taskId) {
        return query(new Query(PENDING, fields(PENDING, "task_id", "work_type", "work_id", "payload_schema", "payload_version", "payload_json", "relations_json", "presentation_json", "reason_code", "reason_detail_json", "attempts", "first_seen_time", "last_attempt_time"), eq(PENDING, "task_id", taskId), List.of(),
                List.of(new Query.Order(column(PENDING, "first_seen_time"), false, true),
                        new Query.Order(column(PENDING, "work_type"), false, true), new Query.Order(column(PENDING, "work_id"), false, true)),
                Integer.MAX_VALUE, 0)).stream().map(row -> new ScheduledPendingWork((Long) row.get("task_id"),
                (String) row.get("work_type"),
                (String) row.get("work_id"),
                (String) row.get("payload_schema"),
                number(row.get("payload_version")),
                (String) row.get("payload_json"),
                (String) row.get("relations_json"),
                (String) row.get("presentation_json"),
                (String) row.get("reason_code"),
                (String) row.get("reason_detail_json"),
                number(row.get("attempts")),
                (Long) row.get("first_seen_time"),
                (Long) row.get("last_attempt_time"))).toList();
    }
    public int deletePendingWork(long taskId, String workType, String workId) {
        return database.transaction(s -> s.delete(PENDING, pending(taskId, workType, workId)));
    }
    public int deleteAllPendingWork(long taskId) {
        return database.transaction(s -> s.delete(PENDING, eq(PENDING, "task_id", taskId)));
    }
    /** 旧表只作为迁移输入，聚合删除仍须清理。 */
    public int deleteLegacyPendingByTask(long taskId) {
        return database.transaction(s -> s.delete("scheduled_task_pending", eq("scheduled_task_pending", "task_id", taskId)));
    }
}
