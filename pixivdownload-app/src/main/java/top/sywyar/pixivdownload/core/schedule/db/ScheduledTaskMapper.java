package top.sywyar.pixivdownload.core.schedule.db;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.sywyar.pixivdownload.core.schedule.ScheduleTaskDefinitionUpdate;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;

import java.util.List;

/** 核心计划任务 Store 的 MyBatis 实现细节；插件代码不得直接依赖本 mapper。 */
@Mapper
public interface ScheduledTaskMapper {

    /** 不含任何 credential secret 的任务投影。 */
    String SELECT_TASK = "SELECT t.id, t.name, t.enabled,"
            + " t.type AS sourceType, t.source_owner_plugin_id AS sourceOwnerPluginId,"
            + " t.definition_schema AS definitionSchema, t.definition_version AS definitionVersion,"
            + " t.params_json AS definitionJson, t.presentation_json AS presentationJson,"
            + " t.trigger_kind AS triggerKind, t.interval_minutes AS intervalMinutes,"
            + " t.cron_expr AS cronExpr, t.proxy_snapshot AS proxySnapshot,"
            + " t.next_run_time AS nextRunTime, t.last_run_time AS lastRunTime,"
            + " t.checkpoint_schema AS checkpointSchema, t.checkpoint_version AS checkpointVersion,"
            + " t.checkpoint_json AS checkpointJson, t.storage_version AS storageVersion,"
            + " t.run_state AS runState, t.run_claim_token AS runClaimToken,"
            + " t.last_outcome AS lastOutcome, t.outcome_code AS outcomeCode,"
            + " t.outcome_message AS outcomeMessage, t.suspend_reason AS suspendReason,"
            + " t.suspend_code AS suspendCode, t.suspend_detail_json AS suspendDetailJson,"
            + " t.state_version AS stateVersion,"
            + " c.policy_owner_plugin_id AS credentialPolicyOwnerPluginId,"
            + " c.policy_id AS credentialPolicyId, c.account_key AS credentialAccountKey,"
            + " c.policy_state_json AS credentialPolicyStateJson,"
            + " c.secret_reference AS credentialSecretReference,"
            + " t.created_time AS createdTime"
            + " FROM scheduled_tasks t"
            + " LEFT JOIN scheduled_task_credentials c ON c.task_id = t.id";

    @Insert("INSERT INTO scheduled_tasks(name, enabled, type, params_json,"
            + " source_owner_plugin_id, definition_schema, definition_version, presentation_json,"
            + " trigger_kind, interval_minutes, cron_expr, cookie_mode, proxy_snapshot,"
            + " next_run_time, last_run_time, checkpoint_schema, checkpoint_version, checkpoint_json,"
            + " storage_version, run_state, run_claim_token, last_outcome, outcome_code, outcome_message,"
            + " suspend_reason, suspend_code, suspend_detail_json, state_version, created_time)"
            + " VALUES(#{name}, #{enabled}, #{sourceType}, #{definitionJson},"
            + " #{sourceOwnerPluginId}, #{definitionSchema}, #{definitionVersion}, #{presentationJson},"
            + " #{triggerKind}, #{intervalMinutes}, #{cronExpr}, 'restricted', #{proxySnapshot},"
            + " #{nextRunTime}, #{lastRunTime}, #{checkpointSchema}, #{checkpointVersion}, #{checkpointJson},"
            + " #{storageVersion}, #{runState}, #{runClaimToken}, #{lastOutcome}, #{outcomeCode}, #{outcomeMessage},"
            + " #{suspendReason}, #{suspendCode}, #{suspendDetailJson}, #{stateVersion}, #{createdTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(ScheduledTaskInsertRow task);

    @Select(SELECT_TASK + " ORDER BY t.id DESC")
    List<ScheduledTask> findAll();

    @Select(SELECT_TASK + " WHERE t.id = #{id}")
    ScheduledTask findById(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM scheduled_tasks")
    int countAll();

    @Select(SELECT_TASK
            + " WHERE t.storage_version = 1 AND t.enabled = 1"
            + " AND t.suspend_reason IS NULL AND t.run_state IS NULL"
            + " AND t.next_run_time IS NOT NULL AND t.next_run_time <= #{now}"
            + " ORDER BY t.next_run_time, t.id")
    List<ScheduledTask> findDue(@Param("now") long now);

    @Select(SELECT_TASK
            + " WHERE c.policy_owner_plugin_id = #{policyOwnerPluginId}"
            + " AND c.policy_id = #{policyId} AND c.account_key = #{accountKey}"
            + " ORDER BY t.id DESC")
    List<ScheduledTask> findByCredentialAccount(
            @Param("policyOwnerPluginId") String policyOwnerPluginId,
            @Param("policyId") String policyId,
            @Param("accountKey") String accountKey);

    @Select(value = "UPDATE scheduled_tasks SET run_state = 'QUEUED',"
            + " run_claim_token = #{claimToken}, state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1 AND state_version = #{expectedStateVersion}"
            + " AND enabled = 1 AND suspend_reason IS NULL AND run_state IS NULL"
            + " AND next_run_time IS NOT NULL AND next_run_time <= #{now}"
            + " RETURNING run_claim_token AS claimToken, state_version AS stateVersion, run_state AS runState",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    ScheduleRunToken tryQueueDue(@Param("id") long id,
                                 @Param("expectedStateVersion") long expectedStateVersion,
                                 @Param("claimToken") String claimToken,
                                 @Param("now") long now);

    @Select(value = "UPDATE scheduled_tasks SET run_state = 'QUEUED',"
            + " run_claim_token = #{claimToken}, state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1 AND state_version = #{expectedStateVersion}"
            + " AND enabled = 1 AND suspend_reason IS NULL AND run_state IS NULL"
            + " RETURNING run_claim_token AS claimToken, state_version AS stateVersion, run_state AS runState",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    ScheduleRunToken tryQueueNow(@Param("id") long id,
                                 @Param("expectedStateVersion") long expectedStateVersion,
                                 @Param("claimToken") String claimToken);

    @Select(value = "UPDATE scheduled_tasks SET run_state = 'RUNNING', state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1"
            + " AND state_version = #{queuedToken.stateVersion}"
            + " AND run_state = 'QUEUED' AND run_claim_token = #{queuedToken.claimToken}"
            + " AND enabled = 1 AND suspend_reason IS NULL"
            + " RETURNING run_claim_token AS claimToken, state_version AS stateVersion, run_state AS runState",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    ScheduleRunToken startRun(@Param("id") long id,
                              @Param("queuedToken") ScheduleRunToken queuedToken);

    @Select(value = "UPDATE scheduled_tasks SET run_state = NULL, run_claim_token = NULL,"
            + " last_run_time = #{completion.finishedTime}, last_outcome = #{completion.outcome},"
            + " outcome_code = #{completion.outcomeCode}, outcome_message = #{completion.outcomeMessage},"
            + " next_run_time = #{completion.nextRunTime},"
            + " checkpoint_schema = CASE WHEN #{completion.checkpointSchema} IS NULL"
            + "                          THEN checkpoint_schema ELSE #{completion.checkpointSchema} END,"
            + " checkpoint_version = CASE WHEN #{completion.checkpointSchema} IS NULL"
            + "                           THEN checkpoint_version ELSE #{completion.checkpointVersion} END,"
            + " checkpoint_json = CASE WHEN #{completion.checkpointSchema} IS NULL"
            + "                        THEN checkpoint_json ELSE #{completion.checkpointJson} END,"
            + " state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1"
            + " AND state_version = #{runningToken.stateVersion}"
            + " AND run_state = 'RUNNING' AND run_claim_token = #{runningToken.claimToken}"
            + " AND suspend_reason IS NULL"
            + " RETURNING state_version",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long completeRun(@Param("id") long id,
                     @Param("runningToken") ScheduleRunToken runningToken,
                     @Param("completion") ScheduleRunCompletion completion);

    @Select(value = "UPDATE scheduled_tasks SET run_state = NULL, run_claim_token = NULL,"
            + " last_run_time = #{finishedTime},"
            + " last_outcome = CASE"
            + "   WHEN run_state = 'CANCEL_REQUESTED'"
            + "        AND suspend_reason IN ('MANUAL','QUIESCED') THEN 'CANCELLED'"
            + "   WHEN run_state = 'CANCEL_REQUESTED' AND suspend_reason IS NOT NULL THEN 'ERROR'"
            + "   ELSE #{outcome} END,"
            + " outcome_code = CASE"
            + "   WHEN run_state = 'CANCEL_REQUESTED' AND suspend_reason IS NOT NULL THEN suspend_code"
            + "   ELSE #{outcomeCode} END,"
            + " outcome_message = CASE"
            + "   WHEN run_state = 'CANCEL_REQUESTED' AND suspend_reason IS NOT NULL"
            + "     THEN suspend_detail_json"
            + "   ELSE #{outcomeMessage} END,"
            + " next_run_time = #{nextRunTime}, state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1"
            + " AND run_claim_token = #{activeToken.claimToken}"
            + " AND ((state_version = #{activeToken.stateVersion}"
            + "       AND run_state = #{activeToken.runState})"
            + "   OR (state_version = #{activeToken.stateVersion} + 1"
            + "       AND #{activeToken.runState} IN ('QUEUED','RUNNING')"
            + "       AND run_state = 'CANCEL_REQUESTED' AND suspend_reason IS NOT NULL))"
            + " RETURNING state_version",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long finishCancelled(@Param("id") long id,
                         @Param("activeToken") ScheduleRunToken activeToken,
                         @Param("outcome") ScheduleLastOutcome outcome,
                         @Param("finishedTime") long finishedTime,
                         @Param("outcomeCode") String outcomeCode,
                         @Param("outcomeMessage") String outcomeMessage,
                         @Param("nextRunTime") Long nextRunTime);

    @Select(value = "UPDATE scheduled_tasks SET run_state = NULL, run_claim_token = NULL,"
            + " next_run_time = COALESCE(#{nextRunTime}, next_run_time),"
            + " state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1"
            + " AND state_version = #{queuedToken.stateVersion}"
            + " AND run_state = 'QUEUED' AND run_claim_token = #{queuedToken.claimToken}"
            + " RETURNING state_version",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long releaseQueued(@Param("id") long id,
                       @Param("queuedToken") ScheduleRunToken queuedToken,
                       @Param("nextRunTime") Long nextRunTime);

    @Select(value = "UPDATE scheduled_tasks SET suspend_reason = #{reason}, suspend_code = #{code},"
            + " suspend_detail_json = #{detailJson},"
            + " run_state = CASE WHEN run_state IN ('QUEUED','RUNNING')"
            + "                  THEN 'CANCEL_REQUESTED' ELSE run_state END,"
            + " state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1"
            + " AND state_version = #{expectedStateVersion} AND suspend_reason IS NULL"
            + " RETURNING state_version",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long suspend(@Param("id") long id,
                 @Param("expectedStateVersion") long expectedStateVersion,
                 @Param("reason") ScheduleSuspendReason reason,
                 @Param("code") String code,
                 @Param("detailJson") String detailJson);

    @Select(value = "UPDATE scheduled_tasks SET suspend_reason = NULL, suspend_code = NULL,"
            + " suspend_detail_json = NULL, next_run_time = #{nextRunTime},"
            + " state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1 AND state_version = #{expectedStateVersion}"
            + " AND run_state IS NULL AND suspend_reason = #{expectedReason}"
            + " AND (suspend_code = #{expectedCode} OR (suspend_code IS NULL AND #{expectedCode} IS NULL))"
            + " RETURNING state_version",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long resume(@Param("id") long id,
                @Param("expectedStateVersion") long expectedStateVersion,
                @Param("expectedReason") ScheduleSuspendReason expectedReason,
                @Param("expectedCode") String expectedCode,
                @Param("nextRunTime") Long nextRunTime);

    @Update("UPDATE scheduled_tasks SET suspend_reason = #{reason}, suspend_code = #{code},"
            + " suspend_detail_json = #{detailJson},"
            + " run_state = CASE WHEN run_state IN ('QUEUED','RUNNING')"
            + "                  THEN 'CANCEL_REQUESTED' ELSE run_state END,"
            + " state_version = state_version + 1"
            + " WHERE storage_version = 1 AND suspend_reason IS NULL"
            + " AND id IN (SELECT task_id FROM scheduled_task_credentials"
            + "            WHERE policy_owner_plugin_id = #{policyOwnerPluginId}"
            + "              AND policy_id = #{policyId} AND account_key = #{accountKey})")
    int suspendByCredentialAccount(@Param("policyOwnerPluginId") String policyOwnerPluginId,
                                   @Param("policyId") String policyId,
                                   @Param("accountKey") String accountKey,
                                   @Param("reason") ScheduleSuspendReason reason,
                                   @Param("code") String code,
                                   @Param("detailJson") String detailJson);

    @Update("UPDATE scheduled_tasks SET suspend_reason = NULL, suspend_code = NULL,"
            + " suspend_detail_json = NULL, next_run_time = #{nextRunTime},"
            + " state_version = state_version + 1"
            + " WHERE storage_version = 1 AND run_state IS NULL"
            + " AND suspend_reason = #{expectedReason}"
            + " AND (suspend_code = #{expectedCode} OR (suspend_code IS NULL AND #{expectedCode} IS NULL))"
            + " AND id IN (SELECT task_id FROM scheduled_task_credentials"
            + "            WHERE policy_owner_plugin_id = #{policyOwnerPluginId}"
            + "              AND policy_id = #{policyId} AND account_key = #{accountKey})")
    int resumeByCredentialAccount(@Param("policyOwnerPluginId") String policyOwnerPluginId,
                                  @Param("policyId") String policyId,
                                  @Param("accountKey") String accountKey,
                                  @Param("expectedReason") ScheduleSuspendReason expectedReason,
                                  @Param("expectedCode") String expectedCode,
                                  @Param("nextRunTime") Long nextRunTime);

    @Select(value = "UPDATE scheduled_tasks SET name = #{update.name}, type = #{update.sourceType},"
            + " source_owner_plugin_id = #{update.sourceOwnerPluginId},"
            + " definition_schema = #{update.definitionSchema},"
            + " definition_version = #{update.definitionVersion}, params_json = #{update.definitionJson},"
            + " presentation_json = #{update.presentationJson}, trigger_kind = #{update.triggerKind},"
            + " interval_minutes = #{update.intervalMinutes}, cron_expr = #{update.cronExpr},"
            + " next_run_time = #{update.nextRunTime}, checkpoint_schema = NULL,"
            + " checkpoint_version = NULL, checkpoint_json = NULL,"
            + " suspend_reason = CASE WHEN suspend_reason IN"
            + " ('MIGRATION_ERROR', 'SOURCE_UNAVAILABLE', 'EXECUTOR_UNAVAILABLE')"
            + " THEN NULL ELSE suspend_reason END,"
            + " suspend_code = CASE WHEN suspend_reason IN"
            + " ('MIGRATION_ERROR', 'SOURCE_UNAVAILABLE', 'EXECUTOR_UNAVAILABLE')"
            + " THEN NULL ELSE suspend_code END,"
            + " suspend_detail_json = CASE WHEN suspend_reason IN"
            + " ('MIGRATION_ERROR', 'SOURCE_UNAVAILABLE', 'EXECUTOR_UNAVAILABLE')"
            + " THEN NULL ELSE suspend_detail_json END,"
            + " state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1 AND state_version = #{expectedStateVersion}"
            + " AND run_state IS NULL RETURNING state_version",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long updateDefinition(@Param("id") long id,
                          @Param("expectedStateVersion") long expectedStateVersion,
                          @Param("update") ScheduleTaskDefinitionUpdate update);

    @Select(value = "UPDATE scheduled_tasks SET enabled = #{enabled}, state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1 AND state_version = #{expectedStateVersion}"
            + " AND run_state IS NULL RETURNING state_version",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long updateEnabled(@Param("id") long id,
                       @Param("expectedStateVersion") long expectedStateVersion,
                       @Param("enabled") boolean enabled);

    @Select(value = "UPDATE scheduled_tasks SET proxy_snapshot = #{proxySnapshot},"
            + " state_version = state_version + 1"
            + " WHERE id = #{id} AND storage_version = 1 AND state_version = #{expectedStateVersion}"
            + " AND run_state IS NULL RETURNING state_version",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long updateProxy(@Param("id") long id,
                     @Param("expectedStateVersion") long expectedStateVersion,
                     @Param("proxySnapshot") String proxySnapshot);

    @Delete("DELETE FROM scheduled_tasks WHERE id = #{id}"
            + " AND state_version = #{expectedStateVersion} AND run_state IS NULL")
    int deleteTaskByVersion(@Param("id") long id,
                            @Param("expectedStateVersion") long expectedStateVersion);

    @Update("UPDATE scheduled_tasks SET run_state = NULL, run_claim_token = NULL,"
            + " last_run_time = #{now},"
            + " last_outcome = CASE"
            + "   WHEN suspend_reason IN ('MANUAL','QUIESCED') THEN 'CANCELLED'"
            + "   WHEN suspend_reason IS NOT NULL THEN 'ERROR'"
            + "   ELSE 'INTERRUPTED' END,"
            + " outcome_code = CASE WHEN suspend_reason IS NOT NULL"
            + "   THEN suspend_code ELSE 'host.restart' END,"
            + " outcome_message = CASE WHEN suspend_reason IS NOT NULL"
            + "   THEN suspend_detail_json ELSE NULL END,"
            + " next_run_time = CASE WHEN suspend_reason IS NULL THEN #{now} ELSE next_run_time END,"
            + " state_version = state_version + 1"
            + " WHERE storage_version = 1 AND run_state IS NOT NULL")
    int recoverInterruptedRuns(@Param("now") long now);

    @Select(value = "UPDATE scheduled_tasks SET state_version = state_version + 1"
            + " WHERE id = #{taskId} AND storage_version = 1"
            + " AND state_version = #{expectedStateVersion} AND run_state IS NULL"
            + " RETURNING state_version",
            affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long advanceIdleStateVersion(@Param("taskId") long taskId,
                                 @Param("expectedStateVersion") long expectedStateVersion);

    @Insert("INSERT INTO scheduled_task_credentials(task_id, policy_owner_plugin_id, policy_id,"
            + " account_key, policy_state_json, secret, secret_reference, updated_time)"
            + " VALUES(#{taskId}, #{policyOwnerPluginId}, #{policyId}, #{accountKey}, #{policyStateJson},"
            + " #{secret}, #{secretReference}, #{updatedTime})"
            + " ON CONFLICT(task_id) DO UPDATE SET"
            + " policy_owner_plugin_id = excluded.policy_owner_plugin_id,"
            + " policy_id = excluded.policy_id, account_key = excluded.account_key,"
            + " policy_state_json = excluded.policy_state_json,"
            + " secret = excluded.secret, secret_reference = excluded.secret_reference,"
            + " updated_time = excluded.updated_time")
    int upsertCredential(@Param("taskId") long taskId,
                         @Param("policyOwnerPluginId") String policyOwnerPluginId,
                         @Param("policyId") String policyId,
                         @Param("accountKey") String accountKey,
                         @Param("policyStateJson") String policyStateJson,
                         @Param("secret") String secret,
                         @Param("secretReference") String secretReference,
                         @Param("updatedTime") long updatedTime);

    @Delete("DELETE FROM scheduled_task_credentials WHERE task_id = #{taskId}"
            + " AND policy_owner_plugin_id = #{policyOwnerPluginId} AND policy_id = #{policyId}")
    int deleteCredential(@Param("taskId") long taskId,
                         @Param("policyOwnerPluginId") String policyOwnerPluginId,
                         @Param("policyId") String policyId);

    @Delete("DELETE FROM scheduled_task_credentials WHERE task_id = #{taskId}")
    int deleteCredentialByTask(@Param("taskId") long taskId);

    @Update("UPDATE scheduled_task_credentials SET policy_state_json = #{newPolicyStateJson},"
            + " updated_time = #{updatedTime} WHERE task_id = #{taskId}"
            + " AND policy_owner_plugin_id = #{policyOwnerPluginId} AND policy_id = #{policyId}"
            + " AND policy_state_json = #{expectedPolicyStateJson}")
    int updateCredentialPolicyState(@Param("taskId") long taskId,
                                    @Param("policyOwnerPluginId") String policyOwnerPluginId,
                                    @Param("policyId") String policyId,
                                    @Param("expectedPolicyStateJson") String expectedPolicyStateJson,
                                    @Param("newPolicyStateJson") String newPolicyStateJson,
                                    @Param("updatedTime") long updatedTime);

    @Select("SELECT policy_owner_plugin_id AS policyOwnerPluginId,"
            + " policy_id AS policyId, policy_state_json AS policyStateJson"
            + " FROM scheduled_task_credentials WHERE task_id = #{taskId}")
    ScheduledCredentialBinding findCredentialBinding(@Param("taskId") long taskId);

    @Select("SELECT secret FROM scheduled_task_credentials WHERE task_id = #{taskId}"
            + " AND policy_owner_plugin_id = #{policyOwnerPluginId} AND policy_id = #{policyId}")
    String findCredentialSecret(@Param("taskId") long taskId,
                                @Param("policyOwnerPluginId") String policyOwnerPluginId,
                                @Param("policyId") String policyId);

    @Insert("INSERT INTO scheduled_task_pending_work(task_id, work_type, work_id, payload_schema,"
            + " payload_version, payload_json, relations_json, presentation_json, reason_code,"
            + " reason_detail_json, attempts, first_seen_time, last_attempt_time)"
            + " VALUES(#{taskId}, #{workType}, #{workId}, #{payloadSchema}, #{payloadVersion},"
            + " #{payloadJson}, #{relationsJson}, #{presentationJson}, #{reasonCode},"
            + " #{reasonDetailJson}, #{attempts}, #{firstSeenTime}, #{lastAttemptTime})"
            + " ON CONFLICT(task_id, work_type, work_id) DO UPDATE SET"
            + " payload_schema = excluded.payload_schema, payload_version = excluded.payload_version,"
            + " payload_json = excluded.payload_json, relations_json = excluded.relations_json,"
            + " presentation_json = excluded.presentation_json, reason_code = excluded.reason_code,"
            + " reason_detail_json = excluded.reason_detail_json,"
            + " attempts = MAX(attempts, excluded.attempts),"
            + " last_attempt_time = excluded.last_attempt_time")
    int upsertPendingWork(ScheduledPendingWork pendingWork);

    @Select("SELECT task_id AS taskId, work_type AS workType, work_id AS workId,"
            + " payload_schema AS payloadSchema, payload_version AS payloadVersion,"
            + " payload_json AS payloadJson, relations_json AS relationsJson,"
            + " presentation_json AS presentationJson, reason_code AS reasonCode,"
            + " reason_detail_json AS reasonDetailJson, attempts,"
            + " first_seen_time AS firstSeenTime, last_attempt_time AS lastAttemptTime"
            + " FROM scheduled_task_pending_work WHERE task_id = #{taskId}"
            + " ORDER BY first_seen_time, work_type, work_id")
    List<ScheduledPendingWork> listPendingWork(@Param("taskId") long taskId);

    @Delete("DELETE FROM scheduled_task_pending_work"
            + " WHERE task_id = #{taskId} AND work_type = #{workType} AND work_id = #{workId}")
    int deletePendingWork(@Param("taskId") long taskId,
                          @Param("workType") String workType,
                          @Param("workId") String workId);

    @Delete("DELETE FROM scheduled_task_pending_work WHERE task_id = #{taskId}")
    int deleteAllPendingWork(@Param("taskId") long taskId);

    /** 旧表只作为迁移输入；聚合删除任务时仍需清理它，避免孤儿。 */
    @Delete("DELETE FROM scheduled_task_pending WHERE task_id = #{taskId}")
    int deleteLegacyPendingByTask(@Param("taskId") long taskId);

}
