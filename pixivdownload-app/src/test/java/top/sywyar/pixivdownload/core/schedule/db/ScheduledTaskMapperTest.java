package top.sywyar.pixivdownload.core.schedule.db;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.schedule.ScheduleTaskDefinitionUpdate;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScheduledTaskMapper 中性持久化")
class ScheduledTaskMapperTest {

    private SingleConnectionDataSource dataSource;
    private SqlSession session;
    private ScheduledTaskMapper mapper;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ScheduledTaskMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        mapper = session.getMapper(ScheduledTaskMapper.class);
        jdbc = new JdbcTemplate(dataSource);

        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        new DatabaseInitializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {}).initialize();
    }

    @AfterEach
    void tearDown() {
        session.close();
        dataSource.destroy();
    }

    @Test
    @DisplayName("新任务显式写 storageVersion=1，并按中性来源和定义字段往返")
    void insertsCanonicalTaskProjection() {
        ScheduledTaskInsertRow row = sample("中性任务", 1_000L);
        mapper.insert(row);

        assertThat(row.getId()).isPositive();
        ScheduledTask read = mapper.findById(row.getId());
        assertThat(read.sourceType()).isEqualTo("fixture-source");
        assertThat(read.sourceOwnerPluginId()).isEqualTo("fixture-plugin");
        assertThat(read.definitionSchema()).isEqualTo("fixture.definition");
        assertThat(read.definitionVersion()).isEqualTo(1);
        assertThat(read.definitionJson()).isEqualTo("{\"query\":{\"mode\":\"fixture\"}}");
        assertThat(read.storageVersion()).isEqualTo(ScheduledTask.CURRENT_STORAGE_VERSION);
        assertThat(read.lastOutcome()).isEqualTo(ScheduleLastOutcome.NEVER);
        assertThat(read.stateVersion()).isZero();
    }

    @Test
    @DisplayName("credential join 只投影非敏感元数据，secret 仅专用标量可读")
    void credentialSecretUsesDedicatedScalar() {
        ScheduledTaskInsertRow row = sample("凭证任务", 1_000L);
        mapper.insert(row);
        mapper.upsertCredential(row.getId(), "fixture-plugin", "fixture-credential", "account-1",
                "{\"ack\":12}", "credential-secret", "vault:fixture", 2_000L);

        ScheduledTask read = mapper.findById(row.getId());
        assertThat(read.credentialPolicyOwnerPluginId()).isEqualTo("fixture-plugin");
        assertThat(read.credentialPolicyId()).isEqualTo("fixture-credential");
        assertThat(read.credentialAccountKey()).isEqualTo("account-1");
        assertThat(read.credentialPolicyStateJson()).isEqualTo("{\"ack\":12}");
        assertThat(read.credentialSecretReference()).isEqualTo("vault:fixture");
        assertThat(read.toString()).doesNotContain("credential-secret");
        assertThat(mapper.findCredentialSecret(row.getId(), "fixture-plugin", "fixture-credential"))
                .isEqualTo("credential-secret");
        assertThat(mapper.findCredentialSecret(row.getId(), "other", "fixture-credential")).isNull();
    }

    @Test
    @DisplayName("due 认领和开始运行各自原子返回 claimToken 与新 stateVersion")
    void claimsDueTaskAndStartsWithReturnedToken() {
        ScheduledTaskInsertRow row = sample("到期任务", 500L);
        mapper.insert(row);

        ScheduleRunToken queued = mapper.tryQueueDue(row.getId(), 0L, "claim-a", 1_000L);
        assertThat(queued).isEqualTo(new ScheduleRunToken("claim-a", 1L, ScheduleRunState.QUEUED));
        assertThat(mapper.tryQueueDue(row.getId(), 0L, "claim-b", 1_000L)).isNull();

        ScheduleRunToken running = mapper.startRun(row.getId(), queued);
        assertThat(running).isEqualTo(new ScheduleRunToken("claim-a", 2L, ScheduleRunState.RUNNING));
        assertThat(mapper.startRun(row.getId(), queued)).isNull();

        ScheduleRunCompletion completion = new ScheduleRunCompletion(
                2_000L, ScheduleLastOutcome.OK, "run.ok", null, 9_000L,
                "fixture.checkpoint", 2, "{\"cursor\":\"001\"}");
        assertThat(mapper.completeRun(row.getId(), running, completion)).isEqualTo(3L);

        ScheduledTask completed = mapper.findById(row.getId());
        assertThat(completed.runState()).isNull();
        assertThat(completed.runClaimToken()).isNull();
        assertThat(completed.lastOutcome()).isEqualTo(ScheduleLastOutcome.OK);
        assertThat(completed.checkpointJson()).isEqualTo("{\"cursor\":\"001\"}");
        assertThat(completed.stateVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("失败完成未提供 checkpoint 时保留上次安全断点")
    void failedCompletionPreservesPreviousCheckpoint() {
        ScheduledTaskInsertRow row = sample("失败任务", 500L);
        row.setCheckpointSchema("fixture.checkpoint");
        row.setCheckpointVersion(1);
        row.setCheckpointJson("{\"cursor\":\"safe\"}");
        mapper.insert(row);
        ScheduleRunToken queued = mapper.tryQueueNow(row.getId(), 0L, "claim-error");
        ScheduleRunToken running = mapper.startRun(row.getId(), queued);

        ScheduleRunCompletion failed = new ScheduleRunCompletion(
                2_000L, ScheduleLastOutcome.ERROR, "source.failed", "failure", 9_000L,
                null, null, null);
        assertThat(mapper.completeRun(row.getId(), running, failed)).isEqualTo(3L);

        ScheduledTask completed = mapper.findById(row.getId());
        assertThat(completed.lastOutcome()).isEqualTo(ScheduleLastOutcome.ERROR);
        assertThat(completed.checkpointSchema()).isEqualTo("fixture.checkpoint");
        assertThat(completed.checkpointVersion()).isEqualTo(1);
        assertThat(completed.checkpointJson()).isEqualTo("{\"cursor\":\"safe\"}");
    }

    @Test
    @DisplayName("管理员挂起令旧 normal complete 失败，但原 claim 可完成取消且保留挂起")
    void suspendedRunRejectsNormalCompletionButAcceptsSameClaimCancellation() {
        ScheduledTaskInsertRow row = sample("竞态任务", 500L);
        mapper.insert(row);
        ScheduleRunToken queued = mapper.tryQueueNow(row.getId(), 0L, "claim-race");
        ScheduleRunToken running = mapper.startRun(row.getId(), queued);

        assertThat(mapper.suspend(row.getId(), running.stateVersion(), ScheduleSuspendReason.MANUAL,
                "admin.pause", "{\"by\":\"admin\"}")).isEqualTo(3L);
        ScheduledTask cancelling = mapper.findById(row.getId());
        assertThat(cancelling.runState()).isEqualTo(ScheduleRunState.CANCEL_REQUESTED);
        assertThat(cancelling.runClaimToken()).isEqualTo("claim-race");

        ScheduleRunCompletion staleCompletion = new ScheduleRunCompletion(
                2_000L, ScheduleLastOutcome.OK, "run.ok", null, 9_000L,
                "fixture.checkpoint", 1, "{}");
        assertThat(mapper.completeRun(row.getId(), running, staleCompletion)).isNull();

        assertThat(mapper.finishCancelled(row.getId(), running, ScheduleLastOutcome.ERROR,
                2_100L, "stale.executor", "stale detail", 9_000L)).isEqualTo(4L);
        ScheduledTask finished = mapper.findById(row.getId());
        assertThat(finished.runState()).isNull();
        assertThat(finished.runClaimToken()).isNull();
        assertThat(finished.lastOutcome()).isEqualTo(ScheduleLastOutcome.CANCELLED);
        assertThat(finished.suspendReason()).isEqualTo(ScheduleSuspendReason.MANUAL);
        assertThat(finished.suspendCode()).isEqualTo("admin.pause");
        assertThat(finished.outcomeCode()).isEqualTo("admin.pause");
        assertThat(finished.outcomeMessage()).isEqualTo("{\"by\":\"admin\"}");
        assertThat(finished.checkpointJson()).isNull();
    }

    @Test
    @DisplayName("异步提交失败只可用同一 QUEUED token 释放认领")
    void releasesQueuedClaimAfterSubmissionFailure() {
        ScheduledTaskInsertRow row = sample("提交失败", 500L);
        mapper.insert(row);
        ScheduleRunToken queued = mapper.tryQueueNow(row.getId(), 0L, "claim-submit");

        assertThat(mapper.releaseQueued(row.getId(),
                new ScheduleRunToken("wrong", queued.stateVersion(), ScheduleRunState.QUEUED), 700L)).isNull();
        assertThat(mapper.releaseQueued(row.getId(), queued, 700L)).isEqualTo(2L);

        ScheduledTask released = mapper.findById(row.getId());
        assertThat(released.runState()).isNull();
        assertThat(released.runClaimToken()).isNull();
        assertThat(released.nextRunTime()).isEqualTo(700L);
        assertThat(mapper.tryQueueDue(row.getId(), released.stateVersion(), "claim-retry", 700L))
                .extracting(ScheduleRunToken::claimToken).isEqualTo("claim-retry");
    }

    @Test
    @DisplayName("resume 必须精确匹配 stateVersion、reason 和 code")
    void resumesOnlyExactSuspension() {
        ScheduledTaskInsertRow row = sample("精确恢复", 500L);
        mapper.insert(row);
        Long suspendedVersion = mapper.suspend(row.getId(), 0L, ScheduleSuspendReason.POLICY,
                "risk.limit", "{\"event\":1}");

        assertThat(mapper.resume(row.getId(), suspendedVersion, ScheduleSuspendReason.POLICY,
                "other", 1_000L)).isNull();
        assertThat(mapper.resume(row.getId(), 0L, ScheduleSuspendReason.POLICY,
                "risk.limit", 1_000L)).isNull();
        assertThat(mapper.resume(row.getId(), suspendedVersion, ScheduleSuspendReason.POLICY,
                "risk.limit", 1_000L)).isEqualTo(2L);
        assertThat(mapper.findById(row.getId()).suspendReason()).isNull();
    }

    @Test
    @DisplayName("definition 编辑只清可由有效定义修复的挂起原因")
    void definitionEditClearsOnlyDefinitionRecoverableSuspensions() {
        List<ScheduleSuspendReason> recoverable = List.of(
                ScheduleSuspendReason.MIGRATION_ERROR,
                ScheduleSuspendReason.SOURCE_UNAVAILABLE,
                ScheduleSuspendReason.EXECUTOR_UNAVAILABLE);
        ScheduleTaskDefinitionUpdate update = new ScheduleTaskDefinitionUpdate(
                "修复后", "fixture-source", "fixture-plugin", "fixture.definition", 2,
                "{\"query\":{\"mode\":\"changed\"}}", "{}",
                ScheduledTask.TRIGGER_INTERVAL, 30, null, 2_000L);

        for (ScheduleSuspendReason reason : ScheduleSuspendReason.values()) {
            ScheduledTaskInsertRow row = sample("挂起-" + reason, 1_000L);
            row.setSuspendReason(reason);
            row.setSuspendCode("fixture.suspend");
            row.setSuspendDetailJson("{\"reason\":\"fixture\"}");
            mapper.insert(row);

            assertThat(mapper.updateDefinition(row.getId(), 0L, update))
                    .as("%s 的 definition CAS", reason)
                    .isEqualTo(1L);
            ScheduledTask changed = mapper.findById(row.getId());
            if (recoverable.contains(reason)) {
                assertThat(changed.suspendReason()).as("%s reason", reason).isNull();
                assertThat(changed.suspendCode()).as("%s code", reason).isNull();
                assertThat(changed.suspendDetailJson()).as("%s detail", reason).isNull();
            } else {
                assertThat(changed.suspendReason()).as("%s reason", reason).isEqualTo(reason);
                assertThat(changed.suspendCode()).as("%s code", reason).isEqualTo("fixture.suspend");
                assertThat(changed.suspendDetailJson()).as("%s detail", reason)
                        .isEqualTo("{\"reason\":\"fixture\"}");
            }
        }
    }

    @Test
    @DisplayName("findDue 只返回 canonical、启用、未挂起且无认领的到期任务")
    void findDueUsesOrthogonalStateGate() {
        ScheduledTaskInsertRow due = sample("可运行", 500L);
        mapper.insert(due);
        ScheduledTaskInsertRow disabled = sample("停用", 500L);
        disabled.setEnabled(false);
        mapper.insert(disabled);
        ScheduledTaskInsertRow future = sample("未来", 5_000L);
        mapper.insert(future);
        ScheduledTaskInsertRow suspended = sample("挂起", 500L);
        suspended.setSuspendReason(ScheduleSuspendReason.CREDENTIAL);
        mapper.insert(suspended);
        ScheduledTaskInsertRow legacy = sample("待迁移", 500L);
        legacy.setStorageVersion(ScheduledTask.LEGACY_STORAGE_VERSION);
        mapper.insert(legacy);
        ScheduleRunToken queued = mapper.tryQueueNow(due.getId(), 0L, "claim-due");

        assertThat(mapper.findDue(1_000L)).isEmpty();
        mapper.releaseQueued(due.getId(), queued, 500L);
        assertThat(mapper.findDue(1_000L)).extracting(ScheduledTask::name).containsExactly("可运行");
    }

    @Test
    @DisplayName("启动恢复重排普通中断并保留管理员挂起终态")
    void recoversInterruptedClaimsWithoutOverwritingSuspension() {
        ScheduledTaskInsertRow row = sample("崩溃任务", 9_000L);
        mapper.insert(row);
        ScheduleRunToken queued = mapper.tryQueueNow(row.getId(), 0L, "claim-crash");
        mapper.startRun(row.getId(), queued);
        ScheduledTaskInsertRow pausedRow = sample("暂停中崩溃", 9_000L);
        mapper.insert(pausedRow);
        ScheduleRunToken paused = mapper.tryQueueNow(pausedRow.getId(), 0L, "claim-paused");
        mapper.suspend(pausedRow.getId(), paused.stateVersion(), ScheduleSuspendReason.MANUAL,
                "ADMIN_PAUSE", "{\"by\":\"admin\"}");

        assertThat(mapper.recoverInterruptedRuns(1_000L)).isEqualTo(2);
        ScheduledTask recovered = mapper.findById(row.getId());
        assertThat(recovered.runState()).isNull();
        assertThat(recovered.runClaimToken()).isNull();
        assertThat(recovered.lastOutcome()).isEqualTo(ScheduleLastOutcome.INTERRUPTED);
        assertThat(recovered.lastRunTime()).isEqualTo(1_000L);
        assertThat(recovered.nextRunTime()).isEqualTo(1_000L);
        ScheduledTask recoveredPause = mapper.findById(pausedRow.getId());
        assertThat(recoveredPause.runState()).isNull();
        assertThat(recoveredPause.lastOutcome()).isEqualTo(ScheduleLastOutcome.CANCELLED);
        assertThat(recoveredPause.outcomeCode()).isEqualTo("ADMIN_PAUSE");
        assertThat(recoveredPause.outcomeMessage()).isEqualTo("{\"by\":\"admin\"}");
        assertThat(recoveredPause.lastRunTime()).isEqualTo(1_000L);
        assertThat(recoveredPause.nextRunTime()).isEqualTo(9_000L);
        assertThat(mapper.findDue(1_000L)).extracting(ScheduledTask::id).containsExactly(row.getId());
    }

    @Test
    @DisplayName("字符串作品身份、relations 和 payload 在 pending 中无损往返")
    void pendingWorkPreservesStringIdentityAndEnvelope() {
        List<String> ids = List.of("001", "1", "550e8400-e29b-41d4-a716-446655440000",
                "92233720368547758070", "id-with_dash");
        long now = 3_000L;
        for (String id : ids) {
            mapper.upsertPendingWork(pending(1L, "fixture.work", id, now));
        }
        mapper.upsertPendingWork(pending(1L, "other.work", "1", now));

        assertThat(mapper.listPendingWork(1L)).hasSize(6);
        ScheduledPendingWork leadingZero = pendingByKey(
                mapper.listPendingWork(1L), "fixture.work", "001");
        assertThat(leadingZero.workId()).isEqualTo("001");
        assertThat(leadingZero.payloadJson()).isEqualTo("{\"id\":\"001\"}");
        assertThat(leadingZero.relationsJson()).isEqualTo("[{\"type\":\"author\",\"id\":\"a-1\"}]");

        ScheduledPendingWork refreshed = new ScheduledPendingWork(
                1L, "fixture.work", "001", "fixture.payload", 2,
                "{\"id\":\"001\",\"v\":2}", "[]", "{\"title\":\"new\"}",
                "retry", "{}", 1, 9_999L, 5_000L);
        mapper.upsertPendingWork(refreshed);
        ScheduledPendingWork afterConflict = pendingByKey(
                mapper.listPendingWork(1L), "fixture.work", "001");
        assertThat(afterConflict.attempts()).isEqualTo(1);
        assertThat(afterConflict.firstSeenTime()).isEqualTo(now);
        assertThat(afterConflict.payloadVersion()).isEqualTo(2);
        assertThat(afterConflict.relationsJson()).isEqualTo("[]");

        mapper.upsertPendingWork(new ScheduledPendingWork(
                1L, "fixture.work", "001", "fixture.payload", 2,
                "{\"id\":\"001\",\"v\":3}", "[]", "{\"title\":\"newer\"}",
                "retry-again", "{}", 3, 9_999L, 6_000L));
        ScheduledPendingWork afterHigherAttempt = pendingByKey(
                mapper.listPendingWork(1L), "fixture.work", "001");
        assertThat(afterHigherAttempt.attempts()).isEqualTo(3);
        assertThat(afterHigherAttempt.firstSeenTime()).isEqualTo(now);

        assertThat(mapper.deletePendingWork(1L, "fixture.work", "1")).isEqualTo(1);
        assertThat(pendingByKey(mapper.listPendingWork(1L), "other.work", "1")).isNotNull();
    }

    private ScheduledTaskInsertRow sample(String name, Long nextRunTime) {
        ScheduledTaskInsertRow row = new ScheduledTaskInsertRow();
        row.setName(name);
        row.setEnabled(true);
        row.setSourceType("fixture-source");
        row.setSourceOwnerPluginId("fixture-plugin");
        row.setDefinitionSchema("fixture.definition");
        row.setDefinitionVersion(1);
        row.setDefinitionJson("{\"query\":{\"mode\":\"fixture\"}}");
        row.setPresentationJson("{\"title\":\"fixture\"}");
        row.setTriggerKind(ScheduledTask.TRIGGER_INTERVAL);
        row.setIntervalMinutes(60);
        row.setNextRunTime(nextRunTime);
        row.setCreatedTime(1_700_000_000_000L);
        return row;
    }

    private ScheduledPendingWork pending(long taskId, String workType, String workId, long now) {
        return new ScheduledPendingWork(
                taskId, workType, workId, "fixture.payload", 1,
                "{\"id\":\"" + workId + "\"}",
                "[{\"type\":\"author\",\"id\":\"a-1\"}]",
                "{\"title\":\"fixture\"}", "retry", "{}", 0, now, now);
    }

    private static ScheduledPendingWork pendingByKey(List<ScheduledPendingWork> rows,
                                                      String workType,
                                                      String workId) {
        return rows.stream()
                .filter(row -> row.workType().equals(workType) && row.workId().equals(workId))
                .findFirst()
                .orElse(null);
    }
}
