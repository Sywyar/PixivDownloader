package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
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
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 插件中性的单轮执行引擎。它固定复合租约、route、credential、Guard、背压、pending、finalizer 与
 * checkpoint 门控顺序；站点来源、作品下载和风险判断只经 plugin-api 能力调用。
 */
public final class ScheduleExecutionEngine {

    /** 单个计划任务允许的宿主级最大在途作品数。 */
    public static final int MAX_WORK_IN_FLIGHT = ScheduleExecutionPlanGate.MAX_IN_FLIGHT;

    private final ScheduledTaskStore store;
    private final ScheduleCapabilityRegistry registry;
    private final ScheduleRunState runState;
    private final ScheduleRunQueue runQueue;
    private final ScheduleConfig config;
    private final ScheduleWorkPersistenceCodec persistenceCodec;
    private final ScheduleNetworkRouteResolver routeResolver;
    private final TaskExecutor workTaskExecutor;
    private final ScheduleWorkConcurrencyLimiter workConcurrencyLimiter;
    private final ObjectMapper objectMapper;

    ScheduleExecutionEngine(
            ScheduledTaskStore store,
            ScheduleCapabilityRegistry registry,
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
            ScheduleCapabilityRegistry registry,
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
            ScheduledNetworkRoute route;
            try {
                route = routeResolver.resolve(task.proxySnapshot());
            } catch (IllegalArgumentException failure) {
                throw new ScheduleDefinitionException("invalid schedule network route", failure);
            }
            return new ScheduleCredentialBindingLease(
                    this, task.id(), policyOwner.featurePluginId(), plan.credentialPolicyId(),
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
        Objects.requireNonNull(pendingExhaustedListener, "pendingExhaustedListener");
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
            ScheduledNetworkRoute route;
            try {
                route = routeResolver.resolve(task.proxySnapshot());
            } catch (IllegalArgumentException failure) {
                throw new ScheduleDefinitionException("invalid schedule network route", failure);
            }

            boolean credentialRevoked = false;
            ScheduleExecutionResult result;
            cancellation.throwIfCancellationRequested();
            ScheduleCredentialMaterial credential = loadCredential(task, execution, plan);
            try (credential) {
                GuardInvoker guardInvoker = new GuardInvoker(
                        task, definition, route, cancellation, credential, execution, plan);
                CredentialProbeOutcome probeOutcome;
                try {
                    probeOutcome = probeCredential(
                            task, definition, route, cancellation, credential, execution, plan);
                } catch (Throwable primary) {
                    DeferredFatal fatalFailures = new DeferredFatal();
                    fatalFailures.capture(primary);
                    propagateFailure(guardInvoker, 0L, primary, fatalFailures);
                    throw new AssertionError("unreachable");
                }
                credentialRevoked = probeOutcome.revoked();

                try {
                    credentialRevoked |= guardInvoker.invoke(ScheduledGuardPoint.RUN_START, 0L, null);
                    AtomicReference<ScheduleWorkCoordinator> coordinatorRef = new AtomicReference<>();
                    ScheduleRunQueue.Run queue = runQueue.begin(
                            task.id(), plan.requiredWorkTypes().stream().sorted().findFirst().orElse("work"));
                    ScheduleWorkCoordinator coordinator = new ScheduleWorkCoordinator(
                            task.id(), definition, route, cancellation, credential,
                            store, persistenceCodec, execution.workExecutors(), queue,
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
                            discovery = sourceExecutor.discover(context);
                        }
                        if (discovery == null) {
                            throw pluginFailure("schedule.source.null-result");
                        }
                        validateCandidateCheckpoint(plan, discovery.candidateCheckpoint());
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
                                guardInvoker, coordinator.attemptedWorkCount(), primary, fatalFailures);
                        throw new AssertionError("unreachable");
                    }
                } catch (ScheduleExecutionControlException | ScheduledExecutionException failure) {
                    throw failure;
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                    throw pluginFailure("schedule.execution.plugin-failure");
                }
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

    private ScheduleCredentialMaterial loadCredential(
            ScheduledTask task,
            ScheduleExecutionLease execution,
            ScheduledExecutionPlan plan) {
        ScheduleCapabilityOwner actualOwner = execution.credentialPolicyOwner().orElse(null);
        boolean bindingMatches = actualOwner != null
                && Objects.equals(task.credentialPolicyOwnerPluginId(), actualOwner.featurePluginId())
                && Objects.equals(task.credentialPolicyId(), plan.credentialPolicyId())
                && task.credentialSecretReference() != null;
        String secret = bindingMatches
                ? store.findCredentialSecret(
                        task.id(), actualOwner.featurePluginId(), plan.credentialPolicyId())
                : null;
        return new ScheduleCredentialMaterial(
                secret,
                bindingMatches ? task.credentialSecretReference() : null,
                bindingMatches ? task.credentialAccountKey() : null);
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

    private CredentialProbeOutcome probeCredential(
            ScheduledTask task,
            ScheduledTaskDefinition definition,
            ScheduledNetworkRoute route,
            ScheduledCancellation cancellation,
            ScheduleCredentialMaterial credential,
            ScheduleExecutionLease execution,
            ScheduledExecutionPlan plan)
            throws ScheduleExecutionControlException, ScheduledExecutionException {
        cancellation.throwIfCancellationRequested();
        if (plan.credentialRequirement() == ScheduledCredentialRequirement.NONE) {
            return CredentialProbeOutcome.KEPT;
        }
        if (!credential.isPresent()) {
            if (plan.credentialRequirement() == ScheduledCredentialRequirement.REQUIRED) {
                throw control(ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                        "schedule.credential.required", 0L, ScheduledGuardEvidence.empty());
            }
            return CredentialProbeOutcome.KEPT;
        }
        var policy = execution.credentialPolicy().orElseThrow(() -> new ScheduledExecutionException(
                ScheduledFailure.Category.INTERNAL, "schedule.credential.policy-unavailable"));
        ScheduledCredentialProbeResult probe;
        try (var handle = credential.openHandle()) {
            ScheduledCredentialContext context = new ScheduledCredentialContext() {
                @Override
                public Purpose purpose() {
                    return Purpose.RUN_START;
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
                probe = policy.probe(context);
            } catch (ScheduledExecutionException failure) {
                throw safePluginException(failure, "schedule.credential.probe-failed");
            } catch (Throwable failure) {
                rethrowFatal(failure);
                throw pluginFailure("schedule.credential.probe-failed");
            }
        }
        if (probe == null) {
            throw pluginFailure("schedule.credential.null-result");
        }
        if (!isSafeMachineCode(probe.code())
                || !isSafeAccountKey(probe.accountKey())) {
            throw pluginFailure("schedule.credential.invalid-result");
        }
        return switch (probe.status()) {
            case VALID -> {
                if (task.credentialAccountKey() != null
                        && !Objects.equals(task.credentialAccountKey(), probe.accountKey())) {
                    throw control(ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                            "schedule.credential.account-mismatch", 0L, ScheduledGuardEvidence.empty());
                }
                credential.setAccountKey(probe.accountKey());
                yield CredentialProbeOutcome.KEPT;
            }
            case INVALID -> {
                if (plan.anonymousFallbackAllowed()) {
                    credential.close();
                    yield CredentialProbeOutcome.REVOKED;
                }
                throw control(ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                        probe.code(), 0L, ScheduledGuardEvidence.empty());
            }
            case RETRY_LATER -> throw control(ScheduledGuardDecision.Action.RETRY_LATER,
                    probe.code(), probe.retryAfterMillis(), ScheduledGuardEvidence.empty());
        };
    }

    ScheduledCredentialBindResult probeCredentialForBinding(
            long taskId,
            ScheduledTaskDefinition definition,
            ScheduledNetworkRoute route,
            ScheduleExecutionLease execution,
            String candidateSecret) throws ScheduledExecutionException {
        ScheduledCancellation cancellation = execution.cancellation();
        cancellation.throwIfCancellationRequested();
        var policy = execution.credentialPolicy().orElseThrow(() -> pluginFailure(
                "schedule.credential.policy-unavailable"));
        ScheduledCredentialBindResult result;
        try (ScheduleCredentialMaterial credential = new ScheduleCredentialMaterial(
                candidateSecret, "scheduled-task:" + taskId + ":credential", null);
             var handle = credential.openHandle()) {
            ScheduledCredentialContext context = new ScheduledCredentialContext() {
                @Override
                public Purpose purpose() {
                    return Purpose.BIND;
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
                result = policy.probeForBinding(context);
            } catch (ScheduledExecutionException failure) {
                throw safePluginException(failure, "schedule.credential.bind-probe-failed");
            } catch (Throwable failure) {
                rethrowFatal(failure);
                throw pluginFailure("schedule.credential.bind-probe-failed");
            }
        }
        cancellation.throwIfCancellationRequested();
        if (result == null || result.probeResult() == null
                || !isSafeMachineCode(result.probeResult().code())
                || !isSafeAccountKey(result.probeResult().accountKey())) {
            throw pluginFailure("schedule.credential.invalid-bind-result");
        }
        ScheduledGuardDecision decision = result.postBindResult().decision();
        if (decision.action() != ScheduledGuardDecision.Action.CONTINUE
                && !isSafeMachineCode(decision.reasonCode())) {
            throw pluginFailure("schedule.credential.invalid-bind-result");
        }
        try {
            String initialPolicyStateJson = validateInitialPolicyState(
                    result.initialPolicyStateJson());
            ScheduledCredentialProbeResult probe = new ScheduledCredentialProbeResult(
                    result.probeResult().status(), result.probeResult().accountKey(),
                    result.probeResult().code(), result.probeResult().retryAfterMillis());
            ScheduledGuardResult postBind = new ScheduledGuardResult(
                    new ScheduledGuardDecision(
                            decision.action(), decision.reasonCode(), decision.retryAfterMillis()),
                    sanitizeEvidence(result.postBindResult().evidence()));
            return new ScheduledCredentialBindResult(
                    probe, initialPolicyStateJson, postBind);
        } catch (ScheduledExecutionException failure) {
            throw failure;
        } catch (RuntimeException ignored) {
            throw pluginFailure("schedule.credential.invalid-bind-result");
        }
    }

    private String validateInitialPolicyState(String initialPolicyStateJson)
            throws ScheduledExecutionException {
        ObjectReader strictReader = objectMapper.readerFor(JsonNode.class).with(
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode root = readStrictPolicyJson(strictReader, initialPolicyStateJson);
        if (root == null || !root.isObject()) {
            throw pluginFailure("schedule.credential.invalid-policy-state");
        }
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeFirst();
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (ScheduleCredentialRedactor.isSensitiveFieldName(field.getKey())) {
                        throw pluginFailure("schedule.credential.invalid-policy-state");
                    }
                    pending.addLast(field.getValue());
                }
            } else if (node.isArray()) {
                node.forEach(pending::addLast);
            } else if (node.isTextual()) {
                String text = node.textValue();
                if (ScheduleCredentialRedactor.containsCredentialMaterial(text)) {
                    throw pluginFailure("schedule.credential.invalid-policy-state");
                }
                JsonNode embedded = readEmbeddedPolicyJson(strictReader, text);
                if (embedded != null) {
                    pending.addLast(embedded);
                }
            }
        }
        return initialPolicyStateJson;
    }

    private JsonNode readStrictPolicyJson(ObjectReader strictReader, String json)
            throws ScheduledExecutionException {
        try {
            return strictReader.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            throw pluginFailure("schedule.credential.invalid-policy-state");
        }
    }

    private JsonNode readEmbeddedPolicyJson(ObjectReader strictReader, String text)
            throws ScheduledExecutionException {
        String candidate = text == null ? "" : text.trim();
        if (!candidate.startsWith("{") && !candidate.startsWith("[")) {
            return null;
        }
        try {
            JsonNode nested = strictReader.readTree(candidate);
            return nested != null && (nested.isObject() || nested.isArray()) ? nested : null;
        } catch (JsonProcessingException | IllegalArgumentException strictFailure) {
            try {
                JsonNode permissive = objectMapper.readTree(candidate);
                if (permissive != null && (permissive.isObject() || permissive.isArray())) {
                    throw pluginFailure("schedule.credential.invalid-policy-state");
                }
            } catch (ScheduledExecutionException failure) {
                throw failure;
            } catch (JsonProcessingException | IllegalArgumentException ignored) {
                // 以花括号开头的普通文本不是嵌套 JSON，不按策略状态解释。
            }
            return null;
        }
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
                    entry.getValue().finishRun(context);
                }
            } catch (ScheduledExecutionException failure) {
                try {
                    ScheduledExecutionException safeFailure = safePluginException(
                            failure, "schedule.work.finalizer-failed");
                    if (firstFailure == null) {
                        firstFailure = safeFailure;
                    }
                } catch (Throwable failureProjectionFailure) {
                    if (!fatalFailures.capture(failureProjectionFailure)
                            && firstFailure == null) {
                        firstFailure = pluginFailure("schedule.work.finalizer-failed");
                    }
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
            ScheduledCheckpoint checkpoint) throws ScheduledExecutionException {
        if (checkpoint == null) {
            return;
        }
        if (!Objects.equals(plan.checkpointSchema(), checkpoint.schema())
                || plan.checkpointVersion() != checkpoint.version()) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.checkpoint.plan-mismatch");
        }
        validateCheckpointPayload(checkpoint);
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

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    /** 先完成租约释放，再决定 fatal 是否必须原样越过插件异常归一边界。 */
    private static void closeBeforeFatalPropagation(AutoCloseable lease, Throwable failure) {
        DeferredFatal fatalFailures = new DeferredFatal();
        fatalFailures.capture(failure);
        try {
            lease.close();
        } catch (Throwable closeFailure) {
            if (!fatalFailures.capture(closeFailure) && failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        fatalFailures.rethrowIfPresent();
    }

    private static void closeBeforeFatalPropagation(
            AutoCloseable first,
            AutoCloseable second,
            Throwable failure) {
        DeferredFatal fatalFailures = new DeferredFatal();
        fatalFailures.capture(failure);
        closeForPropagation(first, failure, fatalFailures);
        closeForPropagation(second, failure, fatalFailures);
        fatalFailures.rethrowIfPresent();
    }

    private static void closeForPropagation(
            AutoCloseable lease,
            Throwable failure,
            DeferredFatal fatalFailures) {
        try {
            lease.close();
        } catch (Throwable closeFailure) {
            if (!fatalFailures.capture(closeFailure) && failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * best-effort 清理不能因首个失败中断；fatal 仍必须在全部清理完成后原样传播，后续 fatal 作为 suppressed 保留。
     */
    private static final class DeferredFatal {
        private Error first;

        boolean capture(Throwable failure) {
            Error fatal = fatalError(failure);
            if (fatal == null) {
                return false;
            }
            if (first == null) {
                first = fatal;
            } else if (first != fatal) {
                first.addSuppressed(fatal);
            }
            return true;
        }

        boolean hasFailure() {
            return first != null;
        }

        void rethrowIfPresent() {
            if (first == null) {
                return;
            }
            throw first;
        }

        private static Error fatalError(Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                return fatal;
            }
            if (failure instanceof ThreadDeath fatal) {
                return fatal;
            }
            return null;
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

    private static ScheduledExecutionException pluginFailure(String code) {
        return new ScheduledExecutionException(ScheduledFailure.Category.INTERNAL, code);
    }

    private static ScheduledFailure safeFailure(Throwable failure) {
        if (failure instanceof ScheduledExecutionException scheduled) {
            return safePluginException(
                    scheduled, "schedule.execution.invalid-failure-code").toFailure();
        }
        if (failure instanceof ScheduleExecutionControlException control) {
            return new ScheduledFailure(
                    ScheduledFailure.Category.INTERNAL, control.reasonCode(), control.retryAfterMillis());
        }
        return new ScheduledFailure(
                ScheduledFailure.Category.INTERNAL, "schedule.execution.failed", 0L);
    }

    private void propagateFailure(
            GuardInvoker guardInvoker,
            long attempted,
            Throwable primary,
            DeferredFatal fatalFailures)
            throws ScheduleExecutionControlException, ScheduledExecutionException {
        ScheduleExecutionControlException guardDecision = null;
        if (!fatalFailures.hasFailure()
                && !(primary instanceof ScheduleExecutionControlException)) {
            ScheduledFailure safeFailure;
            try {
                safeFailure = safeFailure(primary);
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
        rethrow(primary);
    }

    private static ScheduleExecutionControlException control(
            ScheduledGuardDecision.Action action,
            String code,
            long retryAfterMillis,
            ScheduledGuardEvidence evidence) {
        return new ScheduleExecutionControlException(action, code, retryAfterMillis, evidence);
    }

    private static void rethrow(Throwable failure)
            throws ScheduleExecutionControlException, ScheduledExecutionException {
        rethrowFatal(failure);
        if (failure instanceof ScheduleExecutionControlException control) {
            throw control;
        }
        if (failure instanceof ScheduledExecutionException scheduled) {
            throw safePluginException(scheduled, "schedule.execution.invalid-failure-code");
        }
        throw pluginFailure("schedule.execution.failed");
    }

    private static ScheduledExecutionException safePluginException(
            ScheduledExecutionException failure,
            String fallbackCode) {
        if (failure instanceof ScheduleCredentialCircuitOpenException circuitOpen) {
            return circuitOpen;
        }
        try {
            String code = failure.code();
            if (!isSafeMachineCode(code)) {
                return pluginFailure(fallbackCode);
            }
            return new ScheduledExecutionException(
                    failure.category(), code, failure.retryAfterMillis());
        } catch (Throwable projectionFailure) {
            rethrowFatal(projectionFailure);
            return pluginFailure(fallbackCode);
        }
    }

    private static boolean isSafeMachineCode(String code) {
        return code != null
                && code.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                && !ScheduleCredentialRedactor.containsCredentialMaterial(code);
    }

    private static boolean isSafeAccountKey(String accountKey) {
        return accountKey == null
                || (accountKey.length() <= 256
                && !ScheduleCredentialRedactor.containsCredentialMaterial(accountKey));
    }

    private static ScheduledGuardEvidence sanitizeEvidence(ScheduledGuardEvidence evidence) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        evidence.attributes().forEach((key, value) -> sanitized.put(
                key, ScheduleCredentialRedactor.redact(value)));
        return new ScheduledGuardEvidence(sanitized);
    }

    private enum CredentialProbeOutcome {
        KEPT(false),
        REVOKED(true);

        private final boolean revoked;

        CredentialProbeOutcome(boolean revoked) {
            this.revoked = revoked;
        }

        boolean revoked() {
            return revoked;
        }
    }

    private final class GuardInvoker {
        private final ScheduledTask taskRow;
        private final ScheduledTaskDefinition definition;
        private final ScheduledNetworkRoute route;
        private final ScheduledCancellation cancellation;
        private final ScheduleCredentialMaterial credential;
        private final ScheduleExecutionLease execution;
        private final ScheduledExecutionPlan plan;
        private boolean failureInvoked;
        private ScheduleExecutionControlException failureDecision;

        private GuardInvoker(
                ScheduledTask taskRow,
                ScheduledTaskDefinition definition,
                ScheduledNetworkRoute route,
                ScheduledCancellation cancellation,
                ScheduleCredentialMaterial credential,
                ScheduleExecutionLease execution,
                ScheduledExecutionPlan plan) {
            this.taskRow = taskRow;
            this.definition = definition;
            this.route = route;
            this.cancellation = cancellation;
            this.credential = credential;
            this.execution = execution;
            this.plan = plan;
        }

        boolean hasBatchGuardAt(long attempted) {
            return plan.guards().stream().anyMatch(binding ->
                    binding.points().contains(ScheduledGuardPoint.WORK_BATCH)
                            && attempted % binding.workBatchSize() == 0);
        }

        boolean invoke(ScheduledGuardPoint point, long attempted, ScheduledFailure failure)
                throws ScheduleExecutionControlException, ScheduledExecutionException {
            if (point != ScheduledGuardPoint.RUN_FAILURE) {
                cancellation.throwIfCancellationRequested();
            }
            boolean revoked = false;
            for (ScheduledGuardBinding binding : plan.guards()) {
                if (!binding.points().contains(point)) {
                    continue;
                }
                if (point == ScheduledGuardPoint.WORK_BATCH
                        && attempted % binding.workBatchSize() != 0) {
                    continue;
                }
                if (point != ScheduledGuardPoint.RUN_FAILURE) {
                    cancellation.throwIfCancellationRequested();
                }
                ScheduledExecutionGuard guard = execution.guard(binding.guardId())
                        .orElseThrow(() -> pluginFailure("schedule.guard.unavailable"));
                ScheduledGuardResult result = invokeOne(guard, binding.guardId(), point, attempted, failure);
                ScheduledGuardDecision decision = result.decision();
                if (decision.action() == ScheduledGuardDecision.Action.CONTINUE) {
                    continue;
                }
                if (!isSafeMachineCode(decision.reasonCode())) {
                    throw pluginFailure("schedule.guard.invalid-result");
                }
                if (decision.action() == ScheduledGuardDecision.Action.REVOKE_CREDENTIAL_AND_CONTINUE
                        && point == ScheduledGuardPoint.RUN_START
                        && plan.anonymousFallbackAllowed()) {
                    credential.close();
                    revoked = true;
                    continue;
                }
                throw control(decision.action(), decision.reasonCode(),
                        decision.retryAfterMillis(), sanitizeEvidence(result.evidence()));
            }
            return revoked;
        }

        ScheduleExecutionControlException invokeFailureOnce(
                long attempted,
                ScheduledFailure failure,
                DeferredFatal fatalFailures) {
            if (failureInvoked) {
                return failureDecision;
            }
            failureInvoked = true;
            for (ScheduledGuardBinding binding : plan.guards()) {
                if (!binding.points().contains(ScheduledGuardPoint.RUN_FAILURE)) {
                    continue;
                }
                try {
                    ScheduledExecutionGuard guard = execution.guard(binding.guardId())
                            .orElseThrow(() -> pluginFailure("schedule.guard.unavailable"));
                    ScheduledGuardResult result = invokeOne(
                            guard, binding.guardId(), ScheduledGuardPoint.RUN_FAILURE,
                            attempted, failure);
                    ScheduledGuardDecision decision = result.decision();
                    if (decision.action() == ScheduledGuardDecision.Action.CONTINUE
                            || decision.action()
                            == ScheduledGuardDecision.Action.REVOKE_CREDENTIAL_AND_CONTINUE) {
                        // 失败轮次不能伪装成匿名降级成功；凭证撤销只允许走成功返回通道。
                        continue;
                    }
                    if (!isSafeMachineCode(decision.reasonCode())) {
                        throw pluginFailure("schedule.guard.invalid-result");
                    }
                    ScheduleExecutionControlException candidate = control(
                            decision.action(), decision.reasonCode(), decision.retryAfterMillis(),
                            sanitizeEvidence(result.evidence()));
                    if (failureDecision == null) {
                        failureDecision = candidate;
                    }
                } catch (Throwable guardFailure) {
                    // 每个 failure Guard 独立 best-effort；非致命失败不覆盖主失败，fatal 延后传播。
                    fatalFailures.capture(guardFailure);
                }
            }
            return failureDecision;
        }

        private ScheduledGuardResult invokeOne(
                ScheduledExecutionGuard guard,
                String guardId,
                ScheduledGuardPoint point,
                long attempted,
                ScheduledFailure failure) throws ScheduledExecutionException {
            ScheduleCapabilityOwner guardOwner = execution.guardOwner(guardId).orElse(null);
            ScheduleCapabilityOwner policyOwner = execution.credentialPolicyOwner().orElse(null);
            Optional<String> policyState = guardOwner != null && guardOwner.equals(policyOwner)
                    ? Optional.ofNullable(taskRow.credentialPolicyStateJson())
                    : Optional.empty();
            try (var handle = credential.openHandle()) {
                ScheduledGuardContext context = new ScheduledGuardContext() {
                    @Override
                    public ScheduledGuardPoint point() {
                        return point;
                    }

                    @Override
                    public long attemptedWorkCount() {
                        return attempted;
                    }

                    @Override
                    public Optional<String> credentialPolicyStateJson() {
                        return policyState;
                    }

                    @Override
                    public ScheduledFailure failure() {
                        return failure;
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
                    ScheduledGuardResult result = guard.evaluateResult(context);
                    if (result == null) {
                        throw pluginFailure("schedule.guard.null-result");
                    }
                    return result;
                } catch (ScheduledExecutionException scheduled) {
                    throw safePluginException(scheduled, "schedule.guard.plugin-failure");
                } catch (Throwable callbackFailure) {
                    rethrowFatal(callbackFailure);
                    throw pluginFailure("schedule.guard.plugin-failure");
                }
            }
        }

    }
}
