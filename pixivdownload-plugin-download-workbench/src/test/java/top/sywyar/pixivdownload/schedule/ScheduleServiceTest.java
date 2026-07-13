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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleSingleCapabilityLease;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkTranslateStatus;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.schedule.dto.AccountResumeRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.List;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleService canonical 状态与凭证操作")
class ScheduleServiceTest {

    private static final long STATE_VERSION = 7L;
    private static final String SOURCE_TYPE = "user-new";
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
    private OveruseWarningService overuseWarningService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PixivSchedulePersistenceCodec persistenceCodec =
            new PixivSchedulePersistenceCodec(objectMapper);
    private final TransactionTemplate transactionTemplate =
            new TransactionTemplate(NO_OP_TRANSACTION_MANAGER);

    /** 默认空统一能力注册中心（多数用例不触发翻译状态叠加）。 */
    private static ScheduleCapabilityRegistry emptyCapabilityRegistry() {
        return new ScheduleCapabilityRegistry();
    }

    private ScheduleService newService() {
        return newService(new ScheduleRunState(), emptyCapabilityRegistry());
    }

    private ScheduleService newService(ScheduleRunState runState,
                                       ScheduleCapabilityRegistry capabilityRegistry) {
        return new ScheduleService(
                store, executor, new ScheduleConfig(), runState, runQueue,
                objectMapper, persistenceCodec, overuseWarningService,
                transactionTemplate, capabilityRegistry);
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
                credentialBound ? DownloadWorkbenchPlugin.ID : null,
                credentialBound ? PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID : null,
                credentialBound ? accountId : null,
                effectivePolicyState,
                credentialBound ? "scheduled-task:" + id + ":credential" : null,
                credentialBound ? 900L : null,
                0L);
    }

    @Test
    @DisplayName("parsePixivUserId：标准 PHPSESSID 取下划线前缀 userId")
    void parsesStandardPhpsessid() {
        assertThat(ScheduleService.parsePixivUserId("PHPSESSID=12345_abcdefghijklmnop; other=x"))
                .isEqualTo("12345");
    }

    @Test
    @DisplayName("parsePixivUserId：无 PHPSESSID、无下划线或前缀非数字时返回 null")
    void parsesNullForMalformed() {
        assertThat(ScheduleService.parsePixivUserId("foo=bar; baz=qux")).isNull();
        assertThat(ScheduleService.parsePixivUserId("PHPSESSID=abcdefonly")).isNull();
        assertThat(ScheduleService.parsePixivUserId("PHPSESSID=abc_def")).isNull();
        assertThat(ScheduleService.parsePixivUserId(null)).isNull();
    }

    @Test
    @DisplayName("账号恢复 ignore：以版本化策略状态确认警告并精确恢复策略挂起")
    void accountResumeIgnoreUpdatesPolicyStateAndResumesExactSuspension() {
        ScheduledTask paused = task(
                1L, true, null, ScheduleSuspendReason.POLICY, "PIXIV_OVERUSE",
                "{\"modifiedAt\":\"999000\"}", "12345", EMPTY_POLICY_STATE, true);
        when(store.findByCredentialAccount(
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                "12345"))
                .thenReturn(List.of(paused));
        when(store.updateCredentialPolicyState(
                eq(1L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq(EMPTY_POLICY_STATE), anyString(), anyLong()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));
        when(store.resumeByCredentialAccount(
                eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("12345"), eq(ScheduleSuspendReason.POLICY),
                eq("PIXIV_OVERUSE"), anyLong()))
                .thenReturn(1);
        AccountResumeRequest request = new AccountResumeRequest();
        request.setMode(AccountResumeRequest.MODE_IGNORE);

        newService().resumeAccount("12345", request);

        ArgumentCaptor<String> newPolicyState = ArgumentCaptor.forClass(String.class);
        verify(store).updateCredentialPolicyState(
                eq(1L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq(EMPTY_POLICY_STATE), newPolicyState.capture(), anyLong());
        assertThat(persistenceCodec.decodeAcknowledgedWarningTime(newPolicyState.getValue()))
                .isEqualTo(999000L);
        verify(store).resumeByCredentialAccount(
                eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("12345"), eq(ScheduleSuspendReason.POLICY),
                eq("PIXIV_OVERUSE"), anyLong());
    }

    @Test
    @DisplayName("账号恢复 defer：分钟数低于下限时拒绝且不执行账号恢复")
    void accountResumeDeferRejectsBelowMin() {
        ScheduledTask paused = task(
                1L, true, null, ScheduleSuspendReason.POLICY, "PIXIV_OVERUSE",
                "{\"modifiedAt\":\"999000\"}", "12345", EMPTY_POLICY_STATE, true);
        when(store.findByCredentialAccount(
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                "12345"))
                .thenReturn(List.of(paused));
        AccountResumeRequest request = new AccountResumeRequest();
        request.setMode(AccountResumeRequest.MODE_DEFER);
        request.setMinutes(30);

        assertThatThrownBy(() -> newService().resumeAccount("12345", request))
                .isInstanceOf(LocalizedException.class);

        verify(store, never()).resumeByCredentialAccount(
                anyString(), anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("账号恢复：凭证账号下无任务时拒绝")
    void accountResumeRejectsUnknownAccount() {
        when(store.findByCredentialAccount(
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                "nope"))
                .thenReturn(List.of());
        AccountResumeRequest request = new AccountResumeRequest();
        request.setMode(AccountResumeRequest.MODE_IGNORE);

        assertThatThrownBy(() -> newService().resumeAccount("nope", request))
                .isInstanceOf(LocalizedException.class);
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication =
                ScheduleCapabilityTestFixture.publishDownloadWorkbench(registry, List.of());
        ScheduleRunState runState = new ScheduleRunState();
        ScheduleService service = newService(runState, registry);
        AtomicReference<ScheduleRunState.Claim> transferredClaim = new AtomicReference<>();
        AtomicReference<ScheduleRunToken> transferredToken = new AtomicReference<>();
        AtomicReference<ScheduleSingleCapabilityLease<ScheduleCapabilityOwner>> transferredLease =
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
        ScheduleGenerationDrain drain =
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication =
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication =
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication =
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
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication =
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
    void authorizeCookieRejectedWhenUnchanged() {
        String cookie = "PHPSESSID=12345_abc; other=x";
        when(store.findById(5L)).thenReturn(task(
                5L, true, null, ScheduleSuspendReason.CREDENTIAL,
                "PIXIV_COOKIE_INVALID", null, "12345", EMPTY_POLICY_STATE, true));
        when(store.findCredentialSecret(
                5L, DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID))
                .thenReturn(cookie);

        assertThatThrownBy(() -> newService().authorizeCookie(5L, "  " + cookie + "  "))
                .isInstanceOf(LocalizedException.class);

        verify(overuseWarningService, never()).probe(anyString(), anyLong());
        verify(store, never()).bindCredential(
                anyLong(), anyLong(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：探活成功后以 CAS 绑定凭证、保留策略状态并恢复凭证挂起")
    void authorizeCookieBindsWithCasAndResumesCredentialSuspension() {
        String cookie = "PHPSESSID=12345_new; other=x";
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
        when(overuseWarningService.probe(eq(cookie), anyLong()))
                .thenReturn(OveruseWarningService.Result.clean());
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

        newService().authorizeCookie(6L, cookie);

        verify(overuseWarningService).probe(eq(cookie), anyLong());
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
    @DisplayName("authorizeCookie：首次授权生成版本化空策略状态")
    void authorizeCookieCreatesVersionedPolicyStateOnFirstBinding() {
        String cookie = "PHPSESSID=999_abc";
        when(store.findById(7L)).thenReturn(task(7L));
        when(overuseWarningService.probe(eq(cookie), anyLong()))
                .thenReturn(OveruseWarningService.Result.clean());
        when(store.bindCredential(
                eq(7L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("999"), eq(EMPTY_POLICY_STATE), eq(cookie),
                eq("scheduled-task:7:credential"), anyLong()))
                .thenReturn(OptionalLong.of(STATE_VERSION + 1));

        newService().authorizeCookie(7L, cookie);

        verify(store).bindCredential(
                eq(7L), eq(STATE_VERSION), eq(DownloadWorkbenchPlugin.ID),
                eq(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                eq("999"), eq(EMPTY_POLICY_STATE), eq(cookie),
                eq("scheduled-task:7:credential"), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：探活判定凭证失效时不执行绑定")
    void authorizeCookieRejectsDeadCredentialProbe() {
        String cookie = "PHPSESSID=999_dead";
        when(store.findById(8L)).thenReturn(task(8L));
        when(overuseWarningService.probe(eq(cookie), anyLong()))
                .thenReturn(OveruseWarningService.Result.cookieDead());

        assertThatThrownBy(() -> newService().authorizeCookie(8L, cookie))
                .isInstanceOf(LocalizedException.class);

        verify(store, never()).bindCredential(
                anyLong(), anyLong(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("authorizeCookie：探活发现警告时绑定后以新版本挂起策略")
    void authorizeCookieSuspendsWarnedCredentialAfterBinding() throws Exception {
        String cookie = "PHPSESSID=999_warned";
        when(store.findById(9L)).thenReturn(task(9L));
        when(overuseWarningService.probe(eq(cookie), anyLong()))
                .thenReturn(OveruseWarningService.Result.warned(999000L, "safe excerpt"));
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

        newService().authorizeCookie(9L, cookie);

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
    void authorizeCookieRejectsStateChangeObservedAfterProbe() {
        String cookie = "PHPSESSID=999_race";
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
                beforeProbe.credentialUpdatedTime(), beforeProbe.createdTime());
        when(store.findById(10L)).thenReturn(beforeProbe, changed);
        when(overuseWarningService.probe(eq(cookie), anyLong()))
                .thenReturn(OveruseWarningService.Result.clean());

        assertThatThrownBy(() -> newService().authorizeCookie(10L, cookie))
                .isInstanceOf(LocalizedException.class);

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

        newService().revokeCookie(20L);

        verify(store).removeCredential(
                20L, STATE_VERSION, DownloadWorkbenchPlugin.ID,
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
    @DisplayName("queue：仅为本轮确实提交自动翻译的小说条目叠加翻译状态")
    void queueOverlaysTranslateOnlyForSubmittedItems() {
        when(store.findById(1L)).thenReturn(task(1L));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_NOVEL);
        run.discovered("111", ScheduleRunQueue.KIND_NOVEL);
        run.mark("111", ScheduleRunQueue.STATUS_DOWNLOADED, null);
        run.markAutoTranslateSubmitted("111");
        run.discovered("222", ScheduleRunQueue.KIND_NOVEL);
        run.mark("222", ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
        when(runQueue.get(1L)).thenReturn(run);
        ScheduledWorkRunner novelRunner = org.mockito.Mockito.mock(ScheduledWorkRunner.class);
        when(novelRunner.kind()).thenReturn(ScheduledWorkKind.NOVEL);
        when(novelRunner.translateStatus(111L)).thenReturn(
                new ScheduledWorkTranslateStatus("TRANSLATING", 5L, 0));
        ScheduleCapabilityRegistry capabilityRegistry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = ScheduleCapabilityTestFixture.publish(
                capabilityRegistry,
                new ScheduleCapabilityOwner("novel", "novel", 1L),
                List.of(),
                List.of(novelRunner));

        List<ScheduleQueueView.Item> items =
                newService(new ScheduleRunState(), capabilityRegistry).queue(1L).items();

        ScheduleQueueView.Item submitted = items.stream()
                .filter(item -> item.id().equals("111"))
                .findFirst()
                .orElseThrow();
        ScheduleQueueView.Item skipped = items.stream()
                .filter(item -> item.id().equals("222"))
                .findFirst()
                .orElseThrow();
        assertThat(submitted.translatePhase()).isEqualTo("TRANSLATING");
        assertThat(skipped.translatePhase()).isNull();
        verify(novelRunner).translateStatus(111L);
        verify(novelRunner, never()).translateStatus(222L);
        assertThat(ScheduleCapabilityTestFixture.withdraw(capabilityRegistry, publication)
                .orElseThrow().isDrained()).isTrue();
    }

    @Test
    @DisplayName("queue：小说执行器缺席时安全跳过翻译状态")
    void queueSkipsTranslateStatusWhenCapabilityIsAbsent() {
        when(store.findById(2L)).thenReturn(task(2L));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_NOVEL);
        run.discovered("333", ScheduleRunQueue.KIND_NOVEL);
        run.mark("333", ScheduleRunQueue.STATUS_DOWNLOADED, null);
        run.markAutoTranslateSubmitted("333");
        when(runQueue.get(2L)).thenReturn(run);

        ScheduleQueueView.Item item = newService().queue(2L).items().get(0);

        assertThat(item.id()).isEqualTo("333");
        assertThat(item.translatePhase()).isNull();
        assertThat(item.translateElapsedSeconds()).isNull();
        assertThat(item.translateSeriesPending()).isNull();
    }

    @Test
    @DisplayName("queue：插件翻译状态异常时降级返回完整队列")
    void queueSurvivesPluginTranslateStatusFailure() {
        when(store.findById(3L)).thenReturn(task(3L));
        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_NOVEL);
        run.discovered("444", ScheduleRunQueue.KIND_NOVEL);
        run.mark("444", ScheduleRunQueue.STATUS_DOWNLOADED, null);
        run.markAutoTranslateSubmitted("444");
        when(runQueue.get(3L)).thenReturn(run);
        ScheduledWorkRunner novelRunner = org.mockito.Mockito.mock(ScheduledWorkRunner.class);
        when(novelRunner.kind()).thenReturn(ScheduledWorkKind.NOVEL);
        when(novelRunner.translateStatus(444L))
                .thenThrow(new IllegalStateException("plugin child failure"));
        ScheduleCapabilityRegistry capabilityRegistry = new ScheduleCapabilityRegistry();
        ScheduleCapabilityPublication publication = ScheduleCapabilityTestFixture.publish(
                capabilityRegistry,
                new ScheduleCapabilityOwner("novel", "novel", 1L),
                List.of(), List.of(novelRunner));

        ScheduleQueueView.Item item =
                newService(new ScheduleRunState(), capabilityRegistry).queue(3L).items().get(0);

        assertThat(item.id()).isEqualTo("444");
        assertThat(item.translatePhase()).isNull();
        assertThat(item.translateElapsedSeconds()).isNull();
        assertThat(item.translateSeriesPending()).isNull();
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

        ScheduleTaskView view = ScheduleTaskView.of(suspended, null, persistenceCodec);

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
}
