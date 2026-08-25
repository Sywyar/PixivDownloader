package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardContext;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledPendingReplayPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunContext;
import top.sywyar.pixivdownload.schedule.FakeScheduleCapabilityAccess;
import top.sywyar.pixivdownload.schedule.ScheduleCapabilityTestFixture;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.ScheduleDefinitionException;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleSourcePublicationChangedException;
import top.sywyar.pixivdownload.schedule.ScheduleSourceUnavailableException;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@DisplayName("计划执行引擎作品协调与持久化")
class ScheduleExecutionEngineWorkPersistenceTest extends ScheduleExecutionEngineTestSupport {

    @Test
    @DisplayName("作品类型容量在任务并发之内独立施加背压")
    void workTypeConcurrencyLimitAppliesInsidePlanLimit() throws Exception {
        CountDownLatch firstTwoEntered = new CountDownLatch(2);
        CountDownLatch thirdSubmitAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirstTwo = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(6, context -> {
            for (int i = 1; i <= 6; i++) {
                if (i == 3) {
                    thirdSubmitAttempted.countDown();
                }
                context.workSink().submit(work(Integer.toString(i)));
            }
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor executor = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return WORK;
            }

            @Override
            public int maxConcurrency() {
                return 2;
            }

            @Override
            public ScheduledWorkResult execute(
                    ScheduledWork work,
                    ScheduledWorkContext context) throws ScheduledExecutionException {
                int sequence = started.incrementAndGet();
                int current = active.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                try {
                    if (sequence <= 2) {
                        firstTwoEntered.countDown();
                        if (!releaseFirstTwo.await(5, TimeUnit.SECONDS)) {
                            throw new ScheduledExecutionException(
                                    ScheduledFailure.Category.INTERNAL,
                                    "fixture.release-timeout");
                        }
                    }
                    return ScheduledWorkResult.completed();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw ScheduledExecutionException.cancelled();
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(6);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            ScheduleExecutionEngine engine = engine(
                    storeWithCredential(), source, executor,
                    credentialPolicy(new AtomicReference<>()),
                    guard(context -> ScheduledGuardDecision.proceed()),
                    new ScheduleRunState(), workers::execute);
            Future<ScheduleExecutionResult> execution = caller.submit(() -> engine.execute(task()));

            assertThat(firstTwoEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thirdSubmitAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(started).hasValue(2);
            assertThat(peak).hasValue(2);

            releaseFirstTwo.countDown();
            assertThat(execution.get(5, TimeUnit.SECONDS).completedWorkCount()).isEqualTo(6);
            assertThat(peak).hasValue(2);
        } finally {
            releaseFirstTwo.countDown();
            workers.shutdownNow();
            caller.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
            caller.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("作品失败先完整写入 pending 才允许末尾 Guard 与 checkpoint 候选返回")
    void persistsPendingBeforeEndGuard() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicBoolean persisted = new AtomicBoolean();
        AtomicReference<ScheduledPendingWork> persistedWork = new AtomicReference<>();
        when(store.upsertPendingWork(any())).thenAnswer(invocation -> {
            persisted.set(true);
            persistedWork.set(invocation.getArgument(0));
            return 1;
        });
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(work("opaque-001"));
            return ScheduledDiscoveryResult.withCheckpoint(
                    new ScheduledCheckpoint("fixture.checkpoint", 1, "{}"));
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK, "fixture.retry");
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_END) {
                assertThat(persisted).isTrue();
            }
            return ScheduledGuardDecision.proceed();
        });

        ScheduleExecutionResult result = engine(
                store, source, executor, credentialPolicy(new AtomicReference<>()), guard)
                .execute(task());

        assertThat(result.completedWorkCount()).isZero();
        assertThat(result.candidateCheckpoint()).isNotNull();
        assertThat(persistedWork.get().attempts()).isZero();
        assertThat(persistedWork.get().firstSeenTime()).isNotNull();
        assertThat(persistedWork.get().lastAttemptTime()).isNull();
        verify(store).upsertPendingWork(any());
    }

    @Test
    @DisplayName("来源可先用纯本地终态清理 pending 且不计作品尝试或批次 Guard")
    void sourceClearsLocallyCompletedPendingBeforeReplay() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        ObjectMapper objectMapper = new ObjectMapper();
        ScheduleWorkPersistenceCodec codec = new ScheduleWorkPersistenceCodec(objectMapper);
        ScheduledWork pendingWork = work("pending-001");
        ScheduledPendingWork pending = codec.toPendingWork(
                1L, pendingWork, "fixture.retry", "{}", 1, 1L, 2L);
        when(store.listPendingWork(1L)).thenReturn(List.of(pending));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger batchGuards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(
                1, ScheduledPendingReplayPolicy.REDISCOVERED_ONLY, context -> {
            assertThat(context.isPending(pendingWork.key())).isTrue();
            context.workSink().completeLocally(
                    pendingWork, ScheduledWorkResult.alreadyCompleted());
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            executions.incrementAndGet();
            return ScheduledWorkResult.completed();
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.WORK_BATCH) {
                batchGuards.incrementAndGet();
            }
            return ScheduledGuardDecision.proceed();
        });

        ScheduleExecutionResult result = engine(
                store, source, executor, credentialPolicy(new AtomicReference<>()), guard)
                .execute(task());

        assertThat(result.completedWorkCount()).isZero();
        assertThat(executions).hasValue(0);
        assertThat(batchGuards).hasValue(0);
        verify(store).deletePendingWork(1L, WORK, "pending-001");
        verify(store, never()).upsertPendingWork(any());
    }

    @Test
    @DisplayName("普通来源失败前仍先执行孤立 pending 并耐久清理成功项")
    void alwaysPendingRunsBeforeSourceFailure() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        ScheduleWorkPersistenceCodec codec =
                new ScheduleWorkPersistenceCodec(new ObjectMapper());
        ScheduledWork pendingWork = work("retry-before-source");
        when(store.listPendingWork(1L)).thenReturn(List.of(codec.toPendingWork(
                1L, pendingWork, "fixture.retry", "{}", 1, 1L, 2L)));
        AtomicInteger executions = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "fixture.source-unavailable");
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            executions.incrementAndGet();
            return ScheduledWorkResult.completed();
        });

        assertThatThrownBy(() -> engine(
                store, source, executor,
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("fixture.source-unavailable"));

        assertThat(executions).hasValue(1);
        verify(store).deletePendingWork(1L, WORK, "retry-before-source");
        verify(store, never()).upsertPendingWork(any());
    }

    @Test
    @DisplayName("持久化检查点异常在凭证读取和来源发现前失败")
    void invalidStoredCheckpointFailsBeforeCredentialOrDiscovery() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicInteger discoveries = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            discoveries.incrementAndGet();
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduleExecutionEngine engine = engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed()));

        assertThatThrownBy(() -> engine.execute(
                taskWithCheckpoint("other.checkpoint", 1, "{}")))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.plan-mismatch"));
        assertThatThrownBy(() -> engine.execute(
                taskWithCheckpoint("fixture.checkpoint", null, "{}")))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.invalid-envelope"));
        assertThatThrownBy(() -> engine.execute(
                taskWithCheckpoint("fixture.checkpoint", 1, "not-json")))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.payload-invalid"));
        assertThatThrownBy(() -> engine.execute(
                taskWithCheckpoint("fixture.checkpoint", 1,
                        "{\"cookie\":\"PHPSESSID=secret\"}")))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.payload-invalid"));

        assertThat(discoveries).hasValue(0);
        verify(store, never()).listPendingWork(anyLong());
        verify(store, never()).findCredentialSecret(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("来源候选检查点必须是无凭证材料的单一 JSON")
    void candidateCheckpointMustBeSafeJson() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        AtomicInteger failureGuards = new AtomicInteger();
        AtomicReference<String> checkpointPayload = new AtomicReference<>(
                "{\"token\":\"Bearer secret-value\"}");
        ScheduledSourceExecutor source = sourceExecutor(1, context ->
                ScheduledDiscoveryResult.withCheckpoint(new ScheduledCheckpoint(
                        "fixture.checkpoint", 1,
                        checkpointPayload.get())));
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
            }
            return ScheduledGuardDecision.proceed();
        });

        assertThatThrownBy(() -> engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()), guard).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.payload-invalid"));
        checkpointPayload.set("{\"cursor\":\"fixture-secret\"}");
        assertThatThrownBy(() -> engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()), guard).execute(task()))
                .isInstanceOfSatisfying(
                        ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.checkpoint.payload-invalid"));
        assertThat(failureGuards).hasValue(2);
    }

    @Test
    @DisplayName("旧 checkpoint 与凭证策略状态中的原始凭证在回送插件前被拒绝")
    void storedCredentialArtifactsAreRejectedBeforePluginCallbacks() throws Exception {
        AtomicBoolean discovered = new AtomicBoolean();
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger guards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            discovered.set(true);
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return POLICY;
            }

            @Override
            public ScheduledCredentialProbeResult probe(
                    ScheduledCredentialContext context) {
                probes.incrementAndGet();
                return ScheduledCredentialProbeResult.valid("account-1");
            }
        };
        List<StoredCredentialEchoCase> cases = List.of(
                new StoredCredentialEchoCase(
                        taskWithStoredArtifacts(
                                "fixture.checkpoint",
                                1,
                                "{\"cursor\":\"fixture-secret\"}",
                                "{}"),
                        "schedule.checkpoint.payload-invalid"),
                new StoredCredentialEchoCase(
                        taskWithStoredArtifacts(
                                null,
                                null,
                                null,
                                "{\"state\":\"fixture-secret\"}"),
                        "schedule.credential.invalid-policy-state"));

        for (StoredCredentialEchoCase echoCase : cases) {
            discovered.set(false);

            assertThatThrownBy(() -> engine(
                    storeWithCredential(),
                    source,
                    workExecutor(context -> ScheduledWorkResult.completed()),
                    policy,
                    guard(context -> {
                        guards.incrementAndGet();
                        return ScheduledGuardDecision.proceed();
                    }))
                    .execute(echoCase.task()))
                    .isInstanceOfSatisfying(
                            ScheduledExecutionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(echoCase.expectedCode()));
            assertThat(discovered).isFalse();
            assertThat(probes).hasValue(0);
            assertThat(guards).hasValue(0);
        }
    }

    @Test
    @DisplayName("不可迁移作品失败先耐久写入 pending 再阻止末尾 Guard 与 checkpoint")
    void unsupportedWorkBecomesTerminalAfterDurablePending() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        when(store.upsertPendingWork(any())).thenReturn(1);
        AtomicInteger endGuards = new AtomicInteger();
        AtomicInteger failureGuards = new AtomicInteger();
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(work("unsupported"));
            return ScheduledDiscoveryResult.withCheckpoint(
                    new ScheduledCheckpoint("fixture.checkpoint", 1, "{}"));
        });
        ScheduledWorkExecutor executor = workExecutor(context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "fixture.payload-unsupported");
        });
        ScheduledExecutionGuard guard = guard(context -> {
            if (context.point() == ScheduledGuardPoint.RUN_END) {
                endGuards.incrementAndGet();
            }
            if (context.point() == ScheduledGuardPoint.RUN_FAILURE) {
                failureGuards.incrementAndGet();
            }
            return ScheduledGuardDecision.proceed();
        });

        assertThatThrownBy(() -> engine(
                store, source, executor,
                credentialPolicy(new AtomicReference<>()), guard).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("fixture.payload-unsupported"));
        verify(store).upsertPendingWork(any());
        assertThat(endGuards).hasValue(0);
        assertThat(failureGuards).hasValue(1);
    }

    @Test
    @DisplayName("pending 跨过重试上限后即使末尾 Guard 失败仍上报事件")
    void pendingExhaustionEventSurvivesLaterFailure() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        ScheduleWorkPersistenceCodec codec = new ScheduleWorkPersistenceCodec(new ObjectMapper());
        ScheduledPendingWork pending = codec.toPendingWork(
                1L, work("retry-001"), "fixture.retry", "{}", 4, 1L, 2L);
        when(store.listPendingWork(1L)).thenReturn(List.of(pending));
        when(store.upsertPendingWork(any())).thenReturn(1);
        ScheduledSourceExecutor source = sourceExecutor(
                1, context -> ScheduledDiscoveryResult.withoutCheckpoint());
        ScheduledWorkExecutor executor = workExecutor(context -> {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK, "fixture.retry");
        });
        ScheduledExecutionGuard guard = guard(context ->
                context.point() == ScheduledGuardPoint.RUN_END
                        ? new ScheduledGuardDecision(
                        ScheduledGuardDecision.Action.FAIL, "fixture.end-rejected", 0L)
                        : ScheduledGuardDecision.proceed());
        List<ScheduleExecutionResult.PendingExhausted> events = new ArrayList<>();

        assertThatThrownBy(() -> engine(
                store, source, executor,
                credentialPolicy(new AtomicReference<>()), guard)
                .execute(task(), events::add))
                .isInstanceOf(ScheduleExecutionControlException.class)
                .hasMessage("fixture.end-rejected");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.workId()).isEqualTo("retry-001");
            assertThat(event.attempts()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("作品池拒绝派发时先写 pending 再失败且不遗留在途计数")
    void rejectedDispatchIsPersistedBeforeFailure() throws Exception {
        ScheduledTaskStore store = storeWithCredential();
        when(store.upsertPendingWork(any())).thenReturn(1);
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(work("rejected"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        TaskExecutor rejectingExecutor = task -> {
            throw new IllegalStateException("executor stopped");
        };
        ScheduleExecutionEngine engine = engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed()),
                new ScheduleRunState(), rejectingExecutor);

        assertThatThrownBy(() -> engine.execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.work.dispatch-failed"));
        verify(store).upsertPendingWork(any());
    }

    @Test
    @DisplayName("来源 work 在进入队列前拒绝凭证材料")
    void sourceWorkIsValidatedBeforeQueueing() throws Exception {
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(new ScheduledWork(
                    new ScheduledWorkKey(WORK, "unsafe"),
                    "fixture.work", 1,
                    "{\"cookie\":\"PHPSESSID=secret\"}",
                    ScheduledWorkPresentation.empty(), List.of()));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledTaskStore store = storeWithCredential();

        assertThatThrownBy(() -> engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                .isInstanceOfSatisfying(ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.work.payload-invalid"));
        verify(store, never()).upsertPendingWork(any());
    }

    @Test
    @DisplayName("来源作品中的原始凭证回显在进入队列前被拒绝")
    void sourceWorkRejectsExactCredentialEchoBeforeQueueing() throws Exception {
        ScheduledSourceExecutor source = sourceExecutor(1, context -> {
            context.workSink().submit(new ScheduledWork(
                    new ScheduledWorkKey(WORK, "exact-echo"),
                    "fixture.work",
                    1,
                    "{\"note\":\"fixture-secret\"}",
                    ScheduledWorkPresentation.empty(),
                    List.of()));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        });
        ScheduledTaskStore store = storeWithCredential();

        assertThatThrownBy(() -> engine(
                store, source,
                workExecutor(context -> ScheduledWorkResult.completed()),
                credentialPolicy(new AtomicReference<>()),
                guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                .isInstanceOfSatisfying(
                        ScheduledExecutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("schedule.work.payload-invalid"));
        verify(store, never()).upsertPendingWork(any());
    }

    @Test
    @DisplayName("作品结果属性含凭证形态时转安全终止失败且不进入队列投影")
    void unsafeWorkResultIsRejected() throws Exception {
        for (Map<String, String> unsafeAttributes : List.of(
                Map.of("title", "Cookie: PHPSESSID=secret"),
                Map.of("tokenCount", "opaque-token-value"),
                Map.of("cookiePresent", "opaque-cookie-value"),
                Map.of("tokenCountValue", "opaque-token-value"),
                Map.of("cookiePresentValue", "opaque-cookie-value"),
                Map.of("sidCountHeader", "opaque-session-value"),
                Map.of("tokenPresentCount", "opaque-token-value"),
                Map.of("cookieEnabledVersion", "opaque-cookie-value"),
                Map.of("sidCountPresent", "opaque-session-value"))) {
            ScheduledTaskStore store = storeWithCredential();
            when(store.upsertPendingWork(any())).thenReturn(1);
            ScheduledSourceExecutor source = sourceExecutor(1, context -> {
                context.workSink().submit(work("unsafe-result"));
                return ScheduledDiscoveryResult.withoutCheckpoint();
            });
            ScheduledWorkExecutor executor = workExecutor(context -> new ScheduledWorkResult(
                    ScheduledWorkResult.Outcome.COMPLETED,
                    "fixture.completed",
                    unsafeAttributes));

            assertThatThrownBy(() -> engine(
                    store, source, executor,
                    credentialPolicy(new AtomicReference<>()),
                    guard(context -> ScheduledGuardDecision.proceed())).execute(task()))
                    .isInstanceOfSatisfying(ScheduledExecutionException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("schedule.work.invalid-result"));
            verify(store).upsertPendingWork(any());
        }
    }

}
