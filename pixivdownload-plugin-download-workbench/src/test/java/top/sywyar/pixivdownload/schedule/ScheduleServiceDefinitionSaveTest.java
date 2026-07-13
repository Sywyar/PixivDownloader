package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskInsert;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.schedule.source.descriptor.PixivScheduledSourceDescriptors;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivScheduledSourceSupport;
import top.sywyar.pixivdownload.download.schedule.source.executor.PixivUserNewScheduledSourceExecutor;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.schedule.dto.ScheduleSourceManifestView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("计划任务通用来源保存")
class ScheduleServiceDefinitionSaveTest {

    private static final String SOURCE_TYPE = "fixture:source";
    private static final String DEFINITION_SCHEMA = "fixture.definition";
    private static final String WORK_TYPE = "fixture:work";

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
    private ScheduleExecutionEngine executionEngine;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("创建调用当前 owner 规范化与计划校验后才写入通用信封")
    void createPersistsPreparedGenericDefinitionAfterPlanValidation() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean prepareCalled = new AtomicBoolean();
        AtomicBoolean planCalled = new AtomicBoolean();
        SourceFixture fixture = publishSource(
                registry, "fixture-owner", prepareCalled, planCalled, false, false);
        doAnswer(invocation -> {
            invocation.<ScheduledTaskInsert>getArgument(0).setId(31L);
            return null;
        }).when(store).insert(any(ScheduledTaskInsert.class));
        when(store.findById(31L)).thenReturn(task(
                31L, "fixture-owner", SOURCE_TYPE,
                "{\"canonical\":true}",
                "{\"title\":\"owner title\",\"summary\":\"summary\","
                        + "\"attributes\":{\"kind\":\"fixture\"}}"));
        ScheduleService service = service(registry);

        ScheduleTaskView view = service.create(request(fixture.activationToken()));

        ArgumentCaptor<ScheduledTaskInsert> row = ArgumentCaptor.forClass(ScheduledTaskInsert.class);
        verify(store).insert(row.capture());
        assertThat(prepareCalled).isTrue();
        assertThat(planCalled).isTrue();
        assertThat(row.getValue().getSourceOwnerPluginId()).isEqualTo("fixture-owner");
        assertThat(row.getValue().getSourceType()).isEqualTo(SOURCE_TYPE);
        assertThat(row.getValue().getDefinitionSchema()).isEqualTo(DEFINITION_SCHEMA);
        assertThat(row.getValue().getDefinitionVersion()).isEqualTo(1);
        assertThat(row.getValue().getDefinitionJson()).isEqualTo("{\"canonical\":true}");
        assertThat(row.getValue().getPresentationJson()).contains("owner title", "kind");
        assertThat(view.sourceAvailable()).isTrue();
        assertThat(view.sourceActivationToken()).isEqualTo(fixture.activationToken());
        assertThat(view.presentation().attributes())
                .containsExactlyEntriesOf(Map.of("kind", "fixture"));

        ScheduleSourceManifestView manifest = service.sources();
        assertThat(manifest.epoch()).isNotBlank();
        assertThat(manifest.sources()).singleElement().satisfies(source -> {
            assertThat(source.sourceType()).isEqualTo(SOURCE_TYPE);
            assertThat(source.ownerPluginId()).isEqualTo("fixture-owner");
            assertThat(source.packageId()).isEqualTo("fixture-package");
            assertThat(source.pluginGeneration()).isEqualTo(3L);
            assertThat(source.activationToken()).isEqualTo(fixture.activationToken());
            assertThat(source.frontend().moduleUrl()).isEqualTo("/fixture/schedule-source.js");
        });
    }

    @Test
    @DisplayName("创建写库与事务提交期间阻塞来源 publication 撤回")
    void createKeepsSourceLeaseThroughInsertAndCommit() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean prepareCalled = new AtomicBoolean();
        SourceFixture fixture = publishSource(
                registry, "fixture-owner", prepareCalled, new AtomicBoolean(), false, false);
        BlockingCommitTransactionManager transactionManager =
                new BlockingCommitTransactionManager(prepareCalled);
        CountDownLatch insertEntered = new CountDownLatch(1);
        CountDownLatch allowInsert = new CountDownLatch(1);
        doAnswer(invocation -> {
            insertEntered.countDown();
            await(allowInsert);
            invocation.<ScheduledTaskInsert>getArgument(0).setId(81L);
            return null;
        }).when(store).insert(any(ScheduledTaskInsert.class));
        when(store.findById(81L)).thenReturn(task(
                81L, "fixture-owner", SOURCE_TYPE,
                "{\"canonical\":true}",
                "{\"title\":\"owner title\",\"attributes\":{}}"));

        ExecutorService worker = Executors.newFixedThreadPool(2);
        try {
            Future<ScheduleTaskView> result = worker.submit(() ->
                    service(registry, transactionManager).create(request(fixture.activationToken())));
            assertThat(insertEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch withdrawStarted = new CountDownLatch(1);
            Future<ScheduleGenerationDrain> withdrawal = worker.submit(() -> {
                withdrawStarted.countDown();
                return ScheduleCapabilityTestFixture.withdraw(
                        registry, fixture.publication()).orElseThrow();
            });
            assertThat(withdrawStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(withdrawal.isDone()).isFalse();
            assertThat(registry.snapshotView().owners()).singleElement();

            allowInsert.countDown();
            assertThat(transactionManager.commitEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(withdrawal.isDone()).isFalse();
            assertThat(registry.snapshotView().owners()).singleElement();

            transactionManager.allowCommit.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS).id()).isEqualTo(81L);
            ScheduleGenerationDrain drain = withdrawal.get(5, TimeUnit.SECONDS);
            assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            assertThat(drain.activeLeaseCount()).isZero();
            assertThat(registry.snapshotView().owners()).isEmpty();
        } finally {
            allowInsert.countDown();
            transactionManager.allowCommit.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("已有外层事务时定义保存独立提交并只阻塞撤回到内层提交完成")
    void definitionSaveUsesIndependentTransactionInsideOuterTransaction() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        SourceFixture fixture = publishSource(
                registry, "fixture-owner", new AtomicBoolean(), new AtomicBoolean(), false, false);
        NestedCommitTransactionManager transactionManager = new NestedCommitTransactionManager();
        doAnswer(invocation -> {
            invocation.<ScheduledTaskInsert>getArgument(0).setId(83L);
            return null;
        }).when(store).insert(any(ScheduledTaskInsert.class));
        when(store.findById(83L)).thenReturn(task(
                83L, "fixture-owner", SOURCE_TYPE,
                "{\"canonical\":true}",
                "{\"title\":\"owner title\",\"attributes\":{}}"));
        CountDownLatch serviceReturnedInsideOuter = new CountDownLatch(1);
        CountDownLatch allowOuterCallback = new CountDownLatch(1);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        ExecutorService worker = Executors.newFixedThreadPool(2);
        try {
            Future<ScheduleTaskView> result = worker.submit(() -> outer.execute(status -> {
                ScheduleTaskView view = service(registry, transactionManager)
                        .create(request(fixture.activationToken()));
                serviceReturnedInsideOuter.countDown();
                await(allowOuterCallback);
                return view;
            }));

            assertThat(transactionManager.innerCommitEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch withdrawStarted = new CountDownLatch(1);
            Future<ScheduleGenerationDrain> withdrawal = worker.submit(() -> {
                withdrawStarted.countDown();
                return ScheduleCapabilityTestFixture.withdraw(
                        registry, fixture.publication()).orElseThrow();
            });
            assertThat(withdrawStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(withdrawal.isDone()).isFalse();
            assertThat(registry.snapshotView().owners()).singleElement();

            transactionManager.allowInnerCommit.countDown();
            assertThat(serviceReturnedInsideOuter.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(transactionManager.requiresNewObserved).isTrue();
            ScheduleGenerationDrain drain = withdrawal.get(5, TimeUnit.SECONDS);
            assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            assertThat(transactionManager.outerCommitEntered.getCount()).isEqualTo(1L);
            assertThat(registry.snapshotView().owners()).isEmpty();

            allowOuterCallback.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS).id()).isEqualTo(83L);
            assertThat(transactionManager.outerCommitEntered.getCount()).isZero();
        } finally {
            transactionManager.allowInnerCommit.countDown();
            allowOuterCallback.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("创建回滚与提交失败都会释放来源 publication 租约")
    void createFailureReleasesSourceLeaseAfterTransactionCompletion() {
        ScheduleCapabilityRegistry rollbackRegistry = new ScheduleCapabilityRegistry();
        SourceFixture rollbackFixture = publishSource(
                rollbackRegistry,
                "rollback-owner",
                new AtomicBoolean(),
                new AtomicBoolean(),
                false,
                false);
        RecordingTransactionManager rollbackManager = new RecordingTransactionManager(null);
        doAnswer(invocation -> {
            throw new IllegalStateException("fixture insert failure");
        }).when(store).insert(any(ScheduledTaskInsert.class));

        assertThatThrownBy(() -> service(rollbackRegistry, rollbackManager)
                .create(request(rollbackFixture.activationToken())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fixture insert failure");
        assertThat(rollbackManager.rolledBack).isTrue();
        assertThat(ScheduleCapabilityTestFixture.withdraw(
                rollbackRegistry, rollbackFixture.publication()).orElseThrow().isDrained()).isTrue();

        org.mockito.Mockito.reset(store);
        ScheduleCapabilityRegistry commitRegistry = new ScheduleCapabilityRegistry();
        SourceFixture commitFixture = publishSource(
                commitRegistry,
                "commit-owner",
                new AtomicBoolean(),
                new AtomicBoolean(),
                false,
                false);
        RecordingTransactionManager commitManager = new RecordingTransactionManager(
                new IllegalStateException("fixture commit failure"));
        doAnswer(invocation -> {
            invocation.<ScheduledTaskInsert>getArgument(0).setId(82L);
            return null;
        }).when(store).insert(any(ScheduledTaskInsert.class));
        when(store.findById(82L)).thenReturn(task(
                82L, "commit-owner", SOURCE_TYPE,
                "{\"canonical\":true}",
                "{\"title\":\"owner title\",\"attributes\":{}}"));

        assertThatThrownBy(() -> service(commitRegistry, commitManager)
                .create(request(commitFixture.activationToken())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fixture commit failure");
        assertThat(commitManager.committed).isTrue();
        assertThat(ScheduleCapabilityTestFixture.withdraw(
                commitRegistry, commitFixture.publication()).orElseThrow().isDrained()).isTrue();
    }

    @Test
    @DisplayName("过期激活令牌返回冲突且不调用 owner 或写库")
    void staleActivationTokenFailsBeforePluginAndPersistence() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean prepareCalled = new AtomicBoolean();
        SourceFixture fixture = publishSource(
                registry, "fixture-owner", prepareCalled, new AtomicBoolean(), false, false);
        ScheduleTaskRequest request = request(fixture.activationToken() + "-stale");

        assertThatThrownBy(() -> service(registry).create(request))
                .isInstanceOfSatisfying(LocalizedException.class, failure ->
                        assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(prepareCalled).isFalse();
        verify(store, never()).insert(any(ScheduledTaskInsert.class));
    }

    @Test
    @DisplayName("非法 JSON 与凭证材料在 owner 调用前被宿主拒绝")
    void unsafeDraftNeverReachesOwnerOrPersistence() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean prepareCalled = new AtomicBoolean();
        SourceFixture fixture = publishSource(
                registry, "fixture-owner", prepareCalled, new AtomicBoolean(), false, false);

        for (String definitionJson : List.of(
                "{\"value\":1,\"value\":2}",
                "{\"cookie\":\"a=b\"}",
                "{\"value\":\"\\u0000\"}",
                "{\"value\":\"\\uD800\"}")) {
            ScheduleTaskRequest request = request(fixture.activationToken());
            request.setDefinitionJson(definitionJson);
            assertThatThrownBy(() -> service(registry).create(request))
                    .isInstanceOf(LocalizedException.class);
        }
        ScheduleTaskRequest oversized = request(fixture.activationToken());
        oversized.setDefinitionJson("{\"value\":\""
                + "界".repeat(ScheduledTaskDefinition.MAX_DEFINITION_BYTES / 3 + 1)
                + "\"}");
        assertThatThrownBy(() -> service(registry).create(oversized))
                .isInstanceOf(LocalizedException.class);

        assertThat(prepareCalled).isFalse();
        verify(store, never()).insert(any(ScheduledTaskInsert.class));
    }

    @Test
    @DisplayName("编辑不能切换来源 owner 且异常路径保持零写入")
    void updateRejectsDifferentOwnerWithoutWriting() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean prepareCalled = new AtomicBoolean();
        SourceFixture fixture = publishSource(
                registry, "other-owner", prepareCalled, new AtomicBoolean(), false, false);
        when(store.findById(44L)).thenReturn(task(
                44L, "existing-owner", "existing:source", "{}", "{}"));

        assertThatThrownBy(() -> service(registry).update(
                44L, updateRequest(fixture.activationToken(), 7L)))
                .isInstanceOf(LocalizedException.class);

        assertThat(prepareCalled).isFalse();
        verify(store, never()).updateDefinition(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("编辑不能让已绑定凭证跨到不同策略且保持零写入")
    void updateRejectsCredentialPolicyChangeWithoutWriting() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean prepareCalled = new AtomicBoolean();
        SourceFixture fixture = publishSource(
                registry, "fixture-owner", prepareCalled, new AtomicBoolean(), false, false);
        when(store.findById(45L)).thenReturn(task(
                45L,
                "fixture-owner",
                SOURCE_TYPE,
                "{}",
                "{}",
                "fixture-owner",
                "old-policy",
                "scheduled-task:45:credential"));

        assertThatThrownBy(() -> service(registry).update(
                45L, updateRequest(fixture.activationToken(), 7L)))
                .isInstanceOf(LocalizedException.class)
                .hasMessageContaining("凭证策略");

        assertThat(prepareCalled).isTrue();
        verify(store, never()).updateDefinition(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("编辑规范化期间任务版本变化时事务内重读并拒绝覆盖")
    void updateRejectsStateVersionChangeDuringNormalization() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean prepareCalled = new AtomicBoolean();
        SourceFixture fixture = publishSource(
                registry, "fixture-owner", prepareCalled, new AtomicBoolean(), false, false);
        when(store.findById(46L)).thenReturn(
                task(
                        46L, "fixture-owner", SOURCE_TYPE, "{}", "{}",
                        null, null, null, 7L),
                task(
                        46L, "fixture-owner", SOURCE_TYPE, "{}", "{}",
                        null, null, null, 8L));

        assertThatThrownBy(() -> service(registry).update(
                46L, updateRequest(fixture.activationToken(), 7L)))
                .isInstanceOfSatisfying(LocalizedException.class, failure ->
                        assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(prepareCalled).isTrue();
        verify(store, never()).updateDefinition(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("陈旧编辑版本在 owner 调用前返回冲突且保持零写入")
    void staleEditorVersionFailsBeforePluginNormalization() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean prepareCalled = new AtomicBoolean();
        SourceFixture fixture = publishSource(
                registry, "fixture-owner", prepareCalled, new AtomicBoolean(), false, false);
        when(store.findById(47L)).thenReturn(task(
                47L, "fixture-owner", SOURCE_TYPE, "{}", "{}",
                null, null, null, 8L));

        assertThatThrownBy(() -> service(registry).update(
                47L, updateRequest(fixture.activationToken(), 7L)))
                .isInstanceOfSatisfying(LocalizedException.class, failure ->
                        assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(prepareCalled).isFalse();
        verify(store, never()).updateDefinition(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("创建禁止状态版本且编辑缺少状态版本时不调用 owner 或写库")
    void createForbidsAndUpdateRequiresExpectedStateVersion() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicBoolean prepareCalled = new AtomicBoolean();
        SourceFixture fixture = publishSource(
                registry, "fixture-owner", prepareCalled, new AtomicBoolean(), false, false);
        ScheduleTaskRequest create = request(fixture.activationToken());
        create.setExpectedStateVersion(0L);

        assertThatThrownBy(() -> service(registry).create(create))
                .isInstanceOf(LocalizedException.class);
        assertThatThrownBy(() -> service(registry).update(
                48L, request(fixture.activationToken())))
                .isInstanceOf(LocalizedException.class);

        assertThat(prepareCalled).isFalse();
        verify(store, never()).insert(any(ScheduledTaskInsert.class));
        verify(store, never()).updateDefinition(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("owner 异常或越权执行计划都不会产生定义写入")
    void pluginFailureAndPlanEscapeLeaveStoreUntouched() {
        for (boolean planEscape : List.of(false, true)) {
            ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
            SourceFixture fixture = publishSource(
                    registry,
                    planEscape ? "escape-owner" : "failure-owner",
                    new AtomicBoolean(),
                    new AtomicBoolean(),
                    !planEscape,
                    planEscape);

            assertThatThrownBy(() -> service(registry).create(request(fixture.activationToken())))
                    .isInstanceOf(LocalizedException.class);
        }
        verify(store, never()).insert(any(ScheduledTaskInsert.class));
    }

    @Test
    @DisplayName("prepare 与 plan 抛出断言错误时不泄漏插件 cause 且快照和数据库不变")
    void assertionErrorsAtPluginCallbacksLeaveSnapshotAndStoreUntouched() {
        for (boolean prepareAssertion : List.of(true, false)) {
            ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
            SourceFixture fixture = publishSource(
                    registry,
                    prepareAssertion ? "prepare-assert-owner" : "plan-assert-owner",
                    new AtomicBoolean(),
                    new AtomicBoolean(),
                    false,
                    false,
                    prepareAssertion,
                    !prepareAssertion,
                    null,
                    Set.of());
            var before = registry.snapshotView();

            assertThatThrownBy(() -> service(registry).create(request(fixture.activationToken())))
                    .isInstanceOfSatisfying(LocalizedException.class, failure ->
                            assertThat(failure.getCause()).isNull());

            assertThat(registry.snapshotView()).isEqualTo(before);
        }
        verify(store, never()).insert(any(ScheduledTaskInsert.class));
    }

    @Test
    @DisplayName("共享计划 gate 在保存时拒绝上限重复 Guard 与过大批次且保持零写入")
    void sharedPlanGateRejectsInvalidPlansBeforeInsert() {
        ScheduledGuardBinding normalGuard = new ScheduledGuardBinding(
                "fixture:guard", Set.of(ScheduledGuardPoint.RUN_START), 0);
        ScheduledGuardBinding oversizedBatch = new ScheduledGuardBinding(
                "fixture:guard", Set.of(ScheduledGuardPoint.WORK_BATCH), 100_001);
        List<ScheduledExecutionPlan> invalidPlans = List.of(
                new ScheduledExecutionPlan(
                        Set.of(WORK_TYPE), null, ScheduledCredentialRequirement.NONE, false,
                        List.of(), null, 0, 257, 0L),
                new ScheduledExecutionPlan(
                        Set.of(WORK_TYPE), null, ScheduledCredentialRequirement.NONE, false,
                        List.of(oversizedBatch), null, 0, 1, 0L),
                new ScheduledExecutionPlan(
                        Set.of(WORK_TYPE), null, ScheduledCredentialRequirement.NONE, false,
                        List.of(normalGuard, normalGuard), null, 0, 1, 0L));

        for (int index = 0; index < invalidPlans.size(); index++) {
            ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
            SourceFixture fixture = publishSource(
                    registry,
                    "plan-gate-owner-" + index,
                    new AtomicBoolean(),
                    new AtomicBoolean(),
                    false,
                    false,
                    false,
                    false,
                    invalidPlans.get(index),
                    Set.of("fixture:guard"));

            assertThatThrownBy(() -> service(registry).create(request(fixture.activationToken())))
                    .isInstanceOf(LocalizedException.class);
        }
        verify(store, never()).insert(any(ScheduledTaskInsert.class));
    }

    @Test
    @DisplayName("Pixiv 非法业务定义在创建与编辑路径均保持零写入")
    void invalidPixivBusinessDefinitionLeavesCreateAndUpdateUntouched() {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        SourceFixture fixture = publishPixivUserNewSource(registry);
        ScheduleTaskRequest invalid = request(fixture.activationToken());
        invalid.setSourceType("user-new");
        invalid.setDefinitionJson("""
                {"kind":"illust","source":{"userId":"not-a-number"}}
                """);

        assertThatThrownBy(() -> service(registry).create(invalid))
                .isInstanceOf(LocalizedException.class);
        when(store.findById(71L)).thenReturn(task(
                71L, DownloadWorkbenchPlugin.ID, "user-new", "{}", "{}"));
        invalid.setExpectedStateVersion(7L);
        assertThatThrownBy(() -> service(registry).update(71L, invalid))
                .isInstanceOf(LocalizedException.class);

        verify(store, never()).insert(any(ScheduledTaskInsert.class));
        verify(store, never()).updateDefinition(any(Long.class), any(Long.class), any());
    }

    private ScheduleService service(ScheduleCapabilityRegistry registry) {
        return service(registry, NO_OP_TRANSACTION_MANAGER);
    }

    private ScheduleService service(
            ScheduleCapabilityRegistry registry,
            PlatformTransactionManager transactionManager) {
        return new ScheduleService(
                store,
                executor,
                new ScheduleConfig(),
                new ScheduleRunState(),
                runQueue,
                objectMapper,
                new PixivSchedulePersistenceCodec(objectMapper),
                executionEngine,
                new TransactionTemplate(transactionManager),
                registry);
    }

    private static ScheduleTaskRequest request(String activationToken) {
        ScheduleTaskRequest request = new ScheduleTaskRequest();
        request.setName("任务");
        request.setSourceType(SOURCE_TYPE);
        request.setActivationToken(activationToken);
        request.setDefinitionJson("{\"raw\":true}");
        request.setTriggerKind(ScheduledTask.TRIGGER_INTERVAL);
        request.setIntervalMinutes(60);
        return request;
    }

    private static ScheduleTaskRequest updateRequest(
            String activationToken,
            long expectedStateVersion) {
        ScheduleTaskRequest request = request(activationToken);
        request.setExpectedStateVersion(expectedStateVersion);
        return request;
    }

    private static SourceFixture publishSource(
            ScheduleCapabilityRegistry registry,
            String ownerPluginId,
            AtomicBoolean prepareCalled,
            AtomicBoolean planCalled,
            boolean prepareFailure,
            boolean planEscape) {
        return publishSource(
                registry,
                ownerPluginId,
                prepareCalled,
                planCalled,
                prepareFailure,
                planEscape,
                false,
                false,
                null,
                Set.of());
    }

    private static SourceFixture publishSource(
            ScheduleCapabilityRegistry registry,
            String ownerPluginId,
            AtomicBoolean prepareCalled,
            AtomicBoolean planCalled,
            boolean prepareFailure,
            boolean planEscape,
            boolean prepareAssertion,
            boolean planAssertion,
            ScheduledExecutionPlan fixedPlan,
            Set<String> descriptorGuardIds) {
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                SOURCE_TYPE,
                Set.of("FIXTURE_SOURCE"),
                DEFINITION_SCHEMA,
                1,
                new ScheduledSourcePresentation(
                        "fixture", "fixture.name", "fixture.description", "schedule", "neutral"),
                Set.of("fixture"),
                Set.of(WORK_TYPE),
                Set.of(),
                descriptorGuardIds,
                new ScheduledSourceFrontendContribution(1, "/fixture/schedule-source.js"));
        ScheduledSourceExecutor sourceExecutor = new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return SOURCE_TYPE;
            }

            @Override
            public ScheduledTaskDefinition prepare(ScheduledTaskDraft draft) {
                prepareCalled.set(true);
                if (prepareFailure) {
                    throw new IllegalStateException("fixture prepare failure");
                }
                if (prepareAssertion) {
                    throw new AssertionError("fixture prepare assertion");
                }
                return new ScheduledTaskDefinition(
                        draft.taskId(),
                        draft.sourceType(),
                        draft.definitionSchema(),
                        draft.definitionVersion(),
                        "{\"canonical\":true}",
                        new ScheduledTaskPresentation(
                                "owner title", "summary", Map.of("kind", "fixture")));
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                planCalled.set(true);
                if (planAssertion) {
                    throw new AssertionError("fixture plan assertion");
                }
                if (fixedPlan != null) {
                    return fixedPlan;
                }
                return ScheduledExecutionPlan.credentialFree(Set.of(
                        planEscape ? "other:work" : WORK_TYPE));
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
        ScheduledWorkExecutor workExecutor = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return WORK_TYPE;
            }

            @Override
            public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }
        };
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner(
                ownerPluginId, ownerPluginId.replace("owner", "package"), 3L);
        ScheduleOwnerBundle bundle = ScheduleOwnerBundle.prepare(
                owner,
                List.of(),
                List.of(),
                List.of(descriptor),
                List.of(sourceExecutor),
                List.of(workExecutor),
                List.of(),
                List.of());
        ScheduleCapabilityPublication publication = ScheduleCapabilityTestFixture.publish(registry, bundle);
        String activationToken = registry.snapshotView().owners().get(0).activationToken();
        return new SourceFixture(activationToken, publication);
    }

    private static SourceFixture publishPixivUserNewSource(ScheduleCapabilityRegistry registry) {
        ObjectMapper mapper = new ObjectMapper();
        PixivScheduledSourceSupport support = new PixivScheduledSourceSupport(
                mapper,
                mock(PixivFetchService.class),
                new PixivSchedulePersistenceCodec(mapper),
                (key, download) -> false,
                () -> 37);
        ScheduledSourceDescriptor descriptor = PixivScheduledSourceDescriptors.createAll().stream()
                .filter(candidate -> candidate.sourceType().equals("user-new"))
                .findFirst()
                .orElseThrow();
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner(
                DownloadWorkbenchPlugin.ID, DownloadWorkbenchPlugin.ID, 3L);
        ScheduleOwnerBundle bundle = ScheduleOwnerBundle.prepare(
                owner,
                List.of(),
                List.of(),
                List.of(descriptor),
                List.of(new PixivUserNewScheduledSourceExecutor(support)),
                List.of(),
                List.of(),
                List.of());
        ScheduleCapabilityPublication publication = ScheduleCapabilityTestFixture.publish(registry, bundle);
        return new SourceFixture(
                registry.snapshotView().owners().get(0).activationToken(), publication);
    }

    private static ScheduledTask task(
            long id,
            String ownerPluginId,
            String sourceType,
            String definitionJson,
            String presentationJson) {
        return task(
                id,
                ownerPluginId,
                sourceType,
                definitionJson,
                presentationJson,
                null,
                null,
                null);
    }

    private static ScheduledTask task(
            long id,
            String ownerPluginId,
            String sourceType,
            String definitionJson,
            String presentationJson,
            String credentialPolicyOwnerPluginId,
            String credentialPolicyId,
            String credentialSecretReference) {
        return task(
                id,
                ownerPluginId,
                sourceType,
                definitionJson,
                presentationJson,
                credentialPolicyOwnerPluginId,
                credentialPolicyId,
                credentialSecretReference,
                7L);
    }

    private static ScheduledTask task(
            long id,
            String ownerPluginId,
            String sourceType,
            String definitionJson,
            String presentationJson,
            String credentialPolicyOwnerPluginId,
            String credentialPolicyId,
            String credentialSecretReference,
            long stateVersion) {
        return new ScheduledTask(
                id,
                "任务",
                true,
                sourceType,
                ownerPluginId,
                DEFINITION_SCHEMA,
                1,
                definitionJson,
                presentationJson,
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
                null,
                null,
                null,
                stateVersion,
                credentialPolicyOwnerPluginId,
                credentialPolicyId,
                credentialPolicyId == null ? null : "account-1",
                credentialPolicyId == null ? null : "{}",
                credentialSecretReference,
                credentialSecretReference == null ? null : 900L,
                0L);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for test latch", failure);
        }
    }

    private static final class BlockingCommitTransactionManager implements PlatformTransactionManager {

        private final AtomicBoolean prepareCalled;
        private final CountDownLatch commitEntered = new CountDownLatch(1);
        private final CountDownLatch allowCommit = new CountDownLatch(1);

        private BlockingCommitTransactionManager(AtomicBoolean prepareCalled) {
            this.prepareCalled = prepareCalled;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (!prepareCalled.get()) {
                throw new AssertionError("definition transaction started before plugin normalization finished");
            }
            if (definition.getPropagationBehavior()
                    != TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
                throw new AssertionError("definition transaction must require a new transaction");
            }
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commitEntered.countDown();
            await(allowCommit);
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private static final class NestedCommitTransactionManager implements PlatformTransactionManager {

        private final CountDownLatch innerCommitEntered = new CountDownLatch(1);
        private final CountDownLatch allowInnerCommit = new CountDownLatch(1);
        private final CountDownLatch outerCommitEntered = new CountDownLatch(1);
        private volatile boolean requiresNewObserved;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            boolean inner = definition.getPropagationBehavior()
                    == TransactionDefinition.PROPAGATION_REQUIRES_NEW;
            if (inner) {
                requiresNewObserved = true;
            }
            return new NamedTransactionStatus(inner);
        }

        @Override
        public void commit(TransactionStatus status) {
            if (((NamedTransactionStatus) status).inner) {
                innerCommitEntered.countDown();
                await(allowInnerCommit);
            } else {
                outerCommitEntered.countDown();
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private static final class NamedTransactionStatus extends SimpleTransactionStatus {

        private final boolean inner;

        private NamedTransactionStatus(boolean inner) {
            this.inner = inner;
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final RuntimeException commitFailure;
        private boolean committed;
        private boolean rolledBack;

        private RecordingTransactionManager(RuntimeException commitFailure) {
            this.commitFailure = commitFailure;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed = true;
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack = true;
        }
    }

    private record SourceFixture(
            String activationToken,
            ScheduleCapabilityPublication publication) {
    }
}
