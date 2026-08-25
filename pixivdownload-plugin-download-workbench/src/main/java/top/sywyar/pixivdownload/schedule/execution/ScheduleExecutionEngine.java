package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledPendingReplayPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunStatistics;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.ScheduleDefinitionException;
import top.sywyar.pixivdownload.schedule.ScheduleExecutorUnavailableException;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleSourcePublicationChangedException;
import top.sywyar.pixivdownload.schedule.ScheduleSourceUnavailableException;
import top.sywyar.pixivdownload.schedule.definition.ScheduleExecutionPlanGate;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionSafety.*;

/**
 * 插件中性的单轮执行引擎。它固定复合租约、route、credential、Guard、背压、pending、finalizer 与
 * checkpoint 门控顺序；站点来源、作品下载和风险判断只经 plugin-api 能力调用。
 */
public final class ScheduleExecutionEngine {

    /** 单个计划任务允许的宿主级最大在途作品数。 */
    public static final int MAX_WORK_IN_FLIGHT = ScheduleExecutionPlanGate.MAX_IN_FLIGHT;

    private final ScheduledTaskStore store;
    private final ScheduleCapabilityAccess registry;
    private final ScheduleRunState runState;
    private final ScheduleRunQueue runQueue;
    private final ScheduleConfig config;
    private final ScheduleWorkPersistenceCodec persistenceCodec;
    private final ScheduleNetworkRouteResolver routeResolver;
    private final TaskExecutor workTaskExecutor;
    private final ScheduleWorkConcurrencyLimiter workConcurrencyLimiter;
    private final ObjectMapper objectMapper;
    private final ScheduleCredentialSupport credentialSupport;

    ScheduleExecutionEngine(
            ScheduledTaskStore store,
            ScheduleCapabilityAccess registry,
            ScheduleRunState runState,
            ScheduleRunQueue runQueue,
            ScheduleConfig config,
            ScheduleWorkPersistenceCodec persistenceCodec,
            ScheduleNetworkRouteResolver routeResolver,
            TaskExecutor workTaskExecutor,
            ObjectMapper objectMapper) {
        this(store, registry, runState, runQueue, config, persistenceCodec, routeResolver,
                workTaskExecutor, new ScheduleWorkConcurrencyLimiter(), objectMapper);
    }

    public ScheduleExecutionEngine(
            ScheduledTaskStore store,
            ScheduleCapabilityAccess registry,
            ScheduleRunState runState,
            ScheduleRunQueue runQueue,
            ScheduleConfig config,
            ScheduleWorkPersistenceCodec persistenceCodec,
            ScheduleNetworkRouteResolver routeResolver,
            TaskExecutor workTaskExecutor,
            ScheduleWorkConcurrencyLimiter workConcurrencyLimiter,
            ObjectMapper objectMapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.runState = Objects.requireNonNull(runState, "runState");
        this.runQueue = Objects.requireNonNull(runQueue, "runQueue");
        this.config = Objects.requireNonNull(config, "config");
        this.persistenceCodec = Objects.requireNonNull(persistenceCodec, "persistenceCodec");
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver");
        this.workTaskExecutor = Objects.requireNonNull(workTaskExecutor, "workTaskExecutor");
        this.workConcurrencyLimiter = Objects.requireNonNull(
                workConcurrencyLimiter, "workConcurrencyLimiter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.credentialSupport = new ScheduleCredentialSupport(store, objectMapper);
    }

    public boolean canResolve(ScheduledTask task) {
        SchedulePlanningLease planning = registry.prepareSource(task.sourceType()).orElse(null);
        try (planning) {
            if (planning == null || !registry.activate(planning)
                    || !matchesTask(task, planning) || planning.sourceExecutor().isEmpty()) {
                return false;
            }
            ScheduledSourceDescriptor descriptor = planning.descriptor().orElseThrow();
            ScheduledExecutionPlan plan = requirePlan(
                    descriptor,
                    invokeSourcePlan(planning.sourceExecutor().orElseThrow(), toDefinition(task)));
            validateStoredCheckpoint(plan, task);
            ScheduleExecutionLease execution = registry.prepareExpansion(planning, plan).orElse(null);
            try (execution) {
                return execution != null && registry.activate(execution)
                        && !execution.cancellation().isCancellationRequested();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 为一次凭证绑定取得来源 plan 声明的完整复合租约，并解析任务级 route。调用方须持有返回租约直到 CAS 完成。
     */
    public ScheduleCredentialBindingLease prepareCredentialBinding(
            ScheduledTask task,
            String expectedActivationToken)
            throws ScheduleSourceUnavailableException,
            ScheduleSourcePublicationChangedException,
            ScheduleExecutorUnavailableException,
            ScheduleDefinitionException,
            ScheduledExecutionException {
        Objects.requireNonNull(task, "task");
        SchedulePlanningLease planning = registry.prepareSource(task.sourceType()).orElse(null);
        if (planning != null
                && !Objects.equals(expectedActivationToken, planning.activationToken())) {
            planning.close();
            throw new ScheduleSourcePublicationChangedException(task.sourceType());
        }
        if (planning == null) {
            throw new ScheduleSourceUnavailableException(task.sourceType());
        }
        try (planning) {
        try {
            if (!registry.activate(planning)) {
                planning.close();
                throw new ScheduleSourceUnavailableException(task.sourceType());
            }
        } catch (ScheduleSourceUnavailableException failure) {
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.source.activation-failed");
        }
        if (!matchesTask(task, planning) || planning.sourceExecutor().isEmpty()) {
            planning.close();
            throw new ScheduleSourceUnavailableException(task.sourceType());
        }
        ScheduledTaskDefinition definition;
        ScheduledSourceDescriptor descriptor;
        ScheduledSourceExecutor sourceExecutor;
        try {
            definition = toDefinition(task);
            descriptor = planning.descriptor().orElseThrow();
            validateDefinition(task, descriptor);
            sourceExecutor = planning.sourceExecutor().orElseThrow();
        } catch (ScheduleDefinitionException failure) {
            planning.close();
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.source.definition-failed");
        }
        ScheduledExecutionPlan plan;
        try {
            plan = invokeSourcePlan(sourceExecutor, definition);
        } catch (ScheduledExecutionException failure) {
            planning.close();
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.source.plan-failed");
        }
        try {
            plan = requirePlan(descriptor, plan);
        } catch (ScheduledExecutionException failure) {
            planning.close();
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.plan.capability-mismatch");
        }
        if (plan.credentialRequirement() == ScheduledCredentialRequirement.NONE) {
            planning.close();
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.credential.binding-not-supported");
        }
        ScheduleExecutionLease execution;
        try {
            execution = registry.prepareExpansion(planning, plan).orElse(null);
        } catch (IllegalArgumentException failure) {
            planning.close();
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.plan.capability-mismatch");
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.plan.expansion-failed");
        }
        if (execution == null) {
            planning.close();
            throw new ScheduleExecutorUnavailableException(
                    task.sourceType(), plan.requiredWorkTypes());
        }
        try {
            if (!registry.activate(execution)) {
                throw new ScheduleExecutorUnavailableException(
                        task.sourceType(), plan.requiredWorkTypes());
            }
            planning.close();
            execution.cancellation().throwIfCancellationRequested();
            ScheduleCapabilityOwner policyOwner = execution.credentialPolicyOwner()
                    .orElseThrow(() -> pluginFailure("schedule.credential.policy-unavailable"));
            if (execution.credentialPolicy().isEmpty()) {
                throw pluginFailure("schedule.credential.policy-unavailable");
            }
            ScheduledNetworkRoute route = resolveRoute(task, plan);
            return new ScheduleCredentialBindingLease(
                    credentialSupport, task.id(), policyOwner.featurePluginId(), plan.credentialPolicyId(),
                    execution, definition, route);
        } catch (ScheduleExecutorUnavailableException failure) {
            execution.close();
            planning.close();
            throw failure;
        } catch (ScheduleDefinitionException | ScheduledExecutionException failure) {
            execution.close();
            planning.close();
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(execution, planning, failure);
            throw pluginFailure("schedule.credential.binding-prepare-failed");
        }
        }
    }

    public ScheduleExecutionResult execute(ScheduledTask task)
            throws ScheduleSourceUnavailableException,
            ScheduleExecutorUnavailableException,
            ScheduleDefinitionException,
            ScheduleExecutionControlException,
            ScheduledExecutionException {
        return execute(task, ignored -> {
        }, ignored -> {
        });
    }

    public ScheduleExecutionResult execute(
            ScheduledTask task,
            Consumer<ScheduleExecutionResult.PendingExhausted> pendingExhaustedListener)
            throws ScheduleSourceUnavailableException,
            ScheduleExecutorUnavailableException,
            ScheduleDefinitionException,
            ScheduleExecutionControlException,
            ScheduledExecutionException {
        return execute(task, pendingExhaustedListener, ignored -> {
        });
    }

    /**
     * 执行一轮计划，并在账号级策略决定离开复合租约前发布其宿主持久化操作。
     *
     * <p>{@code policyAccountSuspensionPublisher} 只会在全部插件回调完成后、复合 execution lease 的
     * 精确 publication barrier 内调用；实现只可执行宿主事务，不得回调插件能力。
     */
    public ScheduleExecutionResult execute(
            ScheduledTask task,
            Consumer<ScheduleExecutionResult.PendingExhausted> pendingExhaustedListener,
            Consumer<ScheduleExecutionControlException> policyAccountSuspensionPublisher)
            throws ScheduleSourceUnavailableException,
            ScheduleExecutorUnavailableException,
            ScheduleDefinitionException,
            ScheduleExecutionControlException,
            ScheduledExecutionException {
        Objects.requireNonNull(pendingExhaustedListener, "pendingExhaustedListener");
        Objects.requireNonNull(
                policyAccountSuspensionPublisher, "policyAccountSuspensionPublisher");
        SchedulePlanningLease planning = registry.prepareSource(task.sourceType()).orElse(null);
        if (planning == null) {
            throw new ScheduleSourceUnavailableException(task.sourceType());
        }
        try (planning) {
        try {
            if (!registry.activate(planning)) {
                planning.close();
                throw new ScheduleSourceUnavailableException(task.sourceType());
            }
        } catch (ScheduleSourceUnavailableException failure) {
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.source.activation-failed");
        }
        if (!matchesTask(task, planning) || planning.sourceExecutor().isEmpty()) {
            planning.close();
            throw new ScheduleSourceUnavailableException(task.sourceType());
        }
        ScheduledTaskDefinition definition;
        ScheduledSourceDescriptor descriptor;
        ScheduledSourceExecutor sourceExecutor;
        try {
            definition = toDefinition(task);
            descriptor = planning.descriptor().orElseThrow();
            validateDefinition(task, descriptor);
            sourceExecutor = planning.sourceExecutor().orElseThrow();
        } catch (ScheduleDefinitionException failure) {
            planning.close();
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.source.definition-failed");
        }
        ScheduledExecutionPlan plan;
        try {
            plan = invokeSourcePlan(sourceExecutor, definition);
        } catch (ScheduledExecutionException failure) {
            planning.close();
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.source.plan-failed");
        }
        try {
            plan = requirePlan(descriptor, plan);
        } catch (ScheduledExecutionException failure) {
            planning.close();
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.plan.capability-mismatch");
        }
        ScheduledCheckpoint storedCheckpoint;
        try {
            storedCheckpoint = validateStoredCheckpoint(plan, task);
        } catch (ScheduledExecutionException failure) {
            planning.close();
            throw failure;
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.checkpoint.payload-invalid");
        }

        ScheduleExecutionLease execution;
        try {
            execution = registry.prepareExpansion(planning, plan).orElse(null);
        } catch (IllegalArgumentException failure) {
            planning.close();
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.plan.capability-mismatch");
        } catch (Throwable failure) {
            closeBeforeFatalPropagation(planning, failure);
            throw pluginFailure("schedule.plan.expansion-failed");
        }
        if (execution == null) {
            planning.close();
            throw new ScheduleExecutorUnavailableException(task.sourceType(), plan.requiredWorkTypes());
        }
        try {
            if (!registry.activate(execution)) {
                throw new ScheduleExecutorUnavailableException(
                        task.sourceType(), plan.requiredWorkTypes());
            }
            ScheduledCancellation leaseCancellation = execution.cancellation();
            ScheduledCancellation cancellation = () -> leaseCancellation.isCancellationRequested()
                    || runState.isCancelRequested(task.id());
            cancellation.throwIfCancellationRequested();
            Map<String, Integer> workConcurrencyLimits = resolveWorkConcurrencyLimits(
                    execution.workExecutors(), plan.maxInFlight());
            List<ScheduledPendingWork> pendingRows = validatePending(task, execution);
            ScheduledNetworkRoute route = resolveRoute(task, plan);

            boolean credentialRevoked = false;
            ScheduleExecutionResult result;
            cancellation.throwIfCancellationRequested();
            ScheduleCredentialMaterial credential = credentialSupport.load(task, execution, plan);
            try (credential) {
                credentialSupport.validateStoredArtifacts(
                        task, storedCheckpoint, credential);
                ScheduleGuardInvoker guardInvoker = new ScheduleGuardInvoker(
                        task, definition, route, cancellation, credential, execution, plan,
                        store, runState);
                ScheduleCredentialSupport.ProbeOutcome probeOutcome;
                try {
                    probeOutcome = credentialSupport.probeForRun(
                            task, definition, route, cancellation, credential, execution, plan);
                } catch (Throwable primary) {
                    DeferredFatal fatalFailures = new DeferredFatal();
                    fatalFailures.capture(primary);
                    propagateFailure(
                            guardInvoker, credential, 0L, primary, fatalFailures);
                    throw new AssertionError("unreachable");
                }
                credentialRevoked = probeOutcome.revoked();

                try {
                    credentialRevoked |= guardInvoker.invoke(ScheduledGuardPoint.RUN_START, 0L, null);
                    AtomicReference<ScheduleWorkCoordinator> coordinatorRef = new AtomicReference<>();
                    ScheduleRunQueue.Run queue = runQueue.begin(task.id());
                    ScheduleWorkCoordinator coordinator = new ScheduleWorkCoordinator(
                            task.id(), definition, route, cancellation, credential,
                            store, persistenceCodec, objectMapper,
                            execution.workExecutors(),
                            execution.workExecutorOwners(),
                            execution.workExecutorPublicationIds(),
                            queue,
                            workTaskExecutor, workConcurrencyLimiter,
                            plan.maxInFlight(), workConcurrencyLimits,
                            config.getPendingMaxAttempts(),
                            config.getAuthFailureCircuitBreaker(), plan.politeDelayMillis(),
                            attempted -> {
                                if (guardInvoker.hasBatchGuardAt(attempted)) {
                                    coordinatorRef.get().drain();
                                    guardInvoker.invoke(
                                            ScheduledGuardPoint.WORK_BATCH, attempted, null);
                                }
                            }, pendingExhaustedListener);
                    coordinatorRef.set(coordinator);
                    ScheduledDiscoveryResult discovery;
                    try {
                        coordinator.loadPending(pendingRows);
                        ScheduledPendingReplayPolicy pendingReplayPolicy =
                                sourceExecutor.pendingReplayPolicy();
                        if (pendingReplayPolicy == ScheduledPendingReplayPolicy.ALWAYS) {
                            coordinator.replayUnseenPending(pendingReplayPolicy);
                        }
                        cancellation.throwIfCancellationRequested();
                        try (var sourceHandle = credential.openHandle()) {
                            ScheduledSourceContext context = new ScheduledSourceContext() {
                                @Override
                                public ScheduledCheckpoint checkpoint() {
                                    return storedCheckpoint;
                                }

                                @Override
                                public top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledWorkSink workSink() {
                                    return coordinator;
                                }

                                @Override
                                public boolean isPending(top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey key) {
                                    return coordinator.isPending(key);
                                }

                                @Override
                                public ScheduledTaskDefinition task() {
                                    return definition;
                                }

                                @Override
                                public ScheduledNetworkRoute route() {
                                    return route;
                                }

                                @Override
                                public top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle credential() {
                                    return sourceHandle;
                                }

                                @Override
                                public ScheduledCancellation cancellation() {
                                    return cancellation;
                                }
                            };
                            try {
                                discovery = sourceExecutor.discover(context);
                            } catch (ScheduledExecutionException failure) {
                                throw safePluginException(
                                        failure, "schedule.execution.invalid-failure-code",
                                        credential);
                            }
                            if (discovery == null) {
                                throw pluginFailure("schedule.source.null-result");
                            }
                            validateCandidateCheckpoint(
                                    plan, discovery.candidateCheckpoint(), credential);
                            discovery = copyDiscoveryResult(discovery);
                        }
                        if (pendingReplayPolicy == ScheduledPendingReplayPolicy.REDISCOVERED_ONLY) {
                            coordinator.replayUnseenPending(pendingReplayPolicy);
                        }
                        coordinator.stopAccepting();
                        coordinator.drain();
                        cancellation.throwIfCancellationRequested();
                        finishExecutors(definition, route, cancellation, credential,
                                execution.workExecutors(), coordinator.statistics());
                        guardInvoker.invoke(
                                ScheduledGuardPoint.RUN_END, coordinator.attemptedWorkCount(), null);
                        cancellation.throwIfCancellationRequested();
                        result = new ScheduleExecutionResult(
                                coordinator.completedWorkCount(),
                                discovery.candidateCheckpoint(),
                                credentialRevoked,
                                coordinator.pendingExhausted());
                    } catch (Throwable caught) {
                        Throwable primary = caught instanceof ScheduleWorkCoordinator.CoordinatorSignal signal
                                ? signal.failure()
                                : caught;
                        DeferredFatal fatalFailures = new DeferredFatal();
                        fatalFailures.capture(primary);
                        coordinator.stopAccepting();
                        try {
                            coordinator.drain();
                        } catch (Throwable drainFailure) {
                            // 非致命次生失败不能覆盖原始分类；fatal 延后到全部清理完成后传播。
                            fatalFailures.capture(drainFailure);
                        }
                        abortExecutors(
                                definition, execution.workExecutors(), fatalFailures);
                        propagateFailure(
                                guardInvoker, credential, coordinator.attemptedWorkCount(),
                                primary, fatalFailures);
                        throw new AssertionError("unreachable");
                    }
                } catch (ScheduleExecutionControlException | ScheduledExecutionException failure) {
                    throw failure;
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                    throw pluginFailure("schedule.execution.plugin-failure");
                }
            } catch (ScheduleExecutionControlException failure) {
                publishPolicyAccountSuspension(
                        task, execution, failure, policyAccountSuspensionPublisher);
                throw failure;
            }
            execution.close();
            if (leaseCancellation.isCancellationRequested()) {
                throw new ScheduleSourceUnavailableException(task.sourceType() + " (capability retired)");
            }
            if (runState.isCancelRequested(task.id())) {
                throw ScheduledExecutionException.cancelled();
            }
            return result;
        } finally {
            execution.close();
            planning.close();
        }
        }
    }

    private void publishPolicyAccountSuspension(
            ScheduledTask task,
            ScheduleExecutionLease execution,
            ScheduleExecutionControlException decision,
            Consumer<ScheduleExecutionControlException> publisher)
            throws ScheduleSourceUnavailableException {
        if (decision.action() != ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT) {
            return;
        }
        Optional<Boolean> published = registry.whileCurrentPublication(execution, () -> {
            publisher.accept(decision);
            return Boolean.TRUE;
        });
        if (published.isEmpty()) {
            throw new ScheduleSourceUnavailableException(
                    task.sourceType() + " (capability publication retired)");
        }
    }

    private ScheduledNetworkRoute resolveRoute(
            ScheduledTask task,
            ScheduledExecutionPlan plan) throws ScheduleDefinitionException {
        try {
            return routeResolver.resolve(task.proxySnapshot(), plan.sourceDefaultRoute());
        } catch (IllegalArgumentException failure) {
            throw new ScheduleDefinitionException("invalid schedule network route", failure);
        }
    }

    private List<ScheduledPendingWork> validatePending(
            ScheduledTask task,
            ScheduleExecutionLease execution) throws ScheduledExecutionException {
        List<ScheduledPendingWork> rows = List.copyOf(store.listPendingWork(task.id()));
        for (ScheduledPendingWork row : rows) {
            try {
                var work = persistenceCodec.fromPendingWork(row);
                if (!execution.workExecutors().containsKey(work.key().workType())) {
                    throw new IllegalArgumentException("pending work type is not in execution plan");
                }
            } catch (IllegalArgumentException failure) {
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                        "schedule.pending.payload-invalid");
            }
        }
        return rows;
    }

    private void finishExecutors(
            ScheduledTaskDefinition definition,
            ScheduledNetworkRoute route,
            ScheduledCancellation cancellation,
            ScheduleCredentialMaterial credential,
            Map<String, ScheduledWorkExecutor> executors,
            Map<String, ScheduledWorkRunStatistics> statistics) throws ScheduledExecutionException {
        ScheduledExecutionException firstFailure = null;
        DeferredFatal fatalFailures = new DeferredFatal();
        for (Map.Entry<String, ScheduledWorkExecutor> entry : executors.entrySet()) {
            String workType = entry.getKey();
            ScheduledWorkRunStatistics typeStatistics = statistics.get(workType);
            try {
                cancellation.throwIfCancellationRequested();
                try (var handle = credential.openHandle()) {
                    ScheduledWorkRunContext context = new ScheduledWorkRunContext() {
                        @Override
                        public String workType() {
                            return workType;
                        }

                        @Override
                        public ScheduledWorkRunStatistics statistics() {
                            return typeStatistics;
                        }

                        @Override
                        public ScheduledTaskDefinition task() {
                            return definition;
                        }

                        @Override
                        public ScheduledNetworkRoute route() {
                            return route;
                        }

                        @Override
                        public top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle credential() {
                            return handle;
                        }

                        @Override
                        public ScheduledCancellation cancellation() {
                            return cancellation;
                        }
                    };
                    try {
                        entry.getValue().finishRun(context);
                    } catch (ScheduledExecutionException failure) {
                        throw safePluginException(
                                failure, "schedule.work.finalizer-failed", credential);
                    }
                }
            } catch (ScheduledExecutionException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            } catch (Throwable failure) {
                if (!fatalFailures.capture(failure) && firstFailure == null) {
                    firstFailure = pluginFailure("schedule.work.finalizer-failed");
                }
            }
        }
        fatalFailures.rethrowIfPresent();
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static void abortExecutors(
            ScheduledTaskDefinition definition,
            Map<String, ScheduledWorkExecutor> executors,
            DeferredFatal fatalFailures) {
        for (ScheduledWorkExecutor executor : executors.values()) {
            try {
                executor.abortRun(definition);
            } catch (Throwable failure) {
                // 异常终止清理逐个 best-effort，不能阻断后续 executor 或覆盖原始失败。
                fatalFailures.capture(failure);
            }
        }
    }

    private void validateCandidateCheckpoint(
            ScheduledExecutionPlan plan,
            ScheduledCheckpoint checkpoint,
            ScheduleCredentialMaterial credential) throws ScheduledExecutionException {
        if (checkpoint == null) {
            return;
        }
        if (!Objects.equals(plan.checkpointSchema(), checkpoint.schema())
                || plan.checkpointVersion() != checkpoint.version()) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.checkpoint.plan-mismatch");
        }
        if (credential.containsEcho(checkpoint.schema())
                || credential.containsEchoInJson(
                objectMapper, checkpoint.payloadJson())) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.checkpoint.payload-invalid");
        }
        validateCheckpointPayload(checkpoint);
    }

    private static ScheduledDiscoveryResult copyDiscoveryResult(
            ScheduledDiscoveryResult discovery) {
        ScheduledCheckpoint checkpoint = discovery.candidateCheckpoint();
        if (checkpoint == null) {
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }
        return ScheduledDiscoveryResult.withCheckpoint(new ScheduledCheckpoint(
                checkpoint.schema(), checkpoint.version(), checkpoint.payloadJson()));
    }

    private void validateCheckpointPayload(ScheduledCheckpoint checkpoint)
            throws ScheduledExecutionException {
        try {
            persistenceCodec.validateCheckpoint(checkpoint);
        } catch (IllegalArgumentException failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.checkpoint.payload-invalid");
        }
    }

    private ScheduledCheckpoint validateStoredCheckpoint(
            ScheduledExecutionPlan plan,
            ScheduledTask task) throws ScheduledExecutionException {
        boolean hasSchema = task.checkpointSchema() != null;
        boolean hasVersion = task.checkpointVersion() != null;
        boolean hasPayload = task.checkpointJson() != null;
        if (!hasSchema && !hasVersion && !hasPayload) {
            return null;
        }
        if (!hasSchema || !hasVersion || !hasPayload) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.checkpoint.invalid-envelope");
        }
        if (!Objects.equals(plan.checkpointSchema(), task.checkpointSchema())
                || plan.checkpointVersion() != task.checkpointVersion()) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.checkpoint.plan-mismatch");
        }
        try {
            ScheduledCheckpoint checkpoint = new ScheduledCheckpoint(
                    task.checkpointSchema(), task.checkpointVersion(), task.checkpointJson());
            validateCheckpointPayload(checkpoint);
            return checkpoint;
        } catch (IllegalArgumentException failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.checkpoint.invalid-envelope");
        }
    }

    private ScheduledTaskDefinition toDefinition(ScheduledTask task) throws ScheduleDefinitionException {
        if (task.definitionVersion() == null || task.definitionVersion() <= 0) {
            throw new ScheduleDefinitionException("schedule definition version is missing");
        }
        ScheduledTaskPresentation presentation = ScheduledTaskPresentation.empty();
        if (task.presentationJson() != null && !task.presentationJson().isBlank()) {
            try {
                presentation = objectMapper.readValue(
                        task.presentationJson(), ScheduledTaskPresentation.class);
            } catch (Exception failure) {
                throw new ScheduleDefinitionException("invalid schedule presentation", failure);
            }
        }
        try {
            return new ScheduledTaskDefinition(
                    task.id(), task.sourceType(), task.definitionSchema(), task.definitionVersion(),
                    task.definitionJson(), presentation);
        } catch (IllegalArgumentException failure) {
            throw new ScheduleDefinitionException("invalid schedule definition envelope", failure);
        }
    }

    private static void validateDefinition(ScheduledTask task, ScheduledSourceDescriptor descriptor)
            throws ScheduleDefinitionException {
        if (!Objects.equals(task.sourceType(), descriptor.sourceType())
                || !Objects.equals(task.definitionSchema(), descriptor.definitionSchema())
                || !Objects.equals(task.definitionVersion(), descriptor.definitionVersion())) {
            throw new ScheduleDefinitionException("schedule definition does not match source descriptor");
        }
    }

    private static ScheduledExecutionPlan requirePlan(
            ScheduledSourceDescriptor descriptor,
            ScheduledExecutionPlan plan)
            throws ScheduledExecutionException {
        try {
            return ScheduleExecutionPlanGate.validate(descriptor, plan);
        } catch (ScheduleExecutionPlanGate.Violation failure) {
            throw switch (failure.reason()) {
                case NULL_PLAN -> pluginFailure("schedule.source.null-plan");
                case MAX_IN_FLIGHT_TOO_LARGE -> new ScheduledExecutionException(
                        ScheduledFailure.Category.INVALID_DEFINITION,
                        "schedule.plan.max-in-flight-too-large");
                case WORK_BATCH_TOO_LARGE -> new ScheduledExecutionException(
                        ScheduledFailure.Category.INVALID_DEFINITION,
                        "schedule.plan.guard-batch-too-large");
                case DUPLICATE_GUARD, UNDECLARED_WORK_TYPE,
                        UNDECLARED_CREDENTIAL_POLICY, UNDECLARED_GUARD ->
                        new ScheduledExecutionException(
                                ScheduledFailure.Category.INVALID_DEFINITION,
                                "schedule.plan.capability-mismatch");
            };
        }
    }

    private static ScheduledExecutionPlan invokeSourcePlan(
            ScheduledSourceExecutor sourceExecutor,
            ScheduledTaskDefinition definition) throws ScheduledExecutionException {
        try {
            return sourceExecutor.plan(definition);
        } catch (ScheduledExecutionException failure) {
            throw safePluginException(failure, "schedule.source.plan-failed");
        } catch (Throwable failure) {
            rethrowFatal(failure);
            throw pluginFailure("schedule.source.plan-failed");
        }
    }

    private static Map<String, Integer> resolveWorkConcurrencyLimits(
            Map<String, ScheduledWorkExecutor> executors,
            int planMaxInFlight) throws ScheduledExecutionException {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (Map.Entry<String, ScheduledWorkExecutor> entry : executors.entrySet()) {
            int executorLimit;
            try {
                executorLimit = entry.getValue().maxConcurrency();
            } catch (Throwable failure) {
                rethrowFatal(failure);
                throw pluginFailure("schedule.work.concurrency-limit-failed");
            }
            if (executorLimit <= 0) {
                throw pluginFailure("schedule.work.concurrency-limit-invalid");
            }
            limits.put(entry.getKey(), Math.min(planMaxInFlight, executorLimit));
        }
        return Map.copyOf(limits);
    }

    private static boolean matchesTask(ScheduledTask task, SchedulePlanningLease planning) {
        return planning != null
                && Objects.equals(task.sourceOwnerPluginId(), planning.owner().featurePluginId())
                && Objects.equals(task.sourceType(), planning.sourceType());
    }

    private static ScheduledFailure safeFailure(
            Throwable failure,
            ScheduleCredentialMaterial credential) {
        if (failure instanceof ScheduledExecutionException scheduled) {
            return safePluginException(
                    scheduled, "schedule.execution.invalid-failure-code", credential).toFailure();
        }
        if (failure instanceof ScheduleExecutionControlException control) {
            if (credential.containsEcho(control.reasonCode())
                    || containsEcho(control.evidence(), credential)) {
                return new ScheduledFailure(
                        ScheduledFailure.Category.INTERNAL,
                        "schedule.execution.invalid-failure-code",
                        0L);
            }
            return new ScheduledFailure(
                    ScheduledFailure.Category.INTERNAL, control.reasonCode(), control.retryAfterMillis());
        }
        return new ScheduledFailure(
                ScheduledFailure.Category.INTERNAL, "schedule.execution.failed", 0L);
    }

    private void propagateFailure(
            ScheduleGuardInvoker guardInvoker,
            ScheduleCredentialMaterial credential,
            long attempted,
            Throwable primary,
            DeferredFatal fatalFailures)
            throws ScheduleExecutionControlException, ScheduledExecutionException {
        ScheduleExecutionControlException guardDecision = null;
        if (!fatalFailures.hasFailure()
                && !(primary instanceof ScheduleExecutionControlException)) {
            ScheduledFailure safeFailure;
            try {
                safeFailure = safeFailure(primary, credential);
            } catch (Throwable failureProjectionFailure) {
                fatalFailures.capture(failureProjectionFailure);
                safeFailure = new ScheduledFailure(
                        ScheduledFailure.Category.INTERNAL,
                        "schedule.execution.failed",
                        0L);
            }
            if (!fatalFailures.hasFailure()
                    && safeFailure.category() != ScheduledFailure.Category.CANCELLED) {
                guardDecision = guardInvoker.invokeFailureOnce(
                        attempted, safeFailure, fatalFailures);
            }
        }
        fatalFailures.rethrowIfPresent();
        if (guardDecision != null) {
            throw guardDecision;
        }
        rethrow(primary, credential);
    }

}
