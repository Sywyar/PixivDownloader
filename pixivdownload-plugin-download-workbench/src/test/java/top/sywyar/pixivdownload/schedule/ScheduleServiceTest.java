package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityLease;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleCredentialPolicyActionRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleCredentialPolicyView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;
import top.sywyar.pixivdownload.schedule.execution.ScheduleCredentialBindingLease;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.download.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleService canonical 状态与凭证操作")
class ScheduleServiceTest {

    private static final long STATE_VERSION = 7L;
    private static final String SOURCE_TYPE = "user-new";
    private static final String ACTIVATION_TOKEN = "activation-current";
    private static final ScheduleCapabilityOwner STATUS_OWNER =
            new ScheduleCapabilityOwner("third-party", "third-party", 1L);
    private static final long STATUS_PUBLICATION_ID = 1L;
    private static final String EMPTY_POLICY_STATE =
            "{\"schema\":\"pixiv.schedule.credential-policy-state\",\"version\":1}";

    private static final PlatformTransactionManager NO_OP_TRANSACTION_MANAGER =
            new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                }

                @Override
                public void rollback(TransactionStatus status) {
                }
            };

    @Mock
    private ScheduledTaskStore store;
    @Mock
    private ScheduleExecutor executor;
    @Mock
    private ScheduleRunQueue runQueue;
    @Mock
    private ScheduleExecutionEngine scheduleExecutionEngine;
    @Mock
    private ScheduleCredentialBindingLease credentialBindingLease;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PixivSchedulePersistenceCodec persistenceCodec =
            new PixivSchedulePersistenceCodec(objectMapper);
    private final TransactionTemplate transactionTemplate =
            new TransactionTemplate(NO_OP_TRANSACTION_MANAGER);

    /** 默认空统一能力注册中心（多数用例不触发翻译状态叠加）。 */
    private static FakeScheduleCapabilityAccess emptyCapabilityRegistry() {
        return new FakeScheduleCapabilityAccess();
    }

    private ScheduleService newService() {
        return newService(new ScheduleRunState(), emptyCapabilityRegistry());
    }

    private ScheduleService newService(ScheduleRunState runState,
                                       ScheduleCapabilityAccess capabilityRegistry) {
        ScheduleCredentialService credentialService = new ScheduleCredentialService(
                store, runState, scheduleExecutionEngine, capabilityRegistry,
                transactionTemplate, objectMapper);
        return new ScheduleService(
                store, executor, new ScheduleConfig(), runState, runQueue,
                objectMapper, credentialService, transactionTemplate,
                capabilityRegistry, new ScheduleHostIdentity(DownloadWorkbenchPlugin.ID));
    }

    private static ScheduledWork queueWork(String workType, String workId, String title) {
        return new ScheduledWork(
                new ScheduledWorkKey(workType, workId),
                "fixture.schedule.work",
                1,
                "{}",
                new ScheduledWorkPresentation(
                        title,
                        "raw-author",
                        "thumbnail-reference",
                        Map.of("sourceHint", "raw")),
                List.of());
    }

    private static void discover(ScheduleRunQueue.Run run, ScheduledWork work) {
        run.discovered(work, STATUS_OWNER, STATUS_PUBLICATION_ID);
    }

    private void stubBinding(ScheduledCredentialBindResult result) throws Exception {
        stubBindingIdentity();
        when(credentialBindingLease.probe(anyString())).thenReturn(result);
    }

    private void stubBindingIdentity() throws Exception {
        when(scheduleExecutionEngine.prepareCredentialBinding(any(), eq(ACTIVATION_TOKEN)))
                .thenReturn(credentialBindingLease);
        when(credentialBindingLease.policyOwnerPluginId())
                .thenReturn(DownloadWorkbenchPlugin.ID);
        when(credentialBindingLease.policyId())
                .thenReturn(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID);
    }

    private static ScheduledCredentialBindResult cleanBinding(String accountKey) {
        return new ScheduledCredentialBindResult(
                ScheduledCredentialProbeResult.valid(accountKey),
                EMPTY_POLICY_STATE, null);
    }

    private static ScheduledCredentialBindResult warnedBinding(
            String accountKey, long modifiedAt, String excerpt) {
        return new ScheduledCredentialBindResult(
                ScheduledCredentialProbeResult.valid(accountKey),
                EMPTY_POLICY_STATE,
                new ScheduledGuardResult(
                        new ScheduledGuardDecision(
                                ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK,
                                "PIXIV_OVERUSE", 0L),
                        new ScheduledGuardEvidence(Map.of(
                                "modifiedAt", Long.toString(modifiedAt),
                                "excerpt", excerpt))));
    }

    private static ScheduledTask task(long id) {
        return task(id, true, null, null, null, null, null, null, false);
    }

    private static ScheduledTask task(long id,
                                      boolean enabled,
                                      top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState runState,
                                      ScheduleSuspendReason suspendReason,
                                      String suspendCode,
                                      String suspendDetailJson,
                                      String accountId,
                                      String policyStateJson,
                                      boolean credentialBound) {
        return task(id, enabled, runState, suspendReason, suspendCode,
                suspendDetailJson, accountId, policyStateJson, credentialBound,
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID);
    }

    private static ScheduledTask task(long id,
                                      boolean enabled,
                                      top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState runState,
                                      ScheduleSuspendReason suspendReason,
                                      String suspendCode,
                                      String suspendDetailJson,
                                      String accountId,
                                      String policyStateJson,
                                      boolean credentialBound,
                                      String credentialPolicyOwnerPluginId,
                                      String credentialPolicyId) {
        String effectivePolicyState = credentialBound
                ? policyStateJson == null ? EMPTY_POLICY_STATE : policyStateJson
                : null;
        return new ScheduledTask(
                id,
                "t",
                enabled,
                SOURCE_TYPE,
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                "{\"kind\":\"illust\",\"source\":{\"userId\":\"1\"}}",
                "{}",
                ScheduledTask.TRIGGER_INTERVAL,
                60,
                null,
                null,
                1000L,
                null,
                null,
                null,
                null,
                ScheduledTask.CURRENT_STORAGE_VERSION,
                runState,
                runState == null ? null : "run-claim-" + id,
                ScheduleLastOutcome.NEVER,
                null,
                null,
                suspendReason,
                suspendCode,
                suspendDetailJson,
                STATE_VERSION,
                credentialBound ? credentialPolicyOwnerPluginId : null,
                credentialBound ? credentialPolicyId : null,
                credentialBound ? accountId : null,
                effectivePolicyState,
                credentialBound ? "scheduled-task:" + id + ":credential" : null,
                0L);
    }

    @Test
    @DisplayName("pause：以 stateVersion 挂起并向本轮 Claim 发协作式取消信号")
    void pauseSuspendsWithCasAndRequestsCancel() {
        when(store.findById(42L)).thenReturn(task(42L));
        when(store.suspend(
                42L, STATE_VERSION, ScheduleSuspendReason.MANUAL, "ADMIN_PAUSE", null))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));
        ScheduleRunState runState = new ScheduleRunState();
        ScheduleRunState.Claim claim = runState.tryMarkQueued(42L);
        assertThat(claim).isNotNull();
        ScheduleService service = newService(runState, emptyCapabilityRegistry());

        service.pause(42L);

        verify(store).suspend(
                42L, STATE_VERSION, ScheduleSuspendReason.MANUAL, "ADMIN_PAUSE", null);
        assertThat(runState.isCancelRequested(42L)).isTrue();
    }

    @Test
    @DisplayName("pause：版本 CAS 失败时拒绝且不发送取消信号")
    void pauseRejectsConcurrentChangeBeforeRequestingCancel() {
        when(store.findById(99L)).thenReturn(task(99L));
        when(store.suspend(
                99L, STATE_VERSION, ScheduleSuspendReason.MANUAL, "ADMIN_PAUSE", null))
                .thenReturn(OptionalLong.empty());
        ScheduleRunState runState = new ScheduleRunState();
        assertThat(runState.tryMarkQueued(99L)).isNotNull();

        assertThatThrownBy(() -> newService(runState, emptyCapabilityRegistry()).pause(99L))
                .isInstanceOf(LocalizedException.class);

        assertThat(runState.isCancelRequested(99L)).isFalse();
    }

    @Test
    @DisplayName("manualRun：内存中已运行或排队时拒绝且不触发执行")
    void manualRunRejectedWhenBusy() {
        when(store.findById(7L)).thenReturn(task(7L));
        ScheduleRunState runState = new ScheduleRunState();
        runState.tryMarkQueued(7L);

        assertThatThrownBy(() -> newService(runState, emptyCapabilityRegistry()).manualRun(7L))
                .isInstanceOf(LocalizedException.class);

        verify(executor, never()).runTaskAsync(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("manualRun：已停用任务拒绝")
    void manualRunRejectedWhenDisabled() {
        when(store.findById(8L)).thenReturn(
                task(8L, false, null, null, null, null, null, null, false));

        assertThatThrownBy(() -> newService().manualRun(8L))
                .isInstanceOf(LocalizedException.class);

        verify(executor, never()).runTaskAsync(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("manualRun：存在 canonical 挂起原因时拒绝")
    void manualRunRejectedWhenSuspended() {
        when(store.findById(9L)).thenReturn(task(
                9L, true, null, ScheduleSuspendReason.MANUAL,
                "ADMIN_PAUSE", null, null, null, false));

        assertThatThrownBy(() -> newService().manualRun(9L))
                .isInstanceOf(LocalizedException.class);

        verify(executor, never()).runTaskAsync(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("runOnce：durable QUEUED token 与 owner 租约一起转交异步执行器")
    void runOnceTransfersDurableTokenAndOwnerLeaseBeforeAsyncQueueing() {
        long taskId = 41L;
        ScheduleRunToken runToken = new ScheduleRunToken(
                "claim-41", STATE_VERSION + 1,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        when(store.findById(taskId)).thenReturn(task(taskId));
        when(store.tryQueueNow(eq(taskId), eq(STATE_VERSION), anyString()))
                .thenReturn(Optional.of(runToken));
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication =
                ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of());
        ScheduleRunState runState = new ScheduleRunState();
        ScheduleService service = newService(runState, registry);
        AtomicReference<ScheduleRunState.Claim> transferredClaim = new AtomicReference<>();
        AtomicReference<ScheduleRunToken> transferredToken = new AtomicReference<>();
        AtomicReference<ScheduleCapabilityLease<ScheduleCapabilityOwner>> transferredLease =
                new AtomicReference<>();
        doAnswer(invocation -> {
            transferredClaim.set(invocation.getArgument(1));
            transferredToken.set(invocation.getArgument(2));
            transferredLease.set(invocation.getArgument(3));
            return null;
        }).when(executor).runTaskAsync(eq(taskId), any(), eq(runToken), any());

        service.runOnce(taskId);

        verify(store).tryQueueNow(eq(taskId), eq(STATE_VERSION), anyString());
        assertThat(transferredToken.get()).isSameAs(runToken);
        FakeScheduleCapabilityAccess.Drain drain =
                ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow();
        assertThat(runState.get(taskId)).isEqualTo(ScheduleRunState.QUEUED);
        assertThat(drain.activeLeaseCount()).isEqualTo(1);
        assertThat(drain.isDrained()).isFalse();
        assertThat(transferredLease.get().cancellation().isCancellationRequested()).isTrue();

        transferredLease.get().close();
        runState.clear(transferredClaim.get());
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("runOnce：异步提交失败时释放 durable token、owner 租约与内存 Claim")
    void runOnceReleasesDurableClaimWhenAsyncSubmissionFails() {
        long taskId = 42L;
        ScheduleRunToken runToken = new ScheduleRunToken(
                "claim-42", STATE_VERSION + 1,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED);
        when(store.findById(taskId)).thenReturn(task(taskId));
        when(store.tryQueueNow(eq(taskId), eq(STATE_VERSION), anyString()))
                .thenReturn(Optional.of(runToken));
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication =
                ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of());
        ScheduleRunState runState = new ScheduleRunState();
        doThrow(new IllegalStateException("rejected"))
                .when(executor).runTaskAsync(eq(taskId), any(), eq(runToken), any());

        assertThatThrownBy(() -> newService(runState, registry).runOnce(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rejected");

        verify(executor).releaseQueued(taskId, runToken);
        assertThat(runState.get(taskId)).isNull();
        assertThat(ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow().isDrained())
                .isTrue();
    }

    @Test
    @DisplayName("runOnce：durable claim 被拒绝时关闭 owner 租约并清除内存 Claim")
    void runOnceClosesLeaseWhenDurableClaimIsRejected() {
        long taskId = 43L;
        when(store.findById(taskId)).thenReturn(task(taskId));
        when(store.tryQueueNow(eq(taskId), eq(STATE_VERSION), anyString()))
                .thenReturn(Optional.empty());
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication =
                ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of());
        ScheduleRunState runState = new ScheduleRunState();

        newService(runState, registry).runOnce(taskId);

        verify(executor, never()).runTaskAsync(anyLong(), any(), any(), any());
        assertThat(runState.get(taskId)).isNull();
        assertThat(ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow().isDrained())
                .isTrue();
    }

    @Test
    @DisplayName("runOnce：queue 写入结果不确定时按预生成 token 收敛 claim")
    void runOnceRecoversUncertainDurableQueueClaim() {
        long taskId = 44L;
        ScheduledTask task = task(taskId);
        when(store.findById(taskId)).thenReturn(task);
        AtomicReference<String> claimToken = new AtomicReference<>();
        when(store.tryQueueNow(eq(taskId), eq(STATE_VERSION), anyString()))
                .thenAnswer(invocation -> {
                    claimToken.set(invocation.getArgument(2));
                    throw new IllegalStateException("queue write failed");
                });
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication =
                ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of());
        ScheduleRunState runState = new ScheduleRunState();

        assertThatThrownBy(() -> newService(runState, registry).runOnce(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("queue write failed");

        verify(executor).releaseClaim(taskId, claimToken.get(), task.nextRunTime());
        assertThat(runState.get(taskId)).isNull();
        assertThat(ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow().isDrained())
                .isTrue();
    }

    @Test
    @DisplayName("runOnce：queue 结果不确定且抛出 AssertionError 时按同 token 清理数据库与内存并原样抛出")
    void runOncePreservesErrorWhileRecoveringUncertainQueueClaim() {
        long taskId = 45L;
        ScheduledTask task = task(taskId);
        AssertionError queueFailure = new AssertionError("queue write uncertain");
        AtomicReference<String> claimToken = new AtomicReference<>();
        when(store.findById(taskId)).thenReturn(task);
        when(store.tryQueueNow(eq(taskId), eq(STATE_VERSION), anyString()))
                .thenAnswer(invocation -> {
                    claimToken.set(invocation.getArgument(2));
                    throw queueFailure;
                });
        FakeScheduleCapabilityAccess registry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication =
                ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of());
        ScheduleRunState runState = new ScheduleRunState();

        assertThatThrownBy(() -> newService(runState, registry).runOnce(taskId))
                .isSameAs(queueFailure);

        verify(executor).releaseClaim(taskId, claimToken.get(), task.nextRunTime());
        assertThat(runState.get(taskId)).isNull();
        assertThat(ScheduleCapabilityTestFixture.withdraw(registry, publication).orElseThrow().isDrained())
                .isTrue();
    }

    @Test
    @DisplayName("authorizeCookie：与当前 secret 相同时在探活和写入前拒绝")
    void authorizeCookieRejectedWhenUnchanged() throws Exception {
        String cookie = "PHPSESSID=12345_abc; other=x";
        stubBindingIdentity();
        when(store.findById(5L)).thenReturn(task(
                5L, true, null, ScheduleSuspendReason.CREDENTIAL,
                "PIXIV_COOKIE_INVALID", null, "12345", EMPTY_POLICY_STATE, true));
        when(store.findCredentialSecret(
                5L, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID))
                .thenReturn(cookie);

        assertThatThrownBy(() -> newService().bindCredential(
                5L, "  " + cookie + "  ", ACTIVATION_TOKEN))
                .isInstanceOf(LocalizedException.class);

        verify(credentialBindingLease, never()).probe(anyString());
        verify(store, never()).bindCredential(
                anyLong(), anyLong(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：探活成功后以 CAS 绑定凭证、保留策略状态并恢复凭证挂起")
    void authorizeCookieBindsWithCasAndResumesCredentialSuspension() throws Exception {
        String cookie = "PHPSESSID=12345_new; other=x";
        stubBinding(cleanBinding("12345"));
        String policyState =
                "{\"schema\":\"pixiv.schedule.credential-policy-state\",\"version\":1,"
                        + "\"futureField\":\"keep\"}";
        ScheduledTask current = task(
                6L, true, null, ScheduleSuspendReason.CREDENTIAL,
                "PIXIV_COOKIE_INVALID", null, "12345", policyState, true);
        when(store.findById(6L)).thenReturn(current);
        when(store.findCredentialSecret(
                6L, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID))
                .thenReturn("PHPSESSID=12345_old; other=x");
        when(store.bindCredential(
                eq(6L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("12345"), eq(policyState), eq(cookie),
                eq("scheduled-task:6:credential"), anyLong()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));
        when(store.resume(
                eq(6L), eq(STATE_VERSION + 1), eq(ScheduleSuspendReason.CREDENTIAL),
                eq("PIXIV_COOKIE_INVALID"), anyLong()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 2));

        newService().bindCredential(6L, cookie, ACTIVATION_TOKEN);

        verify(credentialBindingLease).probe(cookie);
        verify(store).bindCredential(
                eq(6L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("12345"), eq(policyState), eq(cookie),
                eq("scheduled-task:6:credential"), anyLong());
        verify(store).resume(
                eq(6L), eq(STATE_VERSION + 1), eq(ScheduleSuspendReason.CREDENTIAL),
                eq("PIXIV_COOKIE_INVALID"), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：同一策略切换账号时不继承旧账号策略状态")
    void authorizeCookieResetsPolicyStateWhenAccountChanges() throws Exception {
        String cookie = "PHPSESSID=67890_new; other=x";
        stubBinding(cleanBinding("67890"));
        String oldAccountState =
                "{\"schema\":\"pixiv.schedule.credential-policy-state\",\"version\":1,"
                        + "\"acknowledgedWarningTime\":123456}";
        ScheduledTask current = task(
                61L, true, null, null, null, null,
                "12345", oldAccountState, true);
        when(store.findById(61L)).thenReturn(current);
        when(store.findCredentialSecret(
                61L, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID))
                .thenReturn("PHPSESSID=12345_old; other=x");
        when(store.bindCredential(
                eq(61L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("67890"), eq(EMPTY_POLICY_STATE), eq(cookie),
                eq("scheduled-task:61:credential"), anyLong()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().bindCredential(61L, cookie, ACTIVATION_TOKEN);

        verify(store).bindCredential(
                eq(61L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("67890"), eq(EMPTY_POLICY_STATE), eq(cookie),
                eq("scheduled-task:61:credential"), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：首次授权生成版本化空策略状态")
    void authorizeCookieCreatesVersionedPolicyStateOnFirstBinding() throws Exception {
        String cookie = "PHPSESSID=999_abc";
        stubBinding(cleanBinding("999"));
        when(store.findById(7L)).thenReturn(task(7L));
        when(store.bindCredential(
                eq(7L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("999"), eq(EMPTY_POLICY_STATE), eq(cookie),
                eq("scheduled-task:7:credential"), anyLong()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().bindCredential(7L, cookie, ACTIVATION_TOKEN);

        verify(store).bindCredential(
                eq(7L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("999"), eq(EMPTY_POLICY_STATE), eq(cookie),
                eq("scheduled-task:7:credential"), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：探活判定凭证失效时不执行绑定")
    void authorizeCookieRejectsDeadCredentialProbe() throws Exception {
        String cookie = "PHPSESSID=999_dead";
        stubBinding(new ScheduledCredentialBindResult(
                ScheduledCredentialProbeResult.invalid("fixture.invalid"),
                EMPTY_POLICY_STATE, null));
        when(store.findById(8L)).thenReturn(task(8L));

        assertThatThrownBy(() -> newService().bindCredential(
                8L, cookie, ACTIVATION_TOKEN))
                .isInstanceOf(LocalizedException.class);

        verify(store, never()).bindCredential(
                anyLong(), anyLong(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：探活发现警告时绑定后以新版本挂起策略")
    void authorizeCookieSuspendsWarnedCredentialAfterBinding() throws Exception {
        String cookie = "PHPSESSID=999_warned";
        stubBinding(warnedBinding("999", 999_000L, "safe excerpt"));
        when(store.findById(9L)).thenReturn(task(9L));
        when(store.bindCredential(
                eq(9L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("999"), eq(EMPTY_POLICY_STATE), eq(cookie),
                eq("scheduled-task:9:credential"), anyLong()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));
        when(store.suspend(
                eq(9L), eq(STATE_VERSION + 1), eq(ScheduleSuspendReason.POLICY),
                eq("PIXIV_OVERUSE"), anyString()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 2));

        newService().bindCredential(9L, cookie, ACTIVATION_TOKEN);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(store).suspend(
                eq(9L), eq(STATE_VERSION + 1), eq(ScheduleSuspendReason.POLICY),
                eq("PIXIV_OVERUSE"), detail.capture());
        assertThat(objectMapper.readTree(detail.getValue()).path("modifiedAt").asText())
                .isEqualTo("999000");
        assertThat(objectMapper.readTree(detail.getValue()).path("excerpt").asText())
                .isEqualTo("safe excerpt");
        assertThat(detail.getValue()).doesNotContain(cookie);
    }

    @Test
    @DisplayName("authorizeCookie：探活后任务版本变化时不写入凭证")
    void authorizeCookieRejectsStateChangeObservedAfterProbe() throws Exception {
        String cookie = "PHPSESSID=999_race";
        stubBinding(cleanBinding("999"));
        ScheduledTask beforeProbe = task(10L);
        ScheduledTask changed = new ScheduledTask(
                beforeProbe.id(), beforeProbe.name(), beforeProbe.enabled(), beforeProbe.sourceType(),
                beforeProbe.sourceOwnerPluginId(), beforeProbe.definitionSchema(),
                beforeProbe.definitionVersion(), beforeProbe.definitionJson(), beforeProbe.presentationJson(),
                beforeProbe.triggerKind(), beforeProbe.intervalMinutes(), beforeProbe.cronExpr(),
                beforeProbe.proxySnapshot(), beforeProbe.nextRunTime(), beforeProbe.lastRunTime(),
                beforeProbe.checkpointSchema(), beforeProbe.checkpointVersion(), beforeProbe.checkpointJson(),
                beforeProbe.storageVersion(), beforeProbe.runState(), beforeProbe.runClaimToken(),
                beforeProbe.lastOutcome(), beforeProbe.outcomeCode(), beforeProbe.outcomeMessage(),
                beforeProbe.suspendReason(), beforeProbe.suspendCode(), beforeProbe.suspendDetailJson(),
                STATE_VERSION + 1, beforeProbe.credentialPolicyOwnerPluginId(),
                beforeProbe.credentialPolicyId(), beforeProbe.credentialAccountKey(),
                beforeProbe.credentialPolicyStateJson(), beforeProbe.credentialSecretReference(),
                beforeProbe.createdTime());
        when(store.findById(10L)).thenReturn(beforeProbe, changed);

        assertThatThrownBy(() -> newService().bindCredential(
                10L, cookie, ACTIVATION_TOKEN))
                .isInstanceOf(LocalizedException.class);

        verify(store, never()).bindCredential(
                anyLong(), anyLong(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：按复合租约解析的非 Pixiv policy 身份执行 CAS")
    void authorizeCookieUsesResolvedNonPixivPolicyIdentity() throws Exception {
        String cookie = "opaque-external-secret";
        String owner = "external-credential-plugin";
        String policyId = "external:credential";
        String policyState = "{\"schema\":\"external.state\",\"version\":1}";
        stubBinding(new ScheduledCredentialBindResult(
                ScheduledCredentialProbeResult.valid("external-account"),
                policyState, null));
        when(credentialBindingLease.policyOwnerPluginId()).thenReturn(owner);
        when(credentialBindingLease.policyId()).thenReturn(policyId);
        when(store.findById(11L)).thenReturn(task(11L));
        when(store.bindCredential(
                eq(11L), eq(STATE_VERSION), eq(owner), eq(policyId),
                eq("external-account"), eq(policyState), eq(cookie),
                eq("scheduled-task:11:credential"), anyLong()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().bindCredential(11L, cookie, ACTIVATION_TOKEN);

        verify(store, times(2)).findCredentialSecret(11L, owner, policyId);
        verify(store).bindCredential(
                eq(11L), eq(STATE_VERSION), eq(owner), eq(policyId),
                eq("external-account"), eq(policyState), eq(cookie),
                eq("scheduled-task:11:credential"), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：来源 publication 已切换时返回冲突且不探活凭证")
    void authorizeCookieMapsPublicationChangeToConflictBeforeProbe() throws Exception {
        String cookie = "PHPSESSID=999_stale";
        when(store.findById(12L)).thenReturn(task(12L));
        when(scheduleExecutionEngine.prepareCredentialBinding(
                any(), eq(ACTIVATION_TOKEN)))
                .thenThrow(new ScheduleSourcePublicationChangedException(SOURCE_TYPE));

        assertThatThrownBy(() -> newService().bindCredential(
                12L, cookie, ACTIVATION_TOKEN))
                .isInstanceOfSatisfying(LocalizedException.class, failure ->
                        assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT));

        verify(credentialBindingLease, never()).probe(anyString());
        verify(store, never()).bindCredential(
                anyLong(), anyLong(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("revokeCookie：以版本和 policy 身份删除凭证聚合")
    void revokeCookieRemovesCredentialWithCas() {
        when(store.findById(20L)).thenReturn(task(
                20L, true, null, null, null, null,
                "12345", EMPTY_POLICY_STATE, true));
        when(store.removeCredential(
                20L, STATE_VERSION, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().revokeCredential(20L);

        verify(store).removeCredential(
                20L, STATE_VERSION, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID);
    }

    @Test
    @DisplayName("revokeCookie：按任务记录的外部 owner 与 policy 身份执行 CAS 删除")
    void revokeCookieUsesPersistedExternalCredentialIdentity() {
        String owner = "example.schedule-source";
        String policyId = "credential.policy.v1";
        when(store.findById(21L)).thenReturn(task(
                21L, true, null, null, null, null,
                "external-account", "{}", true, owner, policyId));
        when(store.removeCredential(21L, STATE_VERSION, owner, policyId))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().revokeCredential(21L);

        verify(store).removeCredential(21L, STATE_VERSION, owner, policyId);
        verify(store, never()).removeCredential(
                21L, STATE_VERSION, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID);
    }

    @Test
    @DisplayName("updateProxy：合法 host:port 去空白后以版本 CAS 写入")
    void updateProxySavesValidHostPort() {
        when(store.findById(11L)).thenReturn(task(11L));
        when(store.updateProxy(11L, STATE_VERSION, "127.0.0.1:7890"))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().updateProxy(11L, " 127.0.0.1:7890 ");

        verify(store).updateProxy(11L, STATE_VERSION, "127.0.0.1:7890");
    }

    @Test
    @DisplayName("updateProxy：非法格式直接拒绝且不写库")
    void updateProxyRejectsInvalidFormat() {
        when(store.findById(12L)).thenReturn(task(12L));
        ScheduleService service = newService();

        assertThatThrownBy(() -> service.updateProxy(12L, "127.0.0.1"))
                .isInstanceOf(LocalizedException.class);
        assertThatThrownBy(() -> service.updateProxy(12L, "127.0.0.1:0"))
                .isInstanceOf(LocalizedException.class);
        assertThatThrownBy(() -> service.updateProxy(12L, "http://127.0.0.1:7890"))
                .isInstanceOf(LocalizedException.class);
        assertThatThrownBy(() -> service.updateProxy(12L, "user:pass@127.0.0.1:7890"))
                .isInstanceOf(LocalizedException.class);

        verify(store, never()).updateProxy(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("updateProxy：空值以版本 CAS 清除单独代理")
    void updateProxyClearsWhenBlank() {
        when(store.findById(13L)).thenReturn(task(13L));
        when(store.updateProxy(13L, STATE_VERSION, null))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().updateProxy(13L, "  ");

        verify(store).updateProxy(13L, STATE_VERSION, null);
    }

    @Test
    @DisplayName("resume：非手动挂起任务拒绝恢复")
    void resumeRejectedWhenNotPaused() {
        when(store.findById(14L)).thenReturn(task(14L));

        assertThatThrownBy(() -> newService().resume(14L))
                .isInstanceOf(LocalizedException.class);

        verify(store, never()).resume(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("resume：按 reason、code 与 stateVersion 精确恢复手动挂起")
    void resumeManualSuspensionWithCas() {
        when(store.findById(15L)).thenReturn(task(
                15L, true, null, ScheduleSuspendReason.MANUAL,
                "ADMIN_PAUSE", null, null, null, false));
        when(store.resume(
                eq(15L), eq(STATE_VERSION), eq(ScheduleSuspendReason.MANUAL),
                eq("ADMIN_PAUSE"), anyLong()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().resume(15L);

        verify(store).resume(
                eq(15L), eq(STATE_VERSION), eq(ScheduleSuspendReason.MANUAL),
                eq("ADMIN_PAUSE"), anyLong());
    }

    @Test
    @DisplayName("queue：按任意作品类型投影中性展示结果并只读取显式开放的实时状态")
    void queueProjectsNeutralLiveStatusForArbitraryWorkType() {
        String workType = "third.party.text";
        ScheduledWorkKey liveKey = new ScheduledWorkKey(workType, "opaque/111");
        ScheduledWorkKey staticKey = new ScheduledWorkKey(workType, "opaque/222");
        when(store.findById(1L)).thenReturn(task(1L));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
        discover(run, queueWork(workType, liveKey.id(), "raw-title"));
        run.markResult(
                liveKey,
                new ScheduledWorkResult(
                        ScheduledWorkResult.Outcome.COMPLETED,
                        "vendor.completed",
                        Map.of("rating", "vendor-safe"),
                        true),
                ScheduleRunQueue.STATUS_DOWNLOADED,
                null);
        discover(run, queueWork(workType, staticKey.id(), null));
        run.markResult(
                staticKey,
                new ScheduledWorkResult(
                        ScheduledWorkResult.Outcome.SKIPPED,
                        "vendor.skipped",
                        Map.of("autoTranslateSubmitted", "true"),
                        false),
                ScheduleRunQueue.STATUS_SKIPPED_FILTER,
                "vendor.skipped");
        when(runQueue.get(1L)).thenReturn(run);

        ScheduledWorkExecutor workExecutor =
                org.mockito.Mockito.mock(ScheduledWorkExecutor.class);
        when(workExecutor.status(liveKey)).thenReturn(Map.of(
                "phase", "VENDOR_CUSTOM",
                "elapsedSeconds", "-5"));
        @SuppressWarnings("unchecked")
        ScheduleCapabilityLease<ScheduledWorkExecutor> lease =
                org.mockito.Mockito.mock(ScheduleCapabilityLease.class);
        when(lease.owner()).thenReturn(STATUS_OWNER);
        when(lease.publicationId()).thenReturn(STATUS_PUBLICATION_ID);
        when(lease.capability()).thenReturn(workExecutor);
        ScheduleCapabilityAccess capabilityRegistry =
                org.mockito.Mockito.mock(ScheduleCapabilityAccess.class);
        doAnswer(ignored -> Optional.of(lease))
                .when(capabilityRegistry).prepareWorkExecutor(workType);
        when(capabilityRegistry.activate(lease)).thenReturn(true);

        List<ScheduleQueueView.Item> items =
                newService(new ScheduleRunState(), capabilityRegistry).queue(1L).items();

        ScheduleQueueView.Item live = items.stream()
                .filter(item -> item.workId().equals(liveKey.id()))
                .findFirst()
                .orElseThrow();
        ScheduleQueueView.Item notOptedIn = items.stream()
                .filter(item -> item.workId().equals(staticKey.id()))
                .findFirst()
                .orElseThrow();
        assertThat(live.workType()).isEqualTo(workType);
        assertThat(live.title()).isEqualTo("raw-title");
        assertThat(live.author()).isEqualTo("raw-author");
        assertThat(live.presentationAttributes()).containsEntry("sourceHint", "raw");
        assertThat(live.resultAttributes()).containsEntry("rating", "vendor-safe");
        assertThat(live.liveStatus()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "phase", "VENDOR_CUSTOM",
                "elapsedSeconds", "-5"));
        assertThat(notOptedIn.resultAttributes())
                .containsEntry("autoTranslateSubmitted", "true");
        assertThat(notOptedIn.liveStatus()).isEmpty();
        verify(capabilityRegistry, times(1)).prepareWorkExecutor(workType);
        verify(workExecutor).status(liveKey);
        verify(workExecutor, never()).status(staticKey);
        verify(lease).close();
    }

    @Test
    @DisplayName("queue：实时状态整张 Map 经过数量大小控制字符与凭证守卫")
    void queueSanitizesWholeLiveStatusMap() {
        String workType = "third.party.secure";
        ScheduledWorkKey key = new ScheduledWorkKey(workType, "opaque/555");
        when(store.findById(4L)).thenReturn(task(
                4L, true, null, null, null, null,
                "account-1", EMPTY_POLICY_STATE, true));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
        discover(run, queueWork(workType, key.id(), null));
        run.markResult(
                key,
                new ScheduledWorkResult(
                        ScheduledWorkResult.Outcome.COMPLETED,
                        "vendor.completed",
                        Map.of(),
                        true),
                ScheduleRunQueue.STATUS_DOWNLOADED,
                null);
        when(runQueue.get(4L)).thenReturn(run);
        AtomicReference<Map<String, String>> status = new AtomicReference<>(Map.of(
                "phase", "CUSTOM",
                "elapsedSeconds", "5"));
        ScheduledWorkExecutor workExecutor = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return workType;
            }

            @Override
            public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }

            @Override
            public Map<String, String> status(ScheduledWorkKey ignored) {
                return status.get();
            }
        };
        FakeScheduleCapabilityAccess capabilityRegistry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = ScheduleCapabilityTestFixture.publish(
                capabilityRegistry,
                STATUS_OWNER,
                List.of(),
                List.of(),
                List.of(workExecutor));
        ScheduleService service = newService(new ScheduleRunState(), capabilityRegistry);

        assertThat(service.queue(4L).items().get(0).liveStatus())
                .containsExactlyInAnyOrderEntriesOf(status.get());
        status.set(Map.of("apiKey", "not-secret-looking"));
        assertThat(service.queue(4L).items().get(0).liveStatus()).isEmpty();
        status.set(Map.of("phase", "Cookie: PHPSESSID=secret"));
        assertThat(service.queue(4L).items().get(0).liveStatus()).isEmpty();
        status.set(Map.of("phase", "line-one\nline-two"));
        assertThat(service.queue(4L).items().get(0).liveStatus()).isEmpty();
        status.set(Map.of("phase", "X".repeat(257)));
        assertThat(service.queue(4L).items().get(0).liveStatus()).isEmpty();
        status.set(Map.of("not valid", "value"));
        assertThat(service.queue(4L).items().get(0).liveStatus()).isEmpty();
        Map<String, String> tooMany = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 17; index++) {
            tooMany.put("field" + index, "value");
        }
        status.set(tooMany);
        assertThat(service.queue(4L).items().get(0).liveStatus()).isEmpty();
        Map<String, String> tooLargeTogether = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 16; index++) {
            tooLargeTogether.put("field" + index, "X".repeat(256));
        }
        status.set(tooLargeTogether);
        assertThat(service.queue(4L).items().get(0).liveStatus()).isEmpty();
        verify(store, never()).findCredentialSecret(
                anyLong(), anyString(), anyString());

        assertThat(ScheduleCapabilityTestFixture.withdraw(capabilityRegistry, publication)
                .orElseThrow().isDrained()).isTrue();
    }

    @Test
    @DisplayName("queue：实时状态受单次响应聚合字节预算约束并标记截断")
    void queueBoundsAggregateLiveStatusResponse() {
        String workType = "third.party.large-status";
        when(store.findById(41L)).thenReturn(task(41L));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
        for (int index = 0; index < 130; index++) {
            ScheduledWork work = queueWork(
                    workType,
                    "opaque/" + index,
                    "title-" + index);
            discover(run, work);
            run.markResult(
                    work.key(),
                    new ScheduledWorkResult(
                            ScheduledWorkResult.Outcome.COMPLETED,
                            "vendor.completed",
                            Map.of(),
                            true),
                    ScheduleRunQueue.STATUS_DOWNLOADED,
                    null);
        }
        assertThat(run.truncated()).isFalse();
        when(runQueue.get(41L)).thenReturn(run);

        Map<String, String> largeStatus = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 16; index++) {
            largeStatus.put("field" + index, "X".repeat(248));
        }
        java.util.concurrent.atomic.AtomicInteger statusCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        ScheduledWorkExecutor workExecutor = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return workType;
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork work,
                    ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }

            @Override
            public Map<String, String> status(ScheduledWorkKey ignored) {
                statusCalls.incrementAndGet();
                return largeStatus;
            }
        };
        FakeScheduleCapabilityAccess capabilityRegistry =
                new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication =
                ScheduleCapabilityTestFixture.publish(
                        capabilityRegistry,
                        STATUS_OWNER,
                        List.of(),
                        List.of(),
                        List.of(workExecutor));

        ScheduleQueueView view =
                newService(new ScheduleRunState(), capabilityRegistry).queue(41L);

        long projected = view.items().stream()
                .filter(item -> !item.liveStatus().isEmpty())
                .count();
        assertThat(view.truncated()).isTrue();
        assertThat(projected).isPositive().isLessThan(view.items().size());
        assertThat(statusCalls.get()).isEqualTo(projected + 1);
        assertThat(ScheduleCapabilityTestFixture.withdraw(
                capabilityRegistry, publication).orElseThrow().isDrained())
                .isTrue();
    }

    @Test
    @DisplayName("queue：执行器缺席时保留中性队列并省略实时状态")
    void queueSkipsLiveStatusWhenCapabilityIsAbsent() {
        String workType = "third.party.absent";
        ScheduledWorkKey key = new ScheduledWorkKey(workType, "opaque/333");
        when(store.findById(2L)).thenReturn(task(2L));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
        discover(run, queueWork(workType, key.id(), "offline-title"));
        run.markResult(
                key,
                new ScheduledWorkResult(
                        ScheduledWorkResult.Outcome.COMPLETED,
                        "vendor.completed",
                        Map.of("vendorState", "done"),
                        true),
                ScheduleRunQueue.STATUS_DOWNLOADED,
                null);
        when(runQueue.get(2L)).thenReturn(run);

        ScheduleQueueView.Item item = newService().queue(2L).items().get(0);

        assertThat(item.workId()).isEqualTo(key.id());
        assertThat(item.workType()).isEqualTo(workType);
        assertThat(item.title()).isEqualTo("offline-title");
        assertThat(item.resultAttributes()).containsEntry("vendorState", "done");
        assertThat(item.liveStatus()).isEmpty();
    }

    @Test
    @DisplayName("queue：同 owner 重新发布后不得用新执行器解释旧队列身份")
    void queueRejectsReplacementPublicationForOldLiveStatusResult() {
        String workType = "third.party.replaced";
        ScheduledWorkKey key = new ScheduledWorkKey(workType, "opaque/old-owner-id");
        when(store.findById(42L)).thenReturn(task(42L));
        FakeScheduleCapabilityAccess capabilityRegistry =
                new FakeScheduleCapabilityAccess();
        ScheduledWorkExecutor oldExecutor =
                org.mockito.Mockito.mock(ScheduledWorkExecutor.class);
        when(oldExecutor.workType()).thenReturn(workType);
        FakeScheduleCapabilityAccess.Publication oldPublication =
                ScheduleCapabilityTestFixture.publish(
                        capabilityRegistry,
                        STATUS_OWNER,
                        List.of(),
                        List.of(),
                        List.of(oldExecutor));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
        run.discovered(
                queueWork(workType, key.id(), "old-title"),
                oldPublication.owner(),
                oldPublication.publicationId());
        run.markResult(
                key,
                new ScheduledWorkResult(
                        ScheduledWorkResult.Outcome.COMPLETED,
                        "vendor.completed",
                        Map.of(),
                        true),
                ScheduleRunQueue.STATUS_DOWNLOADED,
                null);
        when(runQueue.get(42L)).thenReturn(run);
        assertThat(ScheduleCapabilityTestFixture.withdraw(
                capabilityRegistry, oldPublication).orElseThrow().isDrained())
                .isTrue();

        ScheduledWorkExecutor replacement =
                org.mockito.Mockito.mock(ScheduledWorkExecutor.class);
        when(replacement.workType()).thenReturn(workType);
        FakeScheduleCapabilityAccess.Publication replacementPublication =
                ScheduleCapabilityTestFixture.publish(
                        capabilityRegistry,
                        STATUS_OWNER,
                        List.of(),
                        List.of(),
                        List.of(replacement));

        ScheduleQueueView.Item item =
                newService(new ScheduleRunState(), capabilityRegistry)
                        .queue(42L).items().get(0);

        assertThat(item.workId()).isEqualTo(key.id());
        assertThat(item.liveStatus()).isEmpty();
        verify(replacement, never()).status(any());
        assertThat(ScheduleCapabilityTestFixture.withdraw(
                capabilityRegistry, replacementPublication).orElseThrow().isDrained())
                .isTrue();
    }

    @Test
    @DisplayName("queue：执行器租约失活时关闭租约且不调用插件")
    void queueSkipsLiveStatusWhenCapabilityLeaseIsInactive() {
        String workType = "third.party.inactive";
        ScheduledWorkKey key = new ScheduledWorkKey(workType, "opaque/334");
        when(store.findById(5L)).thenReturn(task(5L));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
        discover(run, queueWork(workType, key.id(), "inactive-title"));
        run.markResult(
                key,
                new ScheduledWorkResult(
                        ScheduledWorkResult.Outcome.COMPLETED,
                        "vendor.completed",
                        Map.of(),
                        true),
                ScheduleRunQueue.STATUS_DOWNLOADED,
                null);
        when(runQueue.get(5L)).thenReturn(run);
        ScheduledWorkExecutor workExecutor =
                org.mockito.Mockito.mock(ScheduledWorkExecutor.class);
        @SuppressWarnings("unchecked")
        ScheduleCapabilityLease<ScheduledWorkExecutor> lease =
                org.mockito.Mockito.mock(ScheduleCapabilityLease.class);
        when(lease.owner()).thenReturn(STATUS_OWNER);
        when(lease.publicationId()).thenReturn(STATUS_PUBLICATION_ID);
        ScheduleCapabilityAccess capabilityRegistry =
                org.mockito.Mockito.mock(ScheduleCapabilityAccess.class);
        doAnswer(ignored -> Optional.of(lease))
                .when(capabilityRegistry).prepareWorkExecutor(workType);

        ScheduleQueueView.Item item =
                newService(new ScheduleRunState(), capabilityRegistry).queue(5L).items().get(0);

        assertThat(item.workId()).isEqualTo(key.id());
        assertThat(item.liveStatus()).isEmpty();
        verify(capabilityRegistry).activate(lease);
        verify(workExecutor, never()).status(any());
        verify(lease).close();
    }

    @Test
    @DisplayName("queue：插件实时状态异常时隔离失败并关闭能力租约")
    void queueSurvivesPluginLiveStatusFailure() {
        String workType = "third.party.failure";
        ScheduledWorkKey key = new ScheduledWorkKey(workType, "opaque/444");
        when(store.findById(3L)).thenReturn(task(3L));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun();
        discover(run, queueWork(workType, key.id(), null));
        run.markResult(
                key,
                new ScheduledWorkResult(
                        ScheduledWorkResult.Outcome.COMPLETED,
                        "vendor.completed",
                        Map.of(),
                        true),
                ScheduleRunQueue.STATUS_DOWNLOADED,
                null);
        when(runQueue.get(3L)).thenReturn(run);
        ScheduledWorkExecutor workExecutor =
                org.mockito.Mockito.mock(ScheduledWorkExecutor.class);
        when(workExecutor.workType()).thenReturn(workType);
        when(workExecutor.status(key))
                .thenThrow(new IllegalStateException("plugin child failure"));
        FakeScheduleCapabilityAccess capabilityRegistry = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = ScheduleCapabilityTestFixture.publish(
                capabilityRegistry,
                STATUS_OWNER,
                List.of(),
                List.of(),
                List.of(workExecutor));

        ScheduleQueueView.Item item =
                newService(new ScheduleRunState(), capabilityRegistry).queue(3L).items().get(0);

        assertThat(item.workId()).isEqualTo(key.id());
        assertThat(item.liveStatus()).isEmpty();
        assertThat(ScheduleCapabilityTestFixture.withdraw(capabilityRegistry, publication)
                .orElseThrow().isDrained()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ScheduleSuspendReason.class, names = {
            "SOURCE_UNAVAILABLE", "EXECUTOR_UNAVAILABLE", "QUIESCED", "MIGRATION_ERROR"
    })
    @DisplayName("任务视图保留不可用、静默停机与迁移失败的精确状态")
    void taskViewPreservesOperationalSuspensionReason(ScheduleSuspendReason reason) {
        ScheduledTask suspended = task(
                19L, true, null, reason, "fixture.code", "{}",
                null, null, false);

        ScheduleTaskView view = ScheduleTaskView.of(
                suspended, null, null,
                top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation.empty(),
                false, null,
                ScheduleCredentialPolicyView.unavailable(null, null, null, false));

        assertThat(view.lastStatus()).isEqualTo(reason.name());
        assertThat(view.suspendReason()).isEqualTo(reason.name());
    }

    @Test
    @DisplayName("clearPending：以任务 stateVersion 和原始复合身份执行 CAS 清除")
    void clearPendingUsesTaskVersionAndOpaqueCompositeIdentity() {
        String workType = "novel/自定义?'\"#_%";
        String workId = "001/路径?mode=\"人工\"&x='y'#_%";
        when(store.findById(17L)).thenReturn(task(17L));
        when(store.clearPendingWork(17L, STATE_VERSION, workType, workId))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().clearPending(17L, workType, workId);

        verify(store).clearPendingWork(17L, STATE_VERSION, workType, workId);
        verify(store, never()).deletePendingWork(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("clearPending：CAS 失配时报告并发变化")
    void clearPendingRejectsConcurrentStateChange() {
        when(store.findById(18L)).thenReturn(task(18L));
        when(store.clearPendingWork(18L, STATE_VERSION, "novel", "100"))
                .thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> newService().clearPending(18L, "novel", "100"))
                .isInstanceOf(LocalizedException.class);
    }

    @Test
    @DisplayName("结构性操作在 durable 运行认领存在时被拒绝")
    void deleteRejectedWhenDurablyBusy() {
        when(store.findById(16L)).thenReturn(task(
                16L, true,
                top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.RUNNING,
                null, null, null, null, null, false));

        assertThatThrownBy(() -> newService().delete(16L))
                .isInstanceOf(LocalizedException.class);

        verify(store, never()).deleteAggregate(anyLong(), anyLong());
    }

    @Test
    @DisplayName("来源兼容适配器从中性快照解析当前凭证策略 publication")
    void currentCredentialPolicyActionResolvesStampedPublication() {
        String ownerPluginId = "third-party";
        String policyId = "third-party-credential";
        ScheduleCapabilityOwner owner =
                new ScheduleCapabilityOwner(ownerPluginId, ownerPluginId, 2L);
        ScheduledCredentialPolicy policy =
                org.mockito.Mockito.mock(ScheduledCredentialPolicy.class);
        when(policy.policyId()).thenReturn(policyId);
        FakeScheduleCapabilityAccess capabilityRegistry =
                new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication =
                ScheduleCapabilityTestFixture.publish(
                        capabilityRegistry,
                        ScheduleCapabilityTestFixture.bundle(
                                owner, List.of(), List.of(), List.of(),
                                List.of(policy), List.of()));
        ScheduleCredentialService credentialService =
                org.mockito.Mockito.mock(ScheduleCredentialService.class);
        ScheduleService service = new ScheduleService(
                store, executor, new ScheduleConfig(), new ScheduleRunState(), runQueue,
                objectMapper, credentialService, transactionTemplate,
                capabilityRegistry, new ScheduleHostIdentity(DownloadWorkbenchPlugin.ID));

        service.applyCurrentCredentialPolicyAction(
                ownerPluginId, policyId, "account-42", "resume", Map.of("delay", "60"));

        verify(credentialService).applyAccountAction(
                ownerPluginId, policyId, publication.publicationId(),
                "account-42", "resume", Map.of("delay", "60"));
    }

    @Test
    @DisplayName("精确凭证策略动作缺 publication 时返回受控并发错误")
    void credentialPolicyActionRejectsMissingPublication() {
        ScheduleCredentialService credentialService =
                org.mockito.Mockito.mock(ScheduleCredentialService.class);
        ScheduleService service = new ScheduleService(
                store, executor, new ScheduleConfig(), new ScheduleRunState(), runQueue,
                objectMapper, credentialService, transactionTemplate,
                emptyCapabilityRegistry(), new ScheduleHostIdentity(DownloadWorkbenchPlugin.ID));
        ScheduleCredentialPolicyActionRequest request =
                new ScheduleCredentialPolicyActionRequest();
        request.setOwnerPluginId("third-party");
        request.setPolicyId("third-party-credential");
        request.setAccountKey("account-42");
        request.setActionId("resume");

        assertThatThrownBy(() -> service.applyCredentialPolicyAction(request))
                .isInstanceOf(LocalizedException.class);

        verify(credentialService, never()).applyAccountAction(
                anyString(), anyString(), anyLong(), anyString(), anyString(), any());
    }
}
