package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.notification.NotificationDispatcher;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityLease;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialIncidentPresentation;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionControlException;
import top.sywyar.pixivdownload.schedule.execution.ScheduleCredentialCircuitOpenException;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;

/**
 * 计划任务 durable claim 的外层执行壳。插件能力解析、来源发现、作品执行、凭证、Guard、
 * pending 与候选 checkpoint 均由 {@link ScheduleExecutionEngine} 统一编排；本类只负责 claim/CAS
 * 收尾、挂起投影、运行状态清理和 best-effort 通知。
 */
@Slf4j
@PluginManagedBean
public class ScheduleExecutor {

    private final ScheduledTaskStore store;
    /** owner 原子来源与作品能力 registry；所有插件行为只在 generation lease 内调用。 */
    private final ScheduleCapabilityAccess scheduleCapabilityRegistry;
    private final ScheduleRunState runState;
    private final ObjectMapper objectMapper;
    private final MessageResolver messages;
    private final ScheduleExecutionEngine scheduleExecutionEngine;
    private final TransactionTemplate transactions;
    private final ScheduleHostIdentity hostIdentity;
    private final ScheduleNotificationService notifications;

    public ScheduleExecutor(
            ScheduledTaskStore store,
            ScheduleCapabilityAccess scheduleCapabilityRegistry,
            ScheduleRunState runState,
            ObjectMapper objectMapper,
            NotificationDispatcher notificationDispatcher,
            MessageResolver messages,
            NamespaceMessageResolver namespaceMessageResolver,
            UserDisplayNameProvider userDisplayNameProvider,
            ScheduleExecutionEngine scheduleExecutionEngine,
            TransactionTemplate transactions,
            ScheduleHostIdentity hostIdentity) {
        this.store = Objects.requireNonNull(store, "store");
        this.scheduleCapabilityRegistry = Objects.requireNonNull(
                scheduleCapabilityRegistry, "scheduleCapabilityRegistry");
        this.runState = Objects.requireNonNull(runState, "runState");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.scheduleExecutionEngine = Objects.requireNonNull(
                scheduleExecutionEngine, "scheduleExecutionEngine");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.hostIdentity = Objects.requireNonNull(hostIdentity, "hostIdentity");
        this.notifications = new ScheduleNotificationService(
                store,
                scheduleCapabilityRegistry,
                notificationDispatcher,
                messages,
                namespaceMessageResolver,
                userDisplayNameProvider);
    }

    /** {@code last_message} 失败原因摘要的最大长度（截断防止超长异常文本撑爆列）。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;

    static RuntimeException propagate(Throwable failure) {
        return ScheduleExecutor.<RuntimeException>throwUnchecked(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    static void addCleanupFailure(Throwable failure, Throwable cleanupFailure) {
        if (failure != cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 后台异步运行一个已经抢占瞬时态的任务。owner lease 必须由同步提交点在入队前取得并转交，
     * 使线程池排队时间也计入 generation drain；异步任务无论是否真正开始执行都会负责关闭它。
     */
    @Async("scheduleRunTaskExecutor")
    public void runTaskAsync(
            long taskId,
            ScheduleRunState.Claim claim,
            ScheduleRunToken queuedToken,
            ScheduleCapabilityLease<ScheduleCapabilityOwner> hostLease) {
        try (hostLease) {
            if (hostLease.cancellation().isCancellationRequested()) {
                try {
                    releaseQueued(taskId, queuedToken);
                } finally {
                    runState.clear(claim);
                }
                log.debug("Scheduled task {} queued run skipped: schedule host is quiesced", taskId);
                return;
            }
            runTaskAsyncLeased(taskId, claim, queuedToken, hostLease.cancellation());
        } catch (Throwable e) {
            try {
                releaseQueued(taskId, queuedToken);
            } catch (Throwable cleanupFailure) {
                addCleanupFailure(e, cleanupFailure);
            }
            try {
                runState.clear(claim);
            } catch (Throwable cleanupFailure) {
                addCleanupFailure(e, cleanupFailure);
            }
            throw propagate(e);
        }
    }

    private void runTaskAsyncLeased(
            long taskId,
            ScheduleRunState.Claim claim,
            ScheduleRunToken queuedToken,
            ScheduledCancellation hostCancellation) {
        ScheduledTask task = store.findById(taskId);
        if (task == null) {
            try {
                releaseQueued(taskId, queuedToken);
            } finally {
                runState.clear(claim);
            }
            return;
        }
        runTaskAndRecordLeased(task, claim, queuedToken, hostCancellation);
    }

    /**
     * 同步运行一个任务，并以 CAS 写回最近结果、下一次运行时间与候选 checkpoint。
     * 调度 tick 串行调用本方法；固定周期的下一次运行以本轮真实完成时间为基准。
     */
    public void runTaskAndRecord(ScheduledTask task) {
        ScheduleCapabilityLease<ScheduleCapabilityOwner> hostLease = prepareHostLease();
        try (hostLease) {
            if (hostLease == null || !scheduleCapabilityRegistry.activate(hostLease)) {
                log.debug("Scheduled task {} ({}) skipped: schedule host is quiesced", task.id(), task.name());
                return;
            }
            ScheduleRunState.Claim claim = runState.tryMarkRunning(task.id());
            if (claim == null) {
                log.debug("Scheduled task {} ({}) skipped: already queued or running", task.id(), task.name());
                return;
            }
            String claimToken = null;
            ScheduleRunToken queuedToken;
            try {
                claimToken = java.util.UUID.randomUUID().toString();
                queuedToken = store.tryQueueNow(task.id(), task.stateVersion(), claimToken)
                        .orElse(null);
            } catch (Throwable e) {
                try {
                    if (claimToken != null) {
                        releaseClaim(task.id(), claimToken, task.nextRunTime());
                    }
                } catch (Throwable cleanupFailure) {
                    addCleanupFailure(e, cleanupFailure);
                }
                try {
                    runState.clear(claim);
                } catch (Throwable cleanupFailure) {
                    addCleanupFailure(e, cleanupFailure);
                }
                throw propagate(e);
            }
            if (queuedToken == null) {
                runState.clear(claim);
                log.debug("Scheduled task {} ({}) skipped: durable claim rejected", task.id(), task.name());
                return;
            }
            runTaskAndRecordLeased(task, claim, queuedToken, hostLease.cancellation());
        }
    }

    void runTaskAndRecord(
            ScheduledTask task,
            ScheduleRunState.Claim claim,
            ScheduleRunToken queuedToken) {
        ScheduleCapabilityLease<ScheduleCapabilityOwner> hostLease;
        try {
            hostLease = prepareHostLease();
        } catch (Throwable e) {
            try {
                releaseQueued(task.id(), queuedToken);
            } catch (Throwable cleanupFailure) {
                addCleanupFailure(e, cleanupFailure);
            }
            try {
                runState.clear(claim);
            } catch (Throwable cleanupFailure) {
                addCleanupFailure(e, cleanupFailure);
            }
            throw propagate(e);
        }
        if (hostLease == null) {
            try {
                releaseQueued(task.id(), queuedToken);
            } finally {
                runState.clear(claim);
            }
            log.debug("Scheduled task {} ({}) skipped: schedule host is quiesced", task.id(), task.name());
            return;
        }
        try (hostLease) {
            boolean activated;
            try {
                activated = scheduleCapabilityRegistry.activate(hostLease);
            } catch (Throwable e) {
                try {
                    releaseQueued(task.id(), queuedToken);
                } catch (Throwable cleanupFailure) {
                    addCleanupFailure(e, cleanupFailure);
                }
                try {
                    runState.clear(claim);
                } catch (Throwable cleanupFailure) {
                    addCleanupFailure(e, cleanupFailure);
                }
                throw propagate(e);
            }
            if (!activated) {
                try {
                    releaseQueued(task.id(), queuedToken);
                } finally {
                    runState.clear(claim);
                }
                log.debug("Scheduled task {} ({}) skipped: schedule host is quiesced", task.id(), task.name());
                return;
            }
            runTaskAndRecordLeased(task, claim, queuedToken, hostLease.cancellation());
        }
    }

    void releaseQueued(long taskId, ScheduleRunToken queuedToken) {
        RuntimeException releaseFailure = null;
        try {
            // QUEUED 释放不需要预读；null 由 SQL 的 COALESCE 保留当前 next_run_time。
            if (store.releaseQueued(taskId, queuedToken, null).isPresent()) {
                return;
            }
        } catch (RuntimeException e) {
            releaseFailure = e;
        }
        try {
            // release 与管理员挂起、startRun 的提交结果可能竞态；也覆盖一次性读写异常。
            finishAbandonedClaimWithRetry(
                    taskId, queuedToken.claimToken(), ScheduleLastOutcome.INTERRUPTED,
                    System.currentTimeMillis(), "CLAIM_ABANDONED", null, null);
        } catch (RuntimeException recoveryFailure) {
            if (releaseFailure != null) {
                releaseFailure.addSuppressed(recoveryFailure);
                throw releaseFailure;
            }
            throw recoveryFailure;
        }
    }

    /** 收敛 queue CAS 结果不确定的同 claim 行；若写入未发生或已由别人完成则为空操作。 */
    void releaseClaim(long taskId, String claimToken, Long nextRun) {
        finishAbandonedClaimWithRetry(
                taskId, claimToken, ScheduleLastOutcome.INTERRUPTED,
                System.currentTimeMillis(), "QUEUE_CLAIM_UNCERTAIN", null, nextRun);
    }

    /** tick 对数据库仍在途、但本进程已无内存镜像的孤儿 claim 做幂等收尾。 */
    void recoverOrphanedClaim(ScheduledTask task) {
        finishAbandonedClaimWithRetry(
                task.id(), task.runClaimToken(), ScheduleLastOutcome.INTERRUPTED,
                System.currentTimeMillis(), "ORPHANED_CLAIM", null, task.nextRunTime());
    }

    private OptionalLong finishAbandonedClaimWithRetry(
            long taskId,
            String claimToken,
            ScheduleLastOutcome fallbackOutcome,
            long finishedTime,
            String fallbackCode,
            String fallbackMessage,
            Long nextRun) {
        RuntimeException firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return finishAbandonedClaim(
                        taskId, claimToken, fallbackOutcome, finishedTime,
                        fallbackCode, fallbackMessage, nextRun);
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        throw Objects.requireNonNull(firstFailure);
    }

    /**
     * 只收尾仍由同一 claim 持有的在途行。QUEUED 优先重新释放；RUNNING/CANCEL_REQUESTED 则用当前
     * stateVersion 构造精确 token。SQL 会在并发挂起发生时以行内 reason/code/detail 为准。
     */
    private OptionalLong finishAbandonedClaim(
            long taskId,
            String claimToken,
            ScheduleLastOutcome fallbackOutcome,
            long finishedTime,
            String fallbackCode,
            String fallbackMessage,
            Long nextRun) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ScheduledTask current = store.findById(taskId);
            if (current == null
                    || current.runState() == null
                    || !Objects.equals(claimToken, current.runClaimToken())) {
                return OptionalLong.empty();
            }
            ScheduleRunToken activeToken = new ScheduleRunToken(
                    claimToken, current.stateVersion(), current.runState());
            Long effectiveNextRun = nextRun == null ? current.nextRunTime() : nextRun;
            if (current.runState()
                    == top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.QUEUED) {
                OptionalLong released = store.releaseQueued(taskId, activeToken, nextRun);
                if (released.isPresent()) {
                    return released;
                }
                continue;
            }
            OptionalLong finished = store.finishCancelled(
                    taskId, activeToken, fallbackOutcome, finishedTime,
                    fallbackCode, fallbackMessage, effectiveNextRun);
            if (finished.isPresent()) {
                return finished;
            }
        }
        throw new IllegalStateException("active schedule claim could not be finalized");
    }

    private ScheduleCapabilityLease<ScheduleCapabilityOwner> prepareHostLease() {
        return scheduleCapabilityRegistry.prepareOwner(hostIdentity.featurePluginId()).orElse(null);
    }

    /**
     * 对来源/执行器不可用任务做无网络 planning 探测。仅在当前 owner 与定义 schema 都匹配且所需作品执行器
     * 能取得同代复合租约时返回 true；所有租约都在返回前释放。
     */
    boolean canResolveExecution(ScheduledTask task) {
        try {
            return scheduleExecutionEngine.canResolve(task);
        } catch (Exception e) {
            log.debug("Scheduled task {} capability recovery probe failed: {}",
                    task.id(), e.getClass().getSimpleName());
            return false;
        }
    }

    private void runTaskAndRecordLeased(
            ScheduledTask task,
            ScheduleRunState.Claim claim,
            ScheduleRunToken queuedToken,
            ScheduledCancellation hostCancellation) {
        try {
            runTaskAndRecordLeasedBody(task, claim, queuedToken, hostCancellation);
        } catch (Throwable failure) {
            try {
                finishAbandonedClaimWithRetry(
                        task.id(), queuedToken.claimToken(), ScheduleLastOutcome.ERROR,
                        System.currentTimeMillis(), "UNCAUGHT_THROWABLE", null, task.nextRunTime());
            } catch (Throwable cleanupFailure) {
                addCleanupFailure(failure, cleanupFailure);
            }
            try {
                runState.clear(claim);
            } catch (Throwable cleanupFailure) {
                addCleanupFailure(failure, cleanupFailure);
            }
            throw propagate(failure);
        }
    }

    private void runTaskAndRecordLeasedBody(
            ScheduledTask task,
            ScheduleRunState.Claim claim,
            ScheduleRunToken queuedToken,
            ScheduledCancellation hostCancellation) {
        if (!runState.markRunning(claim)) {
            try {
                releaseQueued(task.id(), queuedToken);
            } finally {
                runState.clear(claim);
            }
            log.debug("Scheduled task {} ({}) skipped: stale run claim", task.id(), task.name());
            return;
        }
        ScheduleRunToken runningToken;
        try {
            runningToken = store.startRun(task.id(), queuedToken).orElse(null);
        } catch (RuntimeException e) {
            try {
                releaseQueued(task.id(), queuedToken);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
                log.error("Scheduled task {} could not release its claim after start failure",
                        task.id(), cleanupFailure);
            } finally {
                runState.clear(claim);
            }
            throw e;
        }
        if (runningToken == null) {
            try {
                releaseQueued(task.id(), queuedToken);
            } finally {
                runState.clear(claim);
            }
            log.debug("Scheduled task {} ({}) skipped: durable start rejected", task.id(), task.name());
            return;
        }
        ScheduleLastOutcome outcome = ScheduleLastOutcome.ERROR;
        String outcomeCode = null;
        String message = null;
        ScheduleCredentialSuspensionNotice suspendNotification = null;
        ScheduledCredentialIncidentPresentation policyAccountIncident =
                ScheduledCredentialIncidentPresentation.empty();
        long suspendTriggerTime = 0L;
        // 本轮是否因凭证失效但策略允许匿名继续；运行成功后据此发一次降级通知。
        boolean[] degraded = {false};
        int completedCount = 0;
        // 仅当 lastOutcome 由「非 ERROR」转入 ERROR 时才发失败通知（连续失败不重复打扰）；进入 catch 前先读旧状态。
        boolean notifyRunFailed = false;
        List<ScheduleNotificationService.PendingExhaustedNotification> pendingNotifications =
                Collections.synchronizedList(new ArrayList<>());
        AtomicReference<ScheduledCheckpoint> candidateCheckpoint = new AtomicReference<>();
        ScheduleSuspendReason requestedSuspend = null;
        boolean suspendPolicyAccount = false;
        String suspendCode = null;
        String suspendDetailJson = null;
        long retryAfterMillis = 0L;
        boolean[] policyAccountSuspensionPersisted = {false};
        AtomicReference<RuntimeException> policyAccountSuspensionFailure = new AtomicReference<>();
        try {
            ensureCapabilityAvailable(hostCancellation, task.sourceType());
            // 任务级代理覆盖调度主线程；作品执行器仍按解析后的中性 route 管理自己的网络作用域。
            OutboundProxyOverride.set(task.proxySnapshot());
            try {
                ScheduleExecutionResult result = scheduleExecutionEngine.execute(
                        task,
                        event -> pendingNotifications.add(
                                new ScheduleNotificationService.PendingExhaustedNotification(
                                event.workType(), event.workId(), event.attempts(),
                                event.triggerTime(), event.reasonCode(),
                                event.presentation())),
                        decision -> {
                            try {
                                suspendForRun(
                                        task,
                                        runningToken,
                                        ScheduleSuspendReason.POLICY,
                                        decision.reasonCode(),
                                        safeGuardDetailJson(decision),
                                        true);
                                policyAccountSuspensionPersisted[0] = true;
                            } catch (RuntimeException failure) {
                                policyAccountSuspensionFailure.set(failure);
                            }
                        });
                completedCount = result.completedWorkCount();
                candidateCheckpoint.set(result.candidateCheckpoint());
                degraded[0] = result.credentialRevoked();
            } finally {
                OutboundProxyOverride.clear();
            }
            ensureCapabilityAvailable(hostCancellation, task.sourceType());
            outcome = ScheduleLastOutcome.OK;
            log.info("Scheduled task {} ({}) completed {} new download(s)", task.id(), task.name(), completedCount);
        } catch (ScheduleExecutionControlException e) {
            outcomeCode = e.reasonCode();
            message = e.reasonCode();
            suspendDetailJson = safeGuardDetailJson(e);
            retryAfterMillis = e.retryAfterMillis();
            switch (e.action()) {
                case SUSPEND_CREDENTIAL -> {
                    requestedSuspend = ScheduleSuspendReason.CREDENTIAL;
                    suspendNotification = new ScheduleCredentialSuspensionNotice(
                            ScheduleCredentialSuspensionNotice.Reason.CREDENTIAL_REJECTED);
                    suspendTriggerTime = System.currentTimeMillis();
                }
                case SUSPEND_POLICY_TASK -> requestedSuspend = ScheduleSuspendReason.POLICY;
                case SUSPEND_POLICY_ACCOUNT -> {
                    requestedSuspend = ScheduleSuspendReason.POLICY;
                    suspendPolicyAccount = true;
                    policyAccountIncident = e.incidentPresentation();
                }
                case RETRY_LATER, FAIL, REVOKE_CREDENTIAL_AND_CONTINUE, CONTINUE -> {
                    outcome = ScheduleLastOutcome.ERROR;
                    notifyRunFailed = task.lastOutcome() != ScheduleLastOutcome.ERROR;
                }
            }
            suspendCode = requestedSuspend == null ? null : e.reasonCode();
            log.warn("Scheduled task {} ({}) stopped by execution policy: {}",
                    task.id(), task.name(), e.reasonCode());
        } catch (ScheduledExecutionException e) {
            outcomeCode = e.code();
            message = e.code();
            retryAfterMillis = e.retryAfterMillis();
            switch (e.category()) {
                case CANCELLED -> outcome = ScheduleLastOutcome.CANCELLED;
                case CREDENTIAL_INVALID -> {
                    requestedSuspend = ScheduleSuspendReason.CREDENTIAL;
                    suspendCode = e.code();
                    suspendTriggerTime = System.currentTimeMillis();
                    if (e instanceof ScheduleCredentialCircuitOpenException circuit) {
                        suspendDetailJson = safeDetailJson(
                                "consecutiveFailures", circuit.consecutiveFailures(),
                                "lastErrorExcerpt", circuit.lastFailureCode());
                        suspendNotification = new ScheduleCredentialSuspensionNotice(
                                ScheduleCredentialSuspensionNotice.Reason.FAILURE_CIRCUIT_OPEN,
                                circuit.consecutiveFailures(), circuit.lastFailureCode());
                    } else {
                        suspendNotification = new ScheduleCredentialSuspensionNotice(
                                ScheduleCredentialSuspensionNotice.Reason.CREDENTIAL_REJECTED);
                    }
                }
                case INVALID_DEFINITION, PAYLOAD_UNSUPPORTED -> {
                    requestedSuspend = ScheduleSuspendReason.MIGRATION_ERROR;
                    suspendCode = e.code();
                }
                default -> {
                    outcome = ScheduleLastOutcome.ERROR;
                    notifyRunFailed = task.lastOutcome() != ScheduleLastOutcome.ERROR;
                }
            }
            log.warn("Scheduled task {} ({}) execution failed: {}",
                    task.id(), task.name(), e.code());
        } catch (ScheduleSourceUnavailableException e) {
            requestedSuspend = hostCancellation.isCancellationRequested()
                    ? ScheduleSuspendReason.QUIESCED
                    : ScheduleSuspendReason.SOURCE_UNAVAILABLE;
            suspendCode = hostCancellation.isCancellationRequested()
                    ? "HOST_QUIESCED"
                    : "SOURCE_UNAVAILABLE";
            outcomeCode = suspendCode;
            message = e.getMessage();
            log.warn("Scheduled task {} ({}) source unavailable: {}", task.id(), task.name(), e.unresolvedType());
        } catch (ScheduleExecutorUnavailableException e) {
            requestedSuspend = ScheduleSuspendReason.EXECUTOR_UNAVAILABLE;
            suspendCode = "EXECUTOR_UNAVAILABLE";
            outcomeCode = suspendCode;
            message = e.getMessage();
            log.warn("Scheduled task {} ({}) work executor unavailable for source {}: {}",
                    task.id(), task.name(), e.sourceType(), e.requiredWorkTypes());
        } catch (ScheduleDefinitionException e) {
            requestedSuspend = ScheduleSuspendReason.MIGRATION_ERROR;
            suspendCode = "DEFINITION_INVALID";
            outcomeCode = suspendCode;
            message = summarizeError(e);
            suspendDetailJson = safeDetailJson("message", message, "sourceType", task.sourceType());
            log.warn("Scheduled task {} ({}) definition is invalid", task.id(), task.name());
        } catch (Exception e) {
            outcome = ScheduleLastOutcome.ERROR;
            outcomeCode = "UNEXPECTED_FAILURE";
            message = summarizeError(e);
            notifyRunFailed = task.lastOutcome() != ScheduleLastOutcome.ERROR;
            log.error("Scheduled task {} ({}) failed [{}]: {}",
                    task.id(), task.name(), e.getClass().getSimpleName(), message);
        }
        long completedAt = System.currentTimeMillis();
        Long nextRun = task.nextRunTime();
        OptionalLong persistedResult;
        try {
            nextRun = ScheduleTiming.computeNextRun(
                    task.triggerKind(), task.intervalMinutes(), task.cronExpr(), completedAt);
            if (retryAfterMillis > 0) {
                nextRun = Math.max(
                        nextRun == null ? 0L : nextRun,
                        saturatingFutureTime(completedAt, retryAfterMillis));
            }
            if (requestedSuspend != null) {
                if (suspendPolicyAccount) {
                    RuntimeException persistenceFailure = policyAccountSuspensionFailure.get();
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }
                    if (!policyAccountSuspensionPersisted[0]) {
                        throw new IllegalStateException(
                                "credential account suspension left publication barrier without persistence");
                    }
                } else {
                    suspendForRun(task, runningToken, requestedSuspend, suspendCode,
                            suspendDetailJson, false);
                }
                ScheduleLastOutcome cancelledOutcome = requestedSuspend == ScheduleSuspendReason.QUIESCED
                        ? ScheduleLastOutcome.CANCELLED
                        : ScheduleLastOutcome.ERROR;
                persistedResult = store.finishCancelled(
                        task.id(), runningToken, cancelledOutcome, completedAt,
                        outcomeCode, message, nextRun);
            } else if (outcome == ScheduleLastOutcome.CANCELLED) {
                persistedResult = store.finishCancelled(
                        task.id(), runningToken, outcome, completedAt,
                        outcomeCode, message, nextRun);
            } else {
                ScheduledCheckpoint checkpoint = outcome == ScheduleLastOutcome.OK
                        ? candidateCheckpoint.get()
                        : null;
                ScheduleRunCompletion completion = new ScheduleRunCompletion(
                        completedAt, outcome, outcomeCode, message, nextRun,
                        checkpoint == null ? null : checkpoint.schema(),
                        checkpoint == null ? null : checkpoint.version(),
                        checkpoint == null ? null : checkpoint.payloadJson());
                persistedResult = store.completeRun(task.id(), runningToken, completion);
                if (persistedResult.isEmpty()) {
                    persistedResult = finishConcurrentSuspend(task.id(), runningToken, completedAt, nextRun);
                }
            }
        } catch (RuntimeException e) {
            try {
                finishAbandonedClaimWithRetry(
                        task.id(), runningToken.claimToken(), ScheduleLastOutcome.ERROR,
                        completedAt, "FINALIZATION_FAILED", summarizeError(e), nextRun);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
                log.error("Scheduled task {} could not finish its claim after finalization failure",
                        task.id(), cleanupFailure);
            }
            throw e;
        } finally {
            runState.clear(claim);
        }
        if (persistedResult.isEmpty()) {
            finishAbandonedClaimWithRetry(
                    task.id(), runningToken.claimToken(), ScheduleLastOutcome.ERROR,
                    completedAt, "FINALIZATION_REJECTED", null, nextRun);
            log.error("Scheduled task {} ({}) durable completion was rejected", task.id(), task.name());
            return;
        }
        if (degraded[0]
                && requestedSuspend == null
                && outcome == ScheduleLastOutcome.OK) {
            String policyOwnerPluginId = task.credentialPolicyOwnerPluginId();
            String policyId = task.credentialPolicyId();
            OptionalLong removed = policyOwnerPluginId == null || policyId == null
                    ? OptionalLong.empty()
                    : store.removeCredential(
                            task.id(), persistedResult.getAsLong(), policyOwnerPluginId, policyId);
            if (removed.isEmpty() && policyOwnerPluginId != null && policyId != null) {
                log.info("Scheduled task {} kept credential changed concurrently after anonymous downgrade",
                        task.id());
            }
        }
        Long notificationNextRun = persistedNextRun(task.id(), nextRun);
        if (policyAccountIncident.scenarioId() != null) {
            notifications.handlePolicyAccountIncident(task, suspendCode, policyAccountIncident);
        }
        if (suspendNotification != null) {
            notifications.handleSuspend(task, suspendNotification, suspendTriggerTime);
        }
        notifications.sendPendingExhaustedNotifications(task, pendingNotifications, notificationNextRun);
        // ── 运行结束通知（best-effort，不影响调度）：成功时按「是否自动降级 / 是否有新下载」二选一，失败时按「转入 ERROR」发一次。──
        if (outcome == ScheduleLastOutcome.OK && requestedSuspend == null) {
            if (degraded[0]) {
                notifications.notifyDegradedAnonymous(task, completedCount, completedAt, notificationNextRun);
            } else if (completedCount > 0) {
                notifications.notifyRunSummary(task, completedCount, completedAt, notificationNextRun);
            }
        } else if (notifyRunFailed && requestedSuspend == null) {
            notifications.notifyRunFailure(task, message, completedAt, notificationNextRun);
        }
    }

    private void suspendForRun(
            ScheduledTask task,
            ScheduleRunToken runningToken,
            ScheduleSuspendReason reason,
            String code,
            String detailJson,
            boolean accountPolicy) {
        if (reason == ScheduleSuspendReason.POLICY
                && accountPolicy
                && task.credentialPolicyOwnerPluginId() != null
                && task.credentialPolicyId() != null
                && task.credentialAccountKey() != null
                && !task.credentialAccountKey().isBlank()) {
            List<ScheduledTask> affected = transactions.execute(status ->
                    suspendCredentialAccountWithCas(
                            task, runningToken, reason, code, detailJson));
            if (affected == null) {
                throw new IllegalStateException(
                        "credential account suspension transaction returned no result");
            }
            affected.forEach(affectedTask -> runState.requestCancel(affectedTask.id()));
            return;
        }
        store.suspend(task.id(), runningToken.stateVersion(), reason, code, detailJson);
    }

    private List<ScheduledTask> suspendCredentialAccountWithCas(
            ScheduledTask currentTask,
            ScheduleRunToken runningToken,
            ScheduleSuspendReason reason,
            String code,
            String detailJson) {
        String ownerPluginId = currentTask.credentialPolicyOwnerPluginId();
        String policyId = currentTask.credentialPolicyId();
        String accountKey = currentTask.credentialAccountKey();
        List<ScheduledTask> affected = new ArrayList<>();
        boolean currentTaskIncluded = false;
        for (ScheduledTask candidate : store.findByCredentialAccount(
                ownerPluginId, policyId, accountKey)) {
            if (candidate == null
                    || candidate.suspendReason() != null
                    || !Objects.equals(ownerPluginId,
                            candidate.credentialPolicyOwnerPluginId())
                    || !Objects.equals(policyId, candidate.credentialPolicyId())
                    || !Objects.equals(accountKey, candidate.credentialAccountKey())) {
                continue;
            }
            long expectedVersion = candidate.id() == currentTask.id()
                    ? runningToken.stateVersion()
                    : candidate.stateVersion();
            if (store.suspend(candidate.id(), expectedVersion, reason, code, detailJson)
                    .isEmpty()) {
                throw new IllegalStateException(
                        "credential account task changed during suspension");
            }
            affected.add(candidate);
            currentTaskIncluded |= candidate.id() == currentTask.id();
        }
        if (!currentTaskIncluded) {
            throw new IllegalStateException(
                    "running task left its credential account during suspension");
        }
        return List.copyOf(affected);
    }

    private OptionalLong finishConcurrentSuspend(
            long taskId,
            ScheduleRunToken runningToken,
            long completedAt,
            Long nextRun) {
        ScheduledTask current = store.findById(taskId);
        if (current == null
                || current.runState() != top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState.CANCEL_REQUESTED
                || !runningToken.claimToken().equals(current.runClaimToken())) {
            return OptionalLong.empty();
        }
        ScheduleLastOutcome outcome = current.suspendReason() == ScheduleSuspendReason.MANUAL
                || current.suspendReason() == ScheduleSuspendReason.QUIESCED
                ? ScheduleLastOutcome.CANCELLED
                : ScheduleLastOutcome.ERROR;
        return store.finishCancelled(
                taskId, runningToken, outcome, completedAt,
                current.suspendCode(), current.suspendDetailJson(), nextRun);
    }

    private String safeDetailJson(String firstKey, Object firstValue, String secondKey, Object secondValue) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            if (firstValue != null) detail.put(firstKey, firstValue);
            if (secondValue != null) detail.put(secondKey, secondValue);
            return objectMapper.writeValueAsString(detail);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String safeGuardDetailJson(ScheduleExecutionControlException decision) {
        try {
            Map<String, String> sanitized = new LinkedHashMap<>();
            decision.evidence().attributes().forEach((key, value) -> sanitized.put(
                    key, ScheduleCredentialRedactor.redact(value)));
            return objectMapper.writeValueAsString(sanitized);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    /**
     * 把异常压缩成可安全展示的失败原因摘要：取 {@code getMessage()}（缺失时退化为异常简单类名），
     * 折叠空白、统一脱敏 Cookie、Authorization、token 与签名凭证，并截断到
     * {@link #MAX_ERROR_MESSAGE_LENGTH}。
     */
    private static String summarizeError(Throwable e) {
        String raw = e.getMessage();
        if (raw == null || raw.isBlank()) {
            raw = e.getClass().getSimpleName();
        }
        String collapsed = ScheduleCredentialRedactor.redact(raw.replaceAll("\\s+", " ").trim());
        if (collapsed.length() > MAX_ERROR_MESSAGE_LENGTH) {
            collapsed = collapsed.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "…";
        }
        return collapsed;
    }

    private static long saturatingFutureTime(long baseTime, long delayMillis) {
        if (delayMillis <= 0L) {
            return baseTime;
        }
        return baseTime > Long.MAX_VALUE - delayMillis
                ? Long.MAX_VALUE
                : baseTime + delayMillis;
    }

    /** 更新运行结果后读取数据库中的真实 next_run_time；若读取失败则回退到本轮刚计算出的值。 */
    private Long persistedNextRun(long taskId, Long fallback) {
        try {
            ScheduledTask refreshed = store.findById(taskId);
            return refreshed == null ? fallback : refreshed.nextRunTime();
        } catch (RuntimeException e) {
            log.debug(messages.getForLog(
                    "schedule.log.next-run.reload-failed", taskId, e.getClass().getSimpleName()));
            return fallback;
        }
    }

    private static void ensureCapabilityAvailable(
            ScheduledCancellation cancellation, String unresolvedType)
            throws ScheduleSourceUnavailableException {
        if (cancellation.isCancellationRequested()) {
            throw new ScheduleSourceUnavailableException(unresolvedType + " (capability retired)");
        }
    }

}
