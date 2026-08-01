package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialAccountActionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialAccountActionRequest;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskStateUpdate;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("调度宿主凭证策略编排")
class ScheduleCredentialServiceTest {

    private static final String OWNER = "credential-owner";
    private static final String POLICY = "fixture:credential-policy";
    private static final String ACCOUNT = "account-1";
    private static final String SUSPEND_CODE = "POLICY_PAUSED";
    private static final String OLD_STATE =
            "{\"schema\":\"fixture.state\",\"version\":1}";
    private static final String NEW_STATE =
            "{\"schema\":\"fixture.state\",\"version\":1,\"acknowledgedAt\":100}";

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
    private ScheduleExecutionEngine executionEngine;
    @Mock
    private ScheduledCredentialPolicy policy;

    @Test
    @DisplayName("账号动作必须精确匹配 owner 与 publication 后才调用策略")
    void accountActionRequiresExactOwnerAndPublication() {
        FakeScheduleCapabilityAccess capabilities = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publish(capabilities, policy, 1L);
        ScheduleCredentialService service = service(capabilities);

        assertConflict(() -> service.applyAccountAction(
                "another-owner", POLICY, publication.publicationId(),
                ACCOUNT, "resume", Map.of()));
        assertConflict(() -> service.applyAccountAction(
                OWNER, POLICY, publication.publicationId() + 1,
                ACCOUNT, "resume", Map.of()));

        verify(policy, never()).prepareAccountAction(any());
        verify(store, never()).findByCredentialAccount(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("策略回调期间 publication 被替换时保持零写入")
    void publicationReplacementDuringPolicyCallbackLeavesStoreUntouched() {
        FakeScheduleCapabilityAccess capabilities = new FakeScheduleCapabilityAccess();
        AtomicReference<FakeScheduleCapabilityAccess.Publication> publication =
                new AtomicReference<>();
        AtomicReference<ScheduledCredentialPolicy> replacement =
                new AtomicReference<>();
        publication.set(publish(capabilities, policy, 1L));
        ScheduledTask task = task(11L, 7L, OWNER, POLICY, ACCOUNT);
        when(store.findByCredentialAccount(OWNER, POLICY, ACCOUNT))
                .thenReturn(List.of(task));
        when(policy.prepareAccountAction(any())).thenAnswer(invocation -> {
            capabilities.withdraw(publication.get()).orElseThrow();
            ScheduledCredentialPolicy next = org.mockito.Mockito.mock(
                    ScheduledCredentialPolicy.class);
            replacement.set(next);
            publish(capabilities, next, 2L);
            return Optional.of(plan(invocation.getArgument(
                    0, ScheduledCredentialAccountActionRequest.class)
                    .tasks().get(0).stateVersion()));
        });

        assertConflict(() -> service(capabilities).applyAccountAction(
                OWNER, POLICY, publication.get().publicationId(),
                ACCOUNT, "resume", Map.of()));

        assertThat(replacement.get()).isNotNull();
        verify(store, never()).updateCredentialPolicyState(
                anyLong(), anyLong(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), anyLong());
        verify(store, never()).resume(
                anyLong(), anyLong(), any(), nullable(String.class), nullable(Long.class));
    }

    @Test
    @DisplayName("事务前任务 stateVersion 改变时拒绝恢复且零写入")
    void taskVersionChangeBeforeTransactionLeavesStoreUntouched() {
        FakeScheduleCapabilityAccess capabilities = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publish(capabilities, policy, 1L);
        ScheduledTask before = task(11L, 7L, OWNER, POLICY, ACCOUNT);
        ScheduledTask changed = task(11L, 8L, OWNER, POLICY, ACCOUNT);
        when(store.findByCredentialAccount(OWNER, POLICY, ACCOUNT))
                .thenReturn(List.of(before), List.of(changed));
        when(policy.prepareAccountAction(any())).thenReturn(Optional.of(plan(7L)));

        assertThatThrownBy(() -> service(capabilities).applyAccountAction(
                OWNER, POLICY, publication.publicationId(),
                ACCOUNT, "resume", Map.of()))
                .isInstanceOfSatisfying(LocalizedException.class, failure ->
                        assertThat(failure.messageCode())
                                .isEqualTo("schedule.error.concurrent-change"));

        verify(store, never()).updateCredentialPolicyState(
                anyLong(), anyLong(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), anyLong());
        verify(store, never()).resume(
                anyLong(), anyLong(), any(), nullable(String.class), nullable(Long.class));
    }

    @Test
    @DisplayName("同账号键的其它 owner 或 policy 不进入策略快照也不被更新")
    void sameAccountAcrossOtherScopesIsNotTouched() {
        FakeScheduleCapabilityAccess capabilities = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publish(capabilities, policy, 1L);
        ScheduledTask exact = task(11L, 7L, OWNER, POLICY, ACCOUNT);
        ScheduledTask otherOwner = task(12L, 7L, "other-owner", POLICY, ACCOUNT);
        ScheduledTask otherPolicy = task(13L, 7L, OWNER, "other:policy", ACCOUNT);
        when(store.findByCredentialAccount(OWNER, POLICY, ACCOUNT))
                .thenReturn(
                        List.of(otherOwner, exact, otherPolicy),
                        List.of(otherPolicy, exact, otherOwner));
        AtomicReference<List<Long>> observedTaskIds = new AtomicReference<>();
        when(policy.prepareAccountAction(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(
                    0, ScheduledCredentialAccountActionRequest.class);
            observedTaskIds.set(request.tasks().stream()
                    .map(ScheduledCredentialTaskSnapshot::taskId)
                    .toList());
            return Optional.of(plan(7L));
        });
        when(store.updateCredentialPolicyState(
                eq(11L), eq(7L), eq(OWNER), eq(POLICY),
                eq(OLD_STATE), eq(NEW_STATE), anyLong()))
                .thenReturn(OptionalLong.of(8L));
        when(store.resume(
                11L, 8L, ScheduleSuspendReason.POLICY,
                SUSPEND_CODE, 9_000L)).thenReturn(OptionalLong.of(9L));

        service(capabilities).applyAccountAction(
                OWNER, POLICY, publication.publicationId(),
                ACCOUNT, "resume", Map.of());

        assertThat(observedTaskIds).hasValue(List.of(11L));
        verify(store).updateCredentialPolicyState(
                eq(11L), eq(7L), eq(OWNER), eq(POLICY),
                eq(OLD_STATE), eq(NEW_STATE), anyLong());
        verify(store, never()).updateCredentialPolicyState(
                eq(12L), anyLong(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), anyLong());
        verify(store, never()).updateCredentialPolicyState(
                eq(13L), anyLong(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), anyLong());
        verify(store).resume(
                11L, 8L, ScheduleSuspendReason.POLICY,
                SUSPEND_CODE, 9_000L);
    }

    @Test
    @DisplayName("账号动作无策略状态更新时仍以原快照版本逐任务恢复")
    void emptyStateUpdatesResumeWithSnapshotVersion() {
        FakeScheduleCapabilityAccess capabilities = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publish(capabilities, policy, 1L);
        ScheduledTask task = task(11L, 7L, OWNER, POLICY, ACCOUNT);
        when(store.findByCredentialAccount(OWNER, POLICY, ACCOUNT))
                .thenReturn(List.of(task));
        when(policy.prepareAccountAction(any())).thenReturn(Optional.of(
                new ScheduledCredentialAccountActionPlan(
                        SUSPEND_CODE, 9_000L, List.of())));
        when(store.resume(
                11L, 7L, ScheduleSuspendReason.POLICY,
                SUSPEND_CODE, 9_000L)).thenReturn(OptionalLong.of(8L));

        service(capabilities).applyAccountAction(
                OWNER, POLICY, publication.publicationId(),
                ACCOUNT, "resume", Map.of());

        verify(store, never()).updateCredentialPolicyState(
                anyLong(), anyLong(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), anyLong());
        verify(store).resume(
                11L, 7L, ScheduleSuspendReason.POLICY,
                SUSPEND_CODE, 9_000L);
    }

    @Test
    @DisplayName("账号动作部分更新策略状态时分别使用更新后版本与原快照版本恢复")
    void partialStateUpdatesResumeEachTaskWithCurrentVersion() {
        FakeScheduleCapabilityAccess capabilities = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publish(capabilities, policy, 1L);
        ScheduledTask first = task(11L, 7L, OWNER, POLICY, ACCOUNT);
        ScheduledTask second = task(12L, 4L, OWNER, POLICY, ACCOUNT);
        when(store.findByCredentialAccount(OWNER, POLICY, ACCOUNT))
                .thenReturn(List.of(second, first));
        when(policy.prepareAccountAction(any())).thenReturn(Optional.of(
                new ScheduledCredentialAccountActionPlan(
                        SUSPEND_CODE,
                        9_000L,
                        List.of(new ScheduledCredentialTaskStateUpdate(
                                11L, 7L, OLD_STATE, NEW_STATE)))));
        when(store.updateCredentialPolicyState(
                eq(11L), eq(7L), eq(OWNER), eq(POLICY),
                eq(OLD_STATE), eq(NEW_STATE), anyLong()))
                .thenReturn(OptionalLong.of(8L));
        when(store.resume(
                11L, 8L, ScheduleSuspendReason.POLICY,
                SUSPEND_CODE, 9_000L)).thenReturn(OptionalLong.of(9L));
        when(store.resume(
                12L, 4L, ScheduleSuspendReason.POLICY,
                SUSPEND_CODE, 9_000L)).thenReturn(OptionalLong.of(5L));

        service(capabilities).applyAccountAction(
                OWNER, POLICY, publication.publicationId(),
                ACCOUNT, "resume", Map.of());

        verify(store).resume(
                11L, 8L, ScheduleSuspendReason.POLICY,
                SUSPEND_CODE, 9_000L);
        verify(store).resume(
                12L, 4L, ScheduleSuspendReason.POLICY,
                SUSPEND_CODE, 9_000L);
    }

    @Test
    @DisplayName("账号动作逐任务恢复遇到版本竞争时回滚整个事务")
    void resumeVersionRaceRollsBackTransaction() {
        FakeScheduleCapabilityAccess capabilities = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publish(capabilities, policy, 1L);
        ScheduledTask task = task(11L, 7L, OWNER, POLICY, ACCOUNT);
        when(store.findByCredentialAccount(OWNER, POLICY, ACCOUNT))
                .thenReturn(List.of(task));
        when(policy.prepareAccountAction(any())).thenReturn(Optional.of(
                new ScheduledCredentialAccountActionPlan(
                        SUSPEND_CODE, 9_000L, List.of())));
        when(store.resume(
                11L, 7L, ScheduleSuspendReason.POLICY,
                SUSPEND_CODE, 9_000L)).thenReturn(OptionalLong.empty());
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();

        assertThatThrownBy(() -> service(capabilities, transactionManager).applyAccountAction(
                OWNER, POLICY, publication.publicationId(),
                ACCOUNT, "resume", Map.of()))
                .isInstanceOfSatisfying(LocalizedException.class, failure ->
                        assertThat(failure.messageCode())
                                .isEqualTo("schedule.error.concurrent-change"));

        assertThat(transactionManager.rolledBack.get()).isTrue();
        assertThat(transactionManager.committed.get()).isFalse();
    }

    @Test
    @DisplayName("插件回调运行时异常映射为不泄露原始内容的安全错误")
    void pluginRuntimeFailureIsMappedToSafeError() {
        FakeScheduleCapabilityAccess capabilities = new FakeScheduleCapabilityAccess();
        FakeScheduleCapabilityAccess.Publication publication = publish(capabilities, policy, 1L);
        when(store.findByCredentialAccount(OWNER, POLICY, ACCOUNT))
                .thenReturn(List.of(task(11L, 7L, OWNER, POLICY, ACCOUNT)));
        when(policy.prepareAccountAction(any()))
                .thenThrow(new IllegalStateException("PHPSESSID=must-not-escape"));

        assertThatThrownBy(() -> service(capabilities).applyAccountAction(
                OWNER, POLICY, publication.publicationId(),
                ACCOUNT, "resume", Map.of()))
                .isInstanceOfSatisfying(LocalizedException.class, failure -> {
                    assertThat(failure.messageCode())
                            .isEqualTo("schedule.error.credential-account-action-failed");
                    assertThat(failure.getMessage()).doesNotContain("must-not-escape");
                });
        verify(store, never()).updateCredentialPolicyState(
                anyLong(), anyLong(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), anyLong());
    }

    private ScheduleCredentialService service(FakeScheduleCapabilityAccess capabilities) {
        return service(capabilities, NO_OP_TRANSACTION_MANAGER);
    }

    private ScheduleCredentialService service(
            FakeScheduleCapabilityAccess capabilities,
            PlatformTransactionManager transactionManager) {
        return new ScheduleCredentialService(
                store,
                new ScheduleRunState(),
                executionEngine,
                capabilities,
                new TransactionTemplate(transactionManager),
                new ObjectMapper());
    }

    private FakeScheduleCapabilityAccess.Publication publish(
            FakeScheduleCapabilityAccess capabilities,
            ScheduledCredentialPolicy credentialPolicy,
            long generation) {
        when(credentialPolicy.policyId()).thenReturn(POLICY);
        return ScheduleCapabilityTestFixture.publish(
                capabilities,
                ScheduleCapabilityTestFixture.bundle(
                        new ScheduleCapabilityOwner(OWNER, "fixture-package", generation),
                        List.of(), List.of(), List.of(),
                        List.of(credentialPolicy), List.of()));
    }

    private static ScheduledCredentialAccountActionPlan plan(long expectedVersion) {
        return new ScheduledCredentialAccountActionPlan(
                SUSPEND_CODE,
                9_000L,
                List.of(new ScheduledCredentialTaskStateUpdate(
                        11L, expectedVersion, OLD_STATE, NEW_STATE)));
    }

    private static ScheduledTask task(
            long id,
            long stateVersion,
            String policyOwner,
            String policyId,
            String accountKey) {
        return new ScheduledTask(
                id,
                "fixture-task-" + id,
                true,
                "fixture-source",
                "source-owner",
                "fixture.definition",
                1,
                "{}",
                "{}",
                ScheduledTask.TRIGGER_INTERVAL,
                60,
                null,
                null,
                1_000L,
                null,
                null,
                null,
                null,
                ScheduledTask.CURRENT_STORAGE_VERSION,
                null,
                null,
                ScheduleLastOutcome.NEVER,
                null,
                null,
                ScheduleSuspendReason.POLICY,
                SUSPEND_CODE,
                "{\"modifiedAt\":\"100\"}",
                stateVersion,
                policyOwner,
                policyId,
                accountKey,
                OLD_STATE,
                "scheduled-task:" + id + ":credential",
                0L);
    }

    private static void assertConflict(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(LocalizedException.class, failure ->
                        assertThat(failure.messageCode())
                                .isEqualTo(
                                        "schedule.error.credential-policy-publication-changed"));
    }

    private static final class RecordingTransactionManager
            implements PlatformTransactionManager {
        private final AtomicBoolean committed = new AtomicBoolean();
        private final AtomicBoolean rolledBack = new AtomicBoolean();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed.set(true);
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack.set(true);
        }
    }
}
