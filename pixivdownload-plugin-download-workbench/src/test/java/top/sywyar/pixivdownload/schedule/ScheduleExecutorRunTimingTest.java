package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.schedule.execution.ScheduleCredentialCircuitOpenException;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionControlException;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionResult;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleExecutor 耐久状态与收尾屏障")
class ScheduleExecutorRunTimingTest {

    private static final String WORK_TYPE = "fixture.work";

    @Mock
    private ScheduledTaskStore store;
    @Mock
    private top.sywyar.pixivdownload.core.notification.NotificationService notificationService;
    @Mock
    private AppMessages appMessages;
    @Mock
    private WebI18nBundleRegistry webI18nBundleRegistry;
    @Mock
    private UserDisplayNameProvider userDisplayNameProvider;

    private ObjectMapper objectMapper;
    private ScheduleRunState localRunState;
    private ScheduleExecutionEngine defaultEngine;
    private ScheduleExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        localRunState = new ScheduleRunState();
        defaultEngine = mock(ScheduleExecutionEngine.class);
        lenient().when(defaultEngine.execute(any(ScheduledTask.class), any()))
                .thenReturn(emptyResult());
        executor = genericExecutor(defaultEngine);

        lenient().when(store.tryQueueNow(anyLong(), anyLong(), anyString()))
                .thenAnswer(invocation -> Optional.of(new ScheduleRunToken(
                        invocation.getArgument(2), 1L,
                        top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED)));
        lenient().when(store.startRun(anyLong(), any(ScheduleRunToken.class)))
                .thenAnswer(invocation -> {
                    ScheduleRunToken queued = invocation.getArgument(1);
                    return Optional.of(new ScheduleRunToken(
                            queued.claimToken(), 2L,
                            top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING));
                });
        lenient().when(store.completeRun(
                        anyLong(), any(ScheduleRunToken.class), any(ScheduleRunCompletion.class)))
                .thenReturn(OptionalLong.of(3L));
        lenient().when(store.finishCancelled(
                        anyLong(), any(ScheduleRunToken.class), any(ScheduleLastOutcome.class),
                        anyLong(), any(), any(), any()))
                .thenReturn(OptionalLong.of(3L));
        lenient().when(store.suspend(
                        anyLong(), anyLong(), any(ScheduleSuspendReason.class), any(), any()))
                .thenReturn(OptionalLong.of(3L));
    }

    @Test
    @DisplayName("现代执行引擎完成后原子提交结果与候选检查点")
    void commitsEngineOutcomeAndCheckpoint() throws Exception {
        ScheduledTask task = task(1L, "user-new", userDefinition("100"), null, null, null);
        ScheduledCheckpoint checkpoint =
                new ScheduledCheckpoint("fixture.checkpoint", 1, "{\"cursor\":\"next\"}");
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenReturn(
                new ScheduleExecutionResult(2, checkpoint, false, List.of()));

        genericExecutor(engine).runTaskAndRecord(task);

        ScheduleRunCompletion completion = captureCompletion(1L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.OK);
        assertThat(completion.checkpointSchema()).isEqualTo("fixture.checkpoint");
        assertThat(completion.checkpointVersion()).isEqualTo(1);
        assertThat(completion.checkpointJson()).isEqualTo("{\"cursor\":\"next\"}");
        verify(store).startRun(eq(1L), any(ScheduleRunToken.class));
    }

    @Test
    @DisplayName("来源能力缺席时挂起为 SOURCE_UNAVAILABLE 且不提交检查点")
    void sourceUnavailableSuspendsWithoutCheckpoint() throws Exception {
        ScheduledTask task = task(2L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any()))
                .thenThrow(new ScheduleSourceUnavailableException("user-new"));

        genericExecutor(engine).runTaskAndRecord(task);

        verify(store).suspend(
                eq(2L), eq(2L), eq(ScheduleSuspendReason.SOURCE_UNAVAILABLE),
                eq("SOURCE_UNAVAILABLE"), isNull());
        verify(store).finishCancelled(
                eq(2L), any(ScheduleRunToken.class), eq(ScheduleLastOutcome.ERROR),
                anyLong(), eq("SOURCE_UNAVAILABLE"), any(), any());
        verify(store, never()).completeRun(eq(2L), any(), any());
    }

    @Test
    @DisplayName("作品执行器缺席时挂起为 EXECUTOR_UNAVAILABLE")
    void executorUnavailableSuspendsWithoutPartialExecution() throws Exception {
        ScheduledTask task = task(3L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any()))
                .thenThrow(new ScheduleExecutorUnavailableException("user-new", Set.of("novel")));

        genericExecutor(engine).runTaskAndRecord(task);

        verify(store).suspend(
                eq(3L), eq(2L), eq(ScheduleSuspendReason.EXECUTOR_UNAVAILABLE),
                eq("EXECUTOR_UNAVAILABLE"), isNull());
        verify(store).finishCancelled(
                eq(3L), any(ScheduleRunToken.class), eq(ScheduleLastOutcome.ERROR),
                anyLong(), eq("EXECUTOR_UNAVAILABLE"), any(), any());
        verify(store, never()).completeRun(eq(3L), any(), any());
    }

    @Test
    @DisplayName("损坏定义挂起为 MIGRATION_ERROR")
    void invalidDefinitionSuspendsAsMigrationError() throws Exception {
        ScheduledTask task = task(4L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any()))
                .thenThrow(new ScheduleDefinitionException("definition mismatch"));

        genericExecutor(engine).runTaskAndRecord(task);

        verify(store).suspend(
                eq(4L), eq(2L), eq(ScheduleSuspendReason.MIGRATION_ERROR),
                eq("DEFINITION_INVALID"), anyString());
        verify(store).finishCancelled(
                eq(4L), any(ScheduleRunToken.class), eq(ScheduleLastOutcome.ERROR),
                anyLong(), eq("DEFINITION_INVALID"), anyString(), any());
        verify(store, never()).completeRun(eq(4L), any(), any());
    }

    @Test
    @DisplayName("协作式人工取消只做取消收尾且不提交检查点")
    void manualCancellationFinishesWithoutCheckpoint() throws Exception {
        ScheduledTask task = task(5L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenThrow(ScheduledExecutionException.cancelled());

        genericExecutor(engine).runTaskAndRecord(task);

        verify(store).finishCancelled(
                eq(5L), any(ScheduleRunToken.class), eq(ScheduleLastOutcome.CANCELLED),
                anyLong(), eq("schedule.cancelled"), eq("schedule.cancelled"), any());
        verify(store, never()).completeRun(eq(5L), any(), any());
    }

    @Test
    @DisplayName("顶层失败结果在持久化前统一脱敏凭证与签名 URL")
    void failureOutcomeRedactsCredentialForms() throws Exception {
        ScheduledTask task = task(6L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenThrow(new IllegalStateException(
                "PHPSESSID=cookie-secret; Authorization: Bearer bearer-secret "
                        + "token: token-secret url=https://example.test/a?X-Amz-Signature=signature-secret"));

        genericExecutor(engine).runTaskAndRecord(task);

        ScheduleRunCompletion completion = captureCompletion(6L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.ERROR);
        assertThat(completion.outcomeMessage()).contains("[redacted]")
                .doesNotContain("cookie-secret", "bearer-secret", "token-secret", "signature-secret");
    }

    @Test
    @DisplayName("通用凭证挂起发送鉴权失效通知且不携带下次运行时间")
    void credentialSuspensionSendsAuthExpiredNotification() throws Exception {
        ScheduledTask task = task(7L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenThrow(new ScheduleExecutionControlException(
                ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                "COOKIE_DEAD", 0L, ScheduledGuardEvidence.empty()));

        genericExecutor(engine).runTaskAndRecord(task);

        verify(store).suspend(
                eq(7L), eq(2L), eq(ScheduleSuspendReason.CREDENTIAL), eq("COOKIE_DEAD"), eq("{}"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> placeholders = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(
                eq(NotificationScenario.AUTH_EXPIRED), any(), placeholders.capture());
        assertThat(placeholders.getValue()).doesNotContainKey("next_run_time");
    }

    @Test
    @DisplayName("通用账号策略挂起覆盖同凭证账号")
    void policyAccountSuspensionCoversCredentialAccount() throws Exception {
        long taskId = 8L;
        ScheduledTask task = task(
                taskId, "user-new", userDefinition("100"), null, "{}", "credential-ref");
        when(store.findByCredentialAccount(
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                Long.toString(taskId)))
                .thenReturn(List.of(task));
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenThrow(new ScheduleExecutionControlException(
                ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT,
                "fixture.account-risk", 0L, ScheduledGuardEvidence.empty()));

        genericExecutor(engine).runTaskAndRecord(task);

        verify(store).suspendByCredentialAccount(
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                Long.toString(taskId),
                ScheduleSuspendReason.POLICY,
                "fixture.account-risk",
                "{}");
        verify(store, never()).suspend(
                eq(taskId), anyLong(), any(ScheduleSuspendReason.class), any(), any());
        verify(store).finishCancelled(
                eq(taskId), any(ScheduleRunToken.class), eq(ScheduleLastOutcome.ERROR),
                anyLong(), eq("fixture.account-risk"), eq("fixture.account-risk"), any());
    }

    @Test
    @DisplayName("通用凭证熔断保留安全计数与末次错误码")
    void credentialCircuitSendsCircuitBreakerNotification() throws Exception {
        ScheduledTask task = task(9L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenThrow(
                new ScheduleCredentialCircuitOpenException(
                        5, "pixiv.illust.access-unavailable"));

        genericExecutor(engine).runTaskAndRecord(task);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(store).suspend(
                eq(9L), eq(2L), eq(ScheduleSuspendReason.CREDENTIAL),
                eq("schedule.credential.failure-circuit-open"), detail.capture());
        assertThat(objectMapper.readTree(detail.getValue()).path("consecutiveFailures").asInt())
                .isEqualTo(5);
        assertThat(objectMapper.readTree(detail.getValue()).path("lastErrorExcerpt").asText())
                .isEqualTo("pixiv.illust.access-unavailable");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> placeholders = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(
                eq(NotificationScenario.CIRCUIT_BREAKER), any(), placeholders.capture());
        assertThat(placeholders.getValue())
                .containsEntry("consecutive_failures", "5")
                .containsEntry("last_error_excerpt", "pixiv.illust.access-unavailable")
                .doesNotContainKey("next_run_time");
    }

    @Test
    @DisplayName("匿名降级按任务实际策略身份删除失效凭证")
    void anonymousDowngradeRemovesActualPolicyCredential() throws Exception {
        ScheduledTask task = org.mockito.Mockito.spy(task(
                10L, "user-new", userDefinition("100"), null, "{}", "credential-ref"));
        when(task.credentialPolicyOwnerPluginId()).thenReturn("fixture-policy-owner");
        when(task.credentialPolicyId()).thenReturn("fixture-policy");
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenReturn(
                new ScheduleExecutionResult(0, null, true, List.of()));
        when(store.removeCredential(10L, 3L, "fixture-policy-owner", "fixture-policy"))
                .thenReturn(OptionalLong.of(4L));

        genericExecutor(engine).runTaskAndRecord(task);

        verify(store).removeCredential(
                10L, 3L, "fixture-policy-owner", "fixture-policy");
        verify(store, never()).removeCredential(
                10L, 3L, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID);
    }

    @Test
    @DisplayName("失败路径的撤销后继续不会删除凭证或提交检查点")
    void failureRevokeControlDoesNotDeleteCredentialOrCommitCheckpoint() throws Exception {
        ScheduledTask task = task(
                11L, "user-new", userDefinition("100"),
                new Checkpoint("fixture.checkpoint", 1, "{\"cursor\":\"safe\"}"),
                "{}", "credential-ref");
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenThrow(new ScheduleExecutionControlException(
                ScheduledGuardDecision.Action.REVOKE_CREDENTIAL_AND_CONTINUE,
                "fixture.failure-revoke", 0L, ScheduledGuardEvidence.empty()));

        genericExecutor(engine).runTaskAndRecord(task);

        ScheduleRunCompletion completion = captureCompletion(11L);
        assertThat(completion.outcome()).isEqualTo(ScheduleLastOutcome.ERROR);
        assertThat(completion.outcomeCode()).isEqualTo("fixture.failure-revoke");
        assertThat(completion.checkpointSchema()).isNull();
        assertThat(completion.checkpointVersion()).isNull();
        assertThat(completion.checkpointJson()).isNull();
        verify(store, never()).removeCredential(
                eq(11L), anyLong(), anyString(), anyString());
        verify(notificationService, never()).notify(
                eq(NotificationScenario.DEGRADED_ANONYMOUS), any(), any());
    }

    @Test
    @DisplayName("pending 通知保留不透明作品身份且仅为已知 Pixiv 类型生成直链")
    void pendingNotificationPreservesOpaqueIdentity() throws Exception {
        ScheduledTask task = task(12L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<ScheduleExecutionResult.PendingExhausted> listener =
                    invocation.getArgument(1);
            listener.accept(new ScheduleExecutionResult.PendingExhausted(
                    "video", "video:abc/1", 5, 1_000L, "video.failed"));
            listener.accept(new ScheduleExecutionResult.PendingExhausted(
                    "video", "123456", 5, 1_500L, "video.failed"));
            listener.accept(new ScheduleExecutionResult.PendingExhausted(
                    "novel", "184467440737095516160", 5, 2_000L, "novel.failed"));
            return emptyResult();
        }).when(engine).execute(eq(task), any());

        genericExecutor(engine).runTaskAndRecord(task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> placeholders = ArgumentCaptor.forClass(Map.class);
        verify(notificationService, times(3)).notify(
                eq(NotificationScenario.PENDING_EXHAUSTED), any(), placeholders.capture());
        assertThat(placeholders.getAllValues().get(0))
                .containsEntry("work_id", "video:abc/1")
                .containsEntry("work_kind", "video")
                .containsEntry("work_url", "");
        assertThat(placeholders.getAllValues().get(1))
                .containsEntry("work_id", "123456")
                .containsEntry("work_kind", "video")
                .containsEntry("work_url", "");
        assertThat(placeholders.getAllValues().get(2))
                .containsEntry("work_id", "184467440737095516160")
                .containsEntry("work_url",
                        "https://www.pixiv.net/novel/show.php?id=184467440737095516160");
    }

    @Test
    @DisplayName("来源通知从 descriptor presentation 命名空间解析任务类型")
    void notificationResolvesDescriptorTaskTypeLabel() throws Exception {
        ScheduledTask task = task(13L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenThrow(new ScheduleExecutionControlException(
                ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                "COOKIE_DEAD", 0L, ScheduledGuardEvidence.empty()));
        WebI18nBundleRegistry.RegisteredBundle bundle = new WebI18nBundleRegistry.RegisteredBundle(
                "fixture",
                new I18nContribution("fixture", "i18n.web.fixture"),
                Map.of(
                        "en-US", Map.of("source.label", "Fixture source"),
                        "zh-CN", Map.of("source.label", "Fixture source")),
                null);
        when(webI18nBundleRegistry.resolve("fixture")).thenReturn(bundle);

        genericExecutor(engine).runTaskAndRecord(task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> placeholders = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(
                eq(NotificationScenario.AUTH_EXPIRED), any(), placeholders.capture());
        assertThat(placeholders.getValue()).containsEntry("task_type", "Fixture source");
    }

    @Test
    @DisplayName("超大重试延迟饱和到 long 上限而不会溢出提前运行")
    void retryDelaySaturatesAtLongMaxValue() throws Exception {
        ScheduledTask task = task(14L, "user-new", userDefinition("100"), null, null, null);
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        when(engine.execute(eq(task), any())).thenThrow(new ScheduledExecutionException(
                ScheduledFailure.Category.RETRYABLE_NETWORK,
                "fixture.retry-later", Long.MAX_VALUE));

        genericExecutor(engine).runTaskAndRecord(task);

        assertThat(captureCompletion(14L).nextRunTime()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("开始运行写库异常会收尾同 claim 的不确定持久化状态")
    void startRunFailureFinalizesSameDurableClaim() {
        ScheduledTask task = task(15L, "user-new", userDefinition("100"), null, null, null);
        ScheduleRunState.Claim claim = localRunState.tryMarkQueued(15L);
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-start-failure", 1L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleRunToken durableRunning = new ScheduleRunToken(
                queued.claimToken(), 2L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING);
        ScheduledTask current = mock(ScheduledTask.class);
        when(current.nextRunTime()).thenReturn(9_000L);
        when(current.runState()).thenReturn(durableRunning.runState());
        when(current.runClaimToken()).thenReturn(durableRunning.claimToken());
        when(current.stateVersion()).thenReturn(durableRunning.stateVersion());
        when(store.startRun(15L, queued)).thenThrow(new IllegalStateException("start write failed"));
        when(store.findById(15L)).thenReturn(current);
        when(store.releaseQueued(15L, queued, null)).thenReturn(OptionalLong.empty());
        when(store.finishCancelled(
                eq(15L), eq(durableRunning), eq(ScheduleLastOutcome.INTERRUPTED), anyLong(),
                eq("CLAIM_ABANDONED"), isNull(), eq(9_000L)))
                .thenReturn(OptionalLong.of(3L));

        assertThatThrownBy(() -> executor.runTaskAndRecord(task, claim, queued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("start write failed");

        verify(store).releaseQueued(15L, queued, null);
        verify(store).finishCancelled(
                eq(15L), eq(durableRunning), eq(ScheduleLastOutcome.INTERRUPTED), anyLong(),
                eq("CLAIM_ABANDONED"), isNull(), eq(9_000L));
        assertThat(localRunState.get(15L)).isNull();
    }

    @Test
    @DisplayName("最终结果写库异常会以错误终态收尾同 claim 并清理内存认领")
    void finalizationFailureFinishesSameDurableClaimAsError() {
        ScheduledTask task = task(16L, "user-new", userDefinition("100"), null, null, null);
        ScheduleRunState.Claim claim = localRunState.tryMarkQueued(16L);
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-finalization-failure", 1L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleRunToken running = new ScheduleRunToken(
                queued.claimToken(), 2L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING);
        ScheduledTask current = mock(ScheduledTask.class);
        when(current.runState()).thenReturn(running.runState());
        when(current.runClaimToken()).thenReturn(running.claimToken());
        when(current.stateVersion()).thenReturn(running.stateVersion());
        when(store.startRun(16L, queued)).thenReturn(Optional.of(running));
        when(store.completeRun(eq(16L), eq(running), any(ScheduleRunCompletion.class)))
                .thenThrow(new IllegalStateException("completion write failed"));
        when(store.findById(16L)).thenReturn(current);
        when(store.finishCancelled(
                eq(16L), eq(running), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("FINALIZATION_FAILED"), eq("completion write failed"), any()))
                .thenReturn(OptionalLong.of(3L));

        assertThatThrownBy(() -> executor.runTaskAndRecord(task, claim, queued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("completion write failed");

        verify(store).finishCancelled(
                eq(16L), eq(running), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("FINALIZATION_FAILED"), eq("completion write failed"), any());
        assertThat(localRunState.get(16L)).isNull();
    }

    @Test
    @DisplayName("宿主租约获取异常会释放耐久队列认领和内存认领")
    void hostLeaseAcquisitionFailureReleasesQueuedClaim() {
        ScheduledTask task = task(17L, "user-new", userDefinition("100"), null, null, null);
        ScheduleRunState.Claim claim = localRunState.tryMarkQueued(17L);
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-host-lease-failure", 1L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleCapabilityRegistry failingRegistry = mock(ScheduleCapabilityRegistry.class);
        when(failingRegistry.resolveOwner(DownloadWorkbenchPlugin.ID))
                .thenThrow(new IllegalStateException("registry unavailable"));
        when(store.releaseQueued(17L, queued, null))
                .thenReturn(OptionalLong.of(2L));
        ScheduleExecutor failingExecutor = newExecutor(failingRegistry, defaultEngine);

        assertThatThrownBy(() -> failingExecutor.runTaskAndRecord(task, claim, queued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registry unavailable");

        verify(store).releaseQueued(17L, queued, null);
        assertThat(localRunState.get(17L)).isNull();
    }

    @Test
    @DisplayName("队列释放的一次性数据库异常由同 token 重试收敛")
    void transientQueuedReleaseFailureIsRetried() {
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-transient-release", 4L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduledTask current = mock(ScheduledTask.class);
        when(current.runState()).thenReturn(queued.runState());
        when(current.runClaimToken()).thenReturn(queued.claimToken());
        when(current.stateVersion()).thenReturn(queued.stateVersion());
        when(current.nextRunTime()).thenReturn(12_000L);
        when(store.findById(18L)).thenReturn(current);
        when(store.releaseQueued(18L, queued, null))
                .thenThrow(new IllegalStateException("temporary database failure"))
                .thenReturn(OptionalLong.of(5L));

        executor.releaseQueued(18L, queued);

        verify(store, times(2)).releaseQueued(18L, queued, null);
    }

    @Test
    @DisplayName("执行引擎抛出 LinkageError 时按同 token 清理并原样抛出")
    void engineLinkageErrorFinalizesSameClaimAndPreservesError() throws Exception {
        LinkageError pluginFailure = new LinkageError("source plugin linkage failed");
        ScheduleExecutionEngine engine = mock(ScheduleExecutionEngine.class);
        ScheduledTask task = task(19L, "user-new", userDefinition("100"), null, null, null);
        when(engine.execute(eq(task), any())).thenThrow(pluginFailure);
        ScheduleExecutor failingExecutor = genericExecutor(engine);
        ScheduleRunState.Claim claim = localRunState.tryMarkQueued(19L);
        ScheduleRunToken queued = new ScheduleRunToken(
                "claim-plugin-error", 1L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        ScheduleRunToken running = new ScheduleRunToken(
                queued.claimToken(), 2L,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING);
        ScheduledTask current = mock(ScheduledTask.class);
        when(current.runState()).thenReturn(running.runState());
        when(current.runClaimToken()).thenReturn(running.claimToken());
        when(current.stateVersion()).thenReturn(running.stateVersion());
        when(store.startRun(19L, queued)).thenReturn(Optional.of(running));
        when(store.findById(19L)).thenReturn(current);
        when(store.finishCancelled(
                eq(19L), eq(running), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("UNCAUGHT_THROWABLE"), isNull(), any()))
                .thenReturn(OptionalLong.of(3L));

        assertThatThrownBy(() -> failingExecutor.runTaskAndRecord(task, claim, queued))
                .isSameAs(pluginFailure);

        verify(store).finishCancelled(
                eq(19L), eq(running), eq(ScheduleLastOutcome.ERROR), anyLong(),
                eq("UNCAUGHT_THROWABLE"), isNull(), any());
        assertThat(localRunState.get(19L)).isNull();
    }

    @Test
    @DisplayName("host owner 租约覆盖执行引擎返回后的结果持久化收尾")
    void hostOwnerLeaseCoversFinalization() throws Exception {
        ScheduledTask task = task(20L, "user-new", userDefinition("100"), null, null, null);
        CountDownLatch finalizationStarted = new CountDownLatch(1);
        CountDownLatch allowFinalization = new CountDownLatch(1);
        doAnswer(invocation -> {
            finalizationStarted.countDown();
            assertThat(allowFinalization.await(5, TimeUnit.SECONDS)).isTrue();
            return OptionalLong.of(3L);
        }).when(store).completeRun(
                eq(20L), any(ScheduleRunToken.class), any(ScheduleRunCompletion.class));

        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = publishSource(registry);
        ScheduleExecutor leasedExecutor = newExecutor(registry, defaultEngine);
        Thread run = new Thread(
                () -> leasedExecutor.runTaskAndRecord(task),
                "schedule-finalization-lease-test");
        run.start();
        try {
            assertThat(finalizationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            ScheduleGenerationDrain drain =
                    ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
            assertThat(drain.activeLeaseCount()).isEqualTo(1);
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))).isFalse();

            allowFinalization.countDown();
            run.join(5_000L);
            assertThat(run.isAlive()).isFalse();
            assertThat(drain.awaitDrained(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isTrue();
        } finally {
            allowFinalization.countDown();
            run.join(5_000L);
        }
    }

    private ScheduleExecutor genericExecutor(ScheduleExecutionEngine engine) {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        publishSource(registry);
        return newExecutor(registry, engine);
    }

    private ScheduleExecutor newExecutor(
            ScheduleCapabilityRegistry registry,
            ScheduleExecutionEngine engine) {
        return new ScheduleExecutor(
                store,
                registry,
                localRunState,
                objectMapper,
                notificationService,
                appMessages,
                webI18nBundleRegistry,
                userDisplayNameProvider,
                engine);
    }

    private ScheduleCapabilityPublication publishSource(ScheduleCapabilityRegistry registry) {
        ScheduledSourceExecutor source = new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return "user-new";
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                return ScheduledExecutionPlan.credentialFree(Set.of(WORK_TYPE));
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                "user-new",
                Set.of(),
                PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                new ScheduledSourcePresentation(
                        "fixture", "source.label", "source.description", "schedule", "neutral"),
                Set.of(),
                Set.of(WORK_TYPE),
                Set.of(),
                Set.of(),
                null);
        ScheduledWorkExecutor workExecutor = mock(ScheduledWorkExecutor.class);
        when(workExecutor.workType()).thenReturn(WORK_TYPE);
        return ScheduleCapabilityTestFixture.publish(
                registry,
                new ScheduleCapabilityOwner(
                        DownloadWorkbenchPlugin.ID, DownloadWorkbenchPlugin.ID, 1L),
                List.of(descriptor),
                List.of(source),
                List.of(workExecutor));
    }

    private ScheduleRunCompletion captureCompletion(long taskId) {
        ArgumentCaptor<ScheduleRunCompletion> completion =
                ArgumentCaptor.forClass(ScheduleRunCompletion.class);
        verify(store).completeRun(eq(taskId), any(ScheduleRunToken.class), completion.capture());
        return completion.getValue();
    }

    private static ScheduleExecutionResult emptyResult() {
        return new ScheduleExecutionResult(0, null, false, List.of());
    }

    private static String userDefinition(String userId) {
        return "{\"kind\":\"illust\",\"source\":{\"userId\":\"" + userId + "\"}}";
    }

    private ScheduledTask task(
            long id,
            String sourceType,
            String definitionJson,
            Checkpoint checkpoint,
            String policyStateJson,
            String secretReference) {
        boolean hasCredential = policyStateJson != null || secretReference != null;
        return new ScheduledTask(
                id, "任务" + id, true, sourceType, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                definitionJson, "{}", ScheduledTask.TRIGGER_INTERVAL, 1, null,
                null, 0L, null,
                checkpoint == null ? null : checkpoint.schema(),
                checkpoint == null ? null : checkpoint.version(),
                checkpoint == null ? null : checkpoint.json(),
                ScheduledTask.CURRENT_STORAGE_VERSION,
                null, null, ScheduleLastOutcome.NEVER, null, null,
                null, null, null, 0L,
                hasCredential ? DownloadWorkbenchPlugin.ID : null,
                hasCredential ? PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID : null,
                hasCredential ? Long.toString(id) : null,
                policyStateJson,
                secretReference,
                hasCredential ? 1L : null,
                0L);
    }

    private record Checkpoint(String schema, int version, String json) {
    }
}
