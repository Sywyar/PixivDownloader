package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.i18n.AppLocale;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.core.notification.NotificationService;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleSingleCapabilityLease;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionControlException;
import top.sywyar.pixivdownload.schedule.execution.ScheduleCredentialCircuitOpenException;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionResult;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;

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
    private final ScheduleCapabilityRegistry scheduleCapabilityRegistry;
    private final ScheduleRunState runState;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final AppMessages messages;
    private final WebI18nBundleRegistry webI18nBundleRegistry;
    private final UserDisplayNameProvider userDisplayNameProvider;
    private final ScheduleExecutionEngine scheduleExecutionEngine;

    public ScheduleExecutor(
            ScheduledTaskStore store,
            ScheduleCapabilityRegistry scheduleCapabilityRegistry,
            ScheduleRunState runState,
            ObjectMapper objectMapper,
            NotificationService notificationService,
            AppMessages messages,
            WebI18nBundleRegistry webI18nBundleRegistry,
            UserDisplayNameProvider userDisplayNameProvider,
            ScheduleExecutionEngine scheduleExecutionEngine) {
        this.store = Objects.requireNonNull(store, "store");
        this.scheduleCapabilityRegistry = Objects.requireNonNull(
                scheduleCapabilityRegistry, "scheduleCapabilityRegistry");
        this.runState = Objects.requireNonNull(runState, "runState");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.webI18nBundleRegistry = webI18nBundleRegistry;
        this.userDisplayNameProvider = Objects.requireNonNull(
                userDisplayNameProvider, "userDisplayNameProvider");
        this.scheduleExecutionEngine = Objects.requireNonNull(
                scheduleExecutionEngine, "scheduleExecutionEngine");
    }

    /** {@code last_message} 失败原因摘要的最大长度（截断防止超长异常文本撑爆列）。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;
    /** 过度访问通知里逐条列出受影响任务的最大条数，超出附「等共 N 个」。 */
    private static final int TASK_LIST_LIMIT = 15;

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
    @Async
    public void runTaskAsync(
            long taskId,
            ScheduleRunState.Claim claim,
            ScheduleRunToken queuedToken,
            ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> hostLease) {
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
        ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> hostLease = prepareHostLease();
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
        ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> hostLease;
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

    private ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> prepareHostLease() {
        var handle = scheduleCapabilityRegistry.resolveOwner(DownloadWorkbenchPlugin.ID).orElse(null);
        if (handle == null) {
            return null;
        }
        return scheduleCapabilityRegistry.prepareAcquire(handle).orElse(null);
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
        ScheduleSuspendException suspendNotification = null;
        OveruseWarningException overuseNotification = null;
        long suspendTriggerTime = 0L;
        // 本轮是否因凭证失效但策略允许匿名继续；运行成功后据此发一次降级通知。
        boolean[] degraded = {false};
        int completedCount = 0;
        // 仅当 lastOutcome 由「非 ERROR」转入 ERROR 时才发失败通知（连续失败不重复打扰）；进入 catch 前先读旧状态。
        boolean notifyRunFailed = false;
        List<PendingExhaustedNotification> pendingNotifications =
                Collections.synchronizedList(new ArrayList<>());
        AtomicReference<ScheduledCheckpoint> candidateCheckpoint = new AtomicReference<>();
        ScheduleSuspendReason requestedSuspend = null;
        boolean suspendPolicyAccount = false;
        String suspendCode = null;
        String suspendDetailJson = null;
        long retryAfterMillis = 0L;
        try {
            ensureCapabilityAvailable(hostCancellation, task.sourceType());
            // 任务级代理覆盖调度主线程；作品执行器仍按解析后的中性 route 管理自己的网络作用域。
            OutboundProxyOverride.set(task.proxySnapshot());
            try {
                ScheduleExecutionResult result = scheduleExecutionEngine.execute(task, event ->
                        pendingNotifications.add(new PendingExhaustedNotification(
                                event.workType(), event.workId(), event.attempts(),
                                event.triggerTime(), event.reasonCode())));
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
                    suspendNotification = new ScheduleSuspendException(
                            ScheduleSuspendException.Reason.COOKIE_DEAD);
                    suspendTriggerTime = System.currentTimeMillis();
                }
                case SUSPEND_POLICY_TASK -> requestedSuspend = ScheduleSuspendReason.POLICY;
                case SUSPEND_POLICY_ACCOUNT -> {
                    requestedSuspend = ScheduleSuspendReason.POLICY;
                    suspendPolicyAccount = true;
                    if ("PIXIV_OVERUSE".equals(e.reasonCode())) {
                        long modifiedAt = parseLongOrZero(
                                e.evidence().attributes().get("modifiedAt"));
                        String excerpt = e.evidence().attributes().getOrDefault("excerpt", "");
                        overuseNotification = new OveruseWarningException(modifiedAt, excerpt);
                    }
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
                        suspendNotification = new ScheduleSuspendException(
                                ScheduleSuspendException.Reason.CIRCUIT_BREAKER,
                                circuit.consecutiveFailures(), circuit.lastFailureCode());
                    } else {
                        suspendNotification = new ScheduleSuspendException(
                                ScheduleSuspendException.Reason.COOKIE_DEAD);
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
                suspendForRun(task, runningToken, requestedSuspend, suspendCode,
                        suspendDetailJson, suspendPolicyAccount);
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
        if (overuseNotification != null) {
            handleOveruse(task, overuseNotification);
        }
        if (suspendNotification != null) {
            handleSuspend(task, suspendNotification, suspendTriggerTime);
        }
        sendPendingExhaustedNotifications(task, pendingNotifications, notificationNextRun);
        // ── 运行结束通知（best-effort，不影响调度）：成功时按「是否自动降级 / 是否有新下载」二选一，失败时按「转入 ERROR」发一次。──
        if (outcome == ScheduleLastOutcome.OK && requestedSuspend == null) {
            if (degraded[0]) {
                notifyDegradedAnonymous(task, completedCount, completedAt, notificationNextRun);
            } else if (completedCount > 0) {
                notifyRunSummary(task, completedCount, completedAt, notificationNextRun);
            }
        } else if (notifyRunFailed && requestedSuspend == null) {
            notifyRunFailure(task, message, completedAt, notificationNextRun);
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
                && task.credentialAccountKey() != null) {
            List<ScheduledTask> affected = store.findByCredentialAccount(
                    task.credentialPolicyOwnerPluginId(), task.credentialPolicyId(),
                    task.credentialAccountKey());
            store.suspendByCredentialAccount(
                    task.credentialPolicyOwnerPluginId(), task.credentialPolicyId(),
                    task.credentialAccountKey(), reason, code, detailJson);
            affected.forEach(affectedTask -> runState.requestCancel(affectedTask.id()));
            return;
        }
        store.suspend(task.id(), runningToken.stateVersion(), reason, code, detailJson);
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

    private static long parseLongOrZero(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
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

    /** 本轮中达到自动重试上限、需要在最终 next_run_time 确定后再发送的通知事件。 */
    private record PendingExhaustedNotification(
            String workType, String workId, int attempts, long triggerTime, String reason) {}

    // ── 自动挂起通知（邮件 + 推送并行，best-effort） ──────────────────────────────────

    /** 过度访问：冻结同账号所有非挂起态任务 + 发 overuse-paused 通知（邮件 + 推送）。 */
    private void handleOveruse(ScheduledTask task, OveruseWarningException e) {
        String accountId = task.credentialAccountKey();
        Locale locale = AppLocale.normalize(Locale.getDefault());
        List<ScheduledTask> affected = collectFreezableTasks(task, accountId);
        int frozen = Math.max(1, affected.size());
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("account_id", accountId == null ? "-" : accountId);
        ph.put("tasks_count", String.valueOf(frozen));
        ph.put("tasks_list_html", buildTaskList(locale, affected, true));
        ph.put("tasks_list_md", buildTaskList(locale, affected, false));
        ph.put("warning_time", formatTime(e.modifiedAt()));
        ph.put("trigger_time", formatTime(System.currentTimeMillis()));
        ph.put("warning_excerpt", e.excerpt());
        sendNotification(NotificationScenario.OVERUSE_PAUSED, ph);
    }

    /**
     * 任务级挂起：发 auth-expired（dead cookie）或 circuit-breaker（熔断）通知（邮件 + 推送）。
     * 挂起任务被 {@code findDue} 状态门挡住、不会自动续跑，故<b>不传 next_run_time</b>——
     * 否则通知里会出现一个永远不会自动到来的「下次预定运行」时间误导管理员；
     * 模板 / 推送文案改为固定的「恢复方式：需重新授权 Cookie」行。
     */
    private void handleSuspend(ScheduledTask task, ScheduleSuspendException e, long triggerTime) {
        Locale locale = AppLocale.normalize(Locale.getDefault());
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", task.name() == null ? "-" : task.name());
        ph.put("task_id", String.valueOf(task.id()));
        ph.put("task_type", taskTypeLabel(locale, task.sourceType()));
        ph.put("task_trigger", triggerLabel(locale, task.triggerKind(), task.intervalMinutes(), task.cronExpr()));
        ph.put("trigger_time", formatTime(triggerTime));
        if (e.reason() == ScheduleSuspendException.Reason.CIRCUIT_BREAKER) {
            ph.put("consecutive_failures", String.valueOf(e.consecutiveFailures()));
            ph.put("last_error_excerpt", e.lastErrorExcerpt() == null ? "" : e.lastErrorExcerpt());
            sendNotification(NotificationScenario.CIRCUIT_BREAKER, ph);
        } else {
            // reason 文案走模板 i18n，运行期只给一个稳定的 key 标识
            ph.put("reason", e.reason().name());
            sendNotification(NotificationScenario.AUTH_EXPIRED, ph);
        }
    }

    /** attempts 刚到达 {@code schedule.pending-max-attempts} 后，在最终 next_run_time 确定时发通知。 */
    private void sendPendingExhaustedNotifications(ScheduledTask task,
                                                   List<PendingExhaustedNotification> notifications,
                                                   Long nextRun) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        List<PendingExhaustedNotification> snapshot;
        synchronized (notifications) {
            snapshot = List.copyOf(notifications);
        }
        for (PendingExhaustedNotification event : snapshot) {
            notifyPendingExhausted(task, event, nextRun);
        }
    }

    /** 单个 pending-exhausted 事件的邮件 + 推送通知，best-effort、不影响调度。 */
    private void notifyPendingExhausted(ScheduledTask task, PendingExhaustedNotification event, Long nextRun) {
        Locale locale = AppLocale.normalize(Locale.getDefault());
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", task.name() == null ? "-" : task.name());
        ph.put("task_id", String.valueOf(task.id()));
        ph.put("task_type", taskTypeLabel(locale, task.sourceType()));
        ph.put("task_trigger", triggerLabel(locale, task.triggerKind(), task.intervalMinutes(), task.cronExpr()));
        ph.put("work_id", displayToken(event.workId()));
        ph.put("work_kind", workKindLabel(locale, event.workType()));
        ph.put("work_url", workUrl(event.workType(), event.workId()));
        ph.put("attempts", String.valueOf(event.attempts()));
        ph.put("trigger_time", formatTime(event.triggerTime()));
        ph.put("next_run_time", formatTime(nextRun));
        // 隔离表 reason 列未折叠空白、可能多行；通知展示前折叠为单行，与其它摘要一致——
        // 既避免多行破坏「- 最近失败：…」的单条结构，也避免换行后行首字符（# / > / - 等）被当作块级 Markdown。
        ph.put("last_error_excerpt", collapseWhitespace(event.reason()));
        sendNotification(NotificationScenario.PENDING_EXHAUSTED, ph);
    }

    /** 任务级通知共用的基础占位符（任务名 / ID / 类型 / 触发方式），与邮件 / 推送同一套键。 */
    private Map<String, String> baseTaskPlaceholders(ScheduledTask task, Locale locale) {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", task.name() == null ? "-" : task.name());
        ph.put("task_id", String.valueOf(task.id()));
        ph.put("task_type", taskTypeLabel(locale, task.sourceType()));
        ph.put("task_trigger", triggerLabel(locale, task.triggerKind(), task.intervalMinutes(), task.cronExpr()));
        return ph;
    }

    /** cookie 失效但任务无需 cookie → 已自动清除失效快照、降级匿名续跑且运行成功：发一次降级通知（best-effort）。 */
    private void notifyDegradedAnonymous(ScheduledTask task, int completed, long triggerTime, Long nextRun) {
        Locale locale = AppLocale.normalize(Locale.getDefault());
        Map<String, String> ph = baseTaskPlaceholders(task, locale);
        ph.put("completed", String.valueOf(completed));
        ph.put("trigger_time", formatTime(triggerTime));
        ph.put("next_run_time", formatTime(nextRun));
        sendNotification(NotificationScenario.DEGRADED_ANONYMOUS, ph);
    }

    /** 运行成功且本轮有新下载：发摘要通知（best-effort）。 */
    private void notifyRunSummary(ScheduledTask task, int completed, long triggerTime, Long nextRun) {
        Locale locale = AppLocale.normalize(Locale.getDefault());
        Map<String, String> ph = baseTaskPlaceholders(task, locale);
        ph.put("completed", String.valueOf(completed));
        ph.put("trigger_time", formatTime(triggerTime));
        ph.put("next_run_time", formatTime(nextRun));
        sendNotification(NotificationScenario.RUN_SUMMARY, ph);
    }

    /** 整轮运行失败（状态由非 ERROR 转入 ERROR）：发失败通知（best-effort）。errorExcerpt 已脱敏、不含凭证。 */
    private void notifyRunFailure(ScheduledTask task, String errorExcerpt, long triggerTime, Long nextRun) {
        Locale locale = AppLocale.normalize(Locale.getDefault());
        Map<String, String> ph = baseTaskPlaceholders(task, locale);
        ph.put("trigger_time", formatTime(triggerTime));
        ph.put("next_run_time", formatTime(nextRun));
        ph.put("last_error_excerpt", errorExcerpt == null ? "" : errorExcerpt);
        sendNotification(NotificationScenario.RUN_FAILED, ph);
    }

    /** 取同 credential policy/account 下将被挂起的任务列表，供通知逐条列出。 */
    private List<ScheduledTask> collectFreezableTasks(ScheduledTask current, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return List.of(current);
        }
        List<ScheduledTask> result = new ArrayList<>();
        for (ScheduledTask t : store.findByCredentialAccount(
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                accountId)) {
            if (t.suspendReason() == null
                    || (t.suspendReason() == ScheduleSuspendReason.POLICY
                    && "PIXIV_OVERUSE".equals(t.suspendCode()))) {
                result.add(t);
            }
        }
        return result.isEmpty() ? List.of(current) : result;
    }

    /**
     * 把受影响任务渲染成「任务名（ID）」列表：{@code html=true} 用 {@code <br>} 连接（任务名做 HTML 转义），
     * 否则用 Markdown 无序列表（{@code - } 前缀、{@code \n} 连接）。超过 {@link #TASK_LIST_LIMIT} 条截断并附「等共 N 个」。
     */
    private String buildTaskList(Locale locale, List<ScheduledTask> tasks, boolean html) {
        if (tasks == null || tasks.isEmpty()) {
            return "-";
        }
        int limit = Math.min(tasks.size(), TASK_LIST_LIMIT);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            ScheduledTask t = tasks.get(i);
            String name = t.name() == null ? "-" : t.name();
            // tasks_list_md 仅供推送（Markdown）消费，mail 用 tasks_list_html：md 分支对任务名做 Markdown
            // 字面转义，避免名字里的 * / _ 等被推送通道渲染器吞掉（与标量占位符在 PushMessageFactory 处一致）。
            String item = messages.get(locale, "mail.template.overuse-paused.task-item",
                    html ? escapeHtml(name) : escapeMarkdownLiteral(name), t.id());
            lines.add(html ? item : "- " + item);
        }
        if (tasks.size() > limit) {
            String more = messages.get(locale, "mail.template.overuse-paused.task-more", tasks.size());
            lines.add(html ? more : "- " + more);
        }
        return String.join(html ? "<br>" : "\n", lines);
    }

    /** 计划任务类型标签按当前 descriptor presentation 解析。 */
    private String taskTypeLabel(Locale locale, String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return "-";
        }
        SchedulePlanningLease planning = scheduleCapabilityRegistry.prepareSource(sourceType).orElse(null);
        if (planning == null) {
            return "-";
        }
        try (planning) {
            if (!scheduleCapabilityRegistry.activate(planning)) {
                return "-";
            }
            ScheduledSourceDescriptor descriptor = planning.descriptor().orElse(null);
            if (descriptor == null || planning.sourceExecutor().isEmpty()) {
                return "-";
            }
            String namespace = descriptor.presentation().displayNamespace();
            String key = descriptor.presentation().displayNameKey();
            if (webI18nBundleRegistry != null) {
                try {
                    WebI18nBundleRegistry.RegisteredBundle bundle =
                            webI18nBundleRegistry.resolve(namespace);
                    if (bundle != null) {
                        String label = bundle.load(locale).get(key);
                        if (label != null && !label.isBlank()) {
                            return label;
                        }
                    }
                } catch (RuntimeException failure) {
                    log.debug("Scheduled source {} display label could not be resolved from namespace {}",
                            sourceType, namespace, failure);
                }
            }
            String fallback = messages.getOrDefault(locale, key, key);
            return fallback == null || fallback.isBlank() ? key : fallback;
        }
    }

    private String workKindLabel(Locale locale, String workType) {
        if (PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL.equals(workType)) {
            return messages.get(locale, "mail.template.pending-exhausted.kind.novel");
        }
        if (PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST.equals(workType)) {
            return messages.get(locale, "mail.template.pending-exhausted.kind.illust");
        }
        return displayToken(workType);
    }

    /** 触发方式的本地化标签：{@code interval} → 「每 N 分钟」、{@code cron} → 「Cron：表达式」。 */
    private String triggerLabel(Locale locale, String triggerKind, Integer intervalMinutes, String cronExpr) {
        if (ScheduledTask.TRIGGER_CRON.equals(triggerKind)) {
            return messages.get(locale, "mail.template.common.trigger.cron", cronExpr == null ? "-" : cronExpr);
        }
        // 传 String 而非 Integer：避免 MessageFormat 对 ≥1000 的分钟数插入千分位分隔符（如 1,440）。
        return messages.get(locale, "mail.template.common.trigger.interval",
                intervalMinutes == null ? "-" : String.valueOf(intervalMinutes));
    }

    /** 仅为已知 Pixiv 类型与十进制 ID 生成直链；其它插件的 opaque identity 不由宿主猜测 URL。 */
    private static String workUrl(String workType, String workId) {
        if (workId == null || workId.isBlank()
                || !workId.chars().allMatch(Character::isDigit)) {
            return "";
        }
        if (PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL.equals(workType)) {
            return "https://www.pixiv.net/novel/show.php?id=" + workId;
        }
        if (PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST.equals(workType)) {
            return "https://www.pixiv.net/artworks/" + workId;
        }
        return "";
    }

    private static String displayToken(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** 把任意空白（含换行）折叠为单个空格并去除首尾空白；{@code null} 视作空串。用于通知展示前的单行化。 */
    private static String collapseWhitespace(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** 最简 HTML 转义：避免任务名里的尖括号 / & 破坏邮件信息卡布局。 */
    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeMarkdownLiteral(String literal) {
        if (literal == null || literal.isEmpty()) {
            return literal == null ? "" : literal;
        }
        String special = "\\`*_[]";
        StringBuilder sb = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (special.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 统一触发一个通知场景：扇出给所有介质（邮件 + 推送），全程 best-effort——
     * {@link NotificationService} 对每个介质各自隔离，绝不影响调度。
     */
    private void sendNotification(NotificationScenario scenario, Map<String, String> placeholders) {
        // 调度器无 HTTP 上下文，locale 显式取 JVM 系统语言归一值。
        Locale locale = AppLocale.normalize(Locale.getDefault());
        // 问候语称呼：用户设了称呼用称呼，否则回退本地化的「管理员」。邮件模板共用，统一在此补齐。
        placeholders.putIfAbsent("username", greetingName(locale));
        notificationService.notify(scenario, locale, placeholders);
    }

    /** 邮件问候称呼：用户自定义称呼优先，否则回退本地化默认「管理员 / administrator」。 */
    private String greetingName(Locale locale) {
        String displayName = userDisplayNameProvider.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return messages.get(locale, "mail.template.placeholder.administrator");
    }

    private static String formatTime(long epochMs) {
        if (epochMs <= 0) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(epochMs));
    }

    /** null 安全的时间格式化（计算下次运行可能返回 {@code null}）：null → 「-」。 */
    private static String formatTime(Long epochMs) {
        return epochMs == null ? "-" : formatTime(epochMs.longValue());
    }
}
