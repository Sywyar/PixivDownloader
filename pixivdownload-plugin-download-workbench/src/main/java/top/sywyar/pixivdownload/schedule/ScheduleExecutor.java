package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.client.HttpClientErrorException;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.i18n.AppLocale;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.core.notification.NotificationService;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledIllustSettings;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledIllustWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledNovelSettings;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledNovelWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleSingleCapabilityLease;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.schedule.source.DiscoveryMode;
import top.sywyar.pixivdownload.download.schedule.source.PageSupplier;
import top.sywyar.pixivdownload.download.schedule.source.ScheduledSource;
import top.sywyar.pixivdownload.download.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot.Download;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot.Filters;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleWorkFilter;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;

/**
 * 计划任务的执行核心：按任务类型在服务端发现作品 ID、跳过已下载、逐个抓取元数据、
 * <b>按任务快照的筛选条件做服务端过滤</b>，再按快照的下载设置派发下载。
 *
 * <p>调度以管理员身份运行（{@code userUuid=null}，不计配额 / 限流）。
 *
 * <p><b>过度访问暂停 + 鉴权失效自动挂起</b>：
 * <ul>
 *   <li><b>轮首检查点</b>（发现之前）：对 cookie-bound 任务读站内信（{@link OveruseWarningService}）——
 *       {@code COOKIE_DEAD} 时依赖型任务挂起 {@code AUTH_EXPIRED}（避免 watermark 越过当时不可见的 R-18 永久遗漏），
 *       非依赖型自动清除失效快照、降级匿名续跑（运行成功后发一次 {@code DEGRADED_ANONYMOUS} 通知）；
 *       {@code WARNED} 抛 {@link OveruseWarningException} → 账号级冻结。</li>
 *   <li><b>轮内 N 检查点</b>：每成功派发 N（{@code schedule.inbox-check-every}）个下载读一次站内信，{@code WARNED} 干净 unwind。</li>
 *   <li><b>单作品异常分类</b>：404/403-gone 跳过不挡 checkpoint；可恢复 {@link PixivFetchService.PixivFetchException}
 *       记隔离表 + 连续计数，连续 M（{@code schedule.auth-failure-circuit-breaker}）次熔断挂起。</li>
 *   <li><b>checkpoint</b>：reached 边界且无挂起条件即提交新水位线（哪怕本轮有作品进隔离表）；挂起异常上抛时绝不提交。</li>
 * </ul>
 * 自动挂起均发通知（邮件 + 推送并行，经 {@link NotificationService}，best-effort）；手动 {@code MANUAL} 挂起不发。
 *
 * <p><b>作品级并发</b>：任务间本就串行（唯一 {@code @Scheduled} tick + 单飞），故一个任务内借用
 * 下载线程池（插画走 {@code downloadTaskExecutor}、小说走 {@code novelDownloadTaskExecutor}，与交互式
 * web 下载共享）做作品级并发。发现 / 翻页 / 水位线边界判定 / 去重 / 取元数据 / 服务端筛选 / 解析图片 URL /
 * 作品间礼貌延迟 / 过度访问轮内 N 检查全在调度主线程<b>串行</b>执行（保证水位线按新→旧判边界、限速安全）；
 * <b>仅</b>把阻塞下载提交到线程池，用 {@link Semaphore}（有效并发数 = {@code min(任务并发数, 对应池大小)}）限流：
 * 主线程提交前 {@code acquire}、异步任务 {@code finally} 里 {@code release}。novel 合订与候选 checkpoint 都在
 * {@code runTask} 返回前等待本轮所有在途下载完成（join）。「连续失败」熔断计数在并发下退化为「按完成顺序的连续」（可接受）。
 */
@Slf4j
@PluginManagedBean
@RequiredArgsConstructor
public class ScheduleExecutor {

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private final ScheduledTaskStore store;
    /** owner 原子来源与作品能力 registry；所有插件行为只在 generation lease 内调用。 */
    private final ScheduleCapabilityRegistry scheduleCapabilityRegistry;
    private final PixivFetchService pixivFetchService;
    private final PixivDatabase pixivDatabase;
    private final WorkMetaCaptureService workMetaCaptureService;
    private final ArtworkDownloader artworkDownloader;
    private final NovelMetadataRepository novelMetadataRepository;
    private final ScheduleConfig scheduleConfig;
    private final ScheduleRunState runState;
    private final ScheduleRunQueue runQueue;
    private final ObjectMapper objectMapper;
    private final PixivSchedulePersistenceCodec persistenceCodec;
    private final OveruseWarningService overuseWarningService;
    private final NotificationService notificationService;
    private final AppMessages messages;
    private final SetupService setupService;
    private final DownloadConfig downloadConfig;
    // 字段名与 bean 名一致，借此按名解析到对应下载池（避免 @Primary 的 applicationTaskExecutor）。
    private final TaskExecutor downloadTaskExecutor;
    private final TaskExecutor novelDownloadTaskExecutor;
    private final ScheduleExecutionEngine scheduleExecutionEngine;

    /** 旧执行壳单测与兼容适配器使用的构造入口；只允许其发布 legacy capability。 */
    public ScheduleExecutor(
            ScheduledTaskStore store,
            ScheduleCapabilityRegistry scheduleCapabilityRegistry,
            PixivFetchService pixivFetchService,
            PixivDatabase pixivDatabase,
            WorkMetaCaptureService workMetaCaptureService,
            ArtworkDownloader artworkDownloader,
            NovelMetadataRepository novelMetadataRepository,
            ScheduleConfig scheduleConfig,
            ScheduleRunState runState,
            ScheduleRunQueue runQueue,
            ObjectMapper objectMapper,
            PixivSchedulePersistenceCodec persistenceCodec,
            OveruseWarningService overuseWarningService,
            NotificationService notificationService,
            AppMessages messages,
            SetupService setupService,
            DownloadConfig downloadConfig,
            TaskExecutor downloadTaskExecutor,
            TaskExecutor novelDownloadTaskExecutor) {
        this(store, scheduleCapabilityRegistry, pixivFetchService, pixivDatabase,
                workMetaCaptureService, artworkDownloader, novelMetadataRepository,
                scheduleConfig, runState, runQueue, objectMapper, persistenceCodec,
                overuseWarningService, notificationService, messages, setupService,
                downloadConfig, downloadTaskExecutor, novelDownloadTaskExecutor, null);
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
        try (ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> hostLease = tryAcquireHostLease()) {
            if (hostLease == null) {
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
            hostLease = tryAcquireHostLease();
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

    private ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> tryAcquireHostLease() {
        var handle = scheduleCapabilityRegistry.resolveOwner(DownloadWorkbenchPlugin.ID).orElse(null);
        if (handle == null) {
            return null;
        }
        return scheduleCapabilityRegistry.tryAcquire(handle).orElse(null);
    }

    /**
     * 对来源/执行器不可用任务做无网络 planning 探测。仅在当前 owner 与定义 schema 都匹配且所需作品执行器
     * 能取得同代复合租约时返回 true；所有租约都在返回前释放。
     */
    boolean canResolveExecution(ScheduledTask task) {
        try (SchedulePlanningLease planning = scheduleCapabilityRegistry.tryAcquireSource(task.sourceType())
                .orElse(null)) {
            if (planning == null
                    || !Objects.equals(task.sourceOwnerPluginId(), planning.owner().featurePluginId())
                    || !Objects.equals(task.sourceType(), planning.sourceType())) {
                return false;
            }
            if (planning.sourceExecutor().isPresent()) {
                planning.close();
                return scheduleExecutionEngine.canResolve(task);
            }
            if (scheduleExecutionEngine != null) {
                return false;
            }
            if (!DownloadWorkbenchPlugin.ID.equals(planning.owner().featurePluginId())) {
                return false;
            }
            ScheduledSource sourceProvider = planning.legacySourceProvider()
                    .filter(ScheduledSource.class::isInstance)
                    .map(ScheduledSource.class::cast)
                    .orElse(null);
            if (sourceProvider == null) {
                return false;
            }
            ScheduleTaskSnapshot snapshot = parseTaskSnapshot(task);
            DiscoveryMode mode = sourceProvider.mode(snapshot.source());
            Set<String> workTypes = mode == DiscoveryMode.COLLECTION
                    ? Set.of(ScheduledWorkKind.ILLUST, ScheduledWorkKind.NOVEL)
                    : Set.of(snapshot.novel() ? ScheduledWorkKind.NOVEL : ScheduledWorkKind.ILLUST);
            ScheduleExecutionLease execution = scheduleCapabilityRegistry
                    .tryExpandLegacy(planning, workTypes)
                    .orElse(null);
            if (execution == null) {
                return false;
            }
            ScheduledCancellation cancellation = execution.cancellation();
            try (execution) {
                if (cancellation.isCancellationRequested()) {
                    return false;
                }
            }
            return !cancellation.isCancellationRequested();
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
        // 本轮是否因 cookie 失效但任务无需 cookie 而自动降级（runTask 内已清失效快照 + 转匿名续跑）；运行成功后据此发一次降级通知。
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
            // 任务级单独代理：覆盖调度主线程上本轮的全部 Pixiv 请求（发现 / 元数据 / 站内信检测）；
            // 提交到下载池的阻塞下载在 WorkRunner.submit 内对池线程做同样的覆盖。
            OutboundProxyOverride.set(task.proxySnapshot());
            try {
                completedCount = runTask(task, pendingNotifications, degraded, candidateCheckpoint);
            } finally {
                OutboundProxyOverride.clear();
            }
            ensureCapabilityAvailable(hostCancellation, task.sourceType());
            outcome = ScheduleLastOutcome.OK;
            log.info("Scheduled task {} ({}) completed {} new download(s)", task.id(), task.name(), completedCount);
        } catch (OveruseWarningException e) {
            requestedSuspend = ScheduleSuspendReason.POLICY;
            suspendPolicyAccount = true;
            suspendCode = "PIXIV_OVERUSE";
            suspendDetailJson = safeDetailJson("modifiedAt", e.modifiedAt(), "excerpt", e.excerpt());
            outcomeCode = suspendCode;
            message = String.valueOf(e.modifiedAt());
            overuseNotification = e;
            log.warn("Scheduled task {} ({}) paused: overuse warning", task.id(), task.name());
        } catch (ScheduleSuspendException e) {
            requestedSuspend = ScheduleSuspendReason.CREDENTIAL;
            suspendCode = e.reason().name();
            suspendDetailJson = safeDetailJson(
                    "consecutiveFailures", e.consecutiveFailures(),
                    "lastErrorExcerpt", e.lastErrorExcerpt());
            outcomeCode = suspendCode;
            suspendNotification = e;
            suspendTriggerTime = System.currentTimeMillis();
            log.warn("Scheduled task {} ({}) suspended: {}", task.id(), task.name(), e.reason());
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
        } catch (SchedulePauseException e) {
            ScheduledTask suspended = store.findById(task.id());
            if (suspended != null
                    && suspended.suspendReason() != null
                    && suspended.suspendReason() != ScheduleSuspendReason.MANUAL) {
                outcome = suspended.suspendReason() == ScheduleSuspendReason.QUIESCED
                        ? ScheduleLastOutcome.CANCELLED
                        : ScheduleLastOutcome.ERROR;
                outcomeCode = suspended.suspendCode();
                message = suspended.suspendDetailJson();
            } else {
                outcome = ScheduleLastOutcome.CANCELLED;
                outcomeCode = "MANUAL_PAUSE";
            }
            log.info("Scheduled task {} ({}) stopped at a work boundary", task.id(), task.name());
        } catch (PixivFetchService.PixivFetchException e) {
            requestedSuspend = ScheduleSuspendReason.CREDENTIAL;
            suspendCode = "COOKIE_DEAD";
            outcomeCode = suspendCode;
            suspendNotification = new ScheduleSuspendException(ScheduleSuspendException.Reason.COOKIE_DEAD);
            suspendTriggerTime = System.currentTimeMillis();
            log.warn("Scheduled task {} ({}) auth expired, awaiting re-authorization", task.id(), task.name());
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

    /**
     * 单作品隔离表 {@code reason} 列入库专用：直接取 {@code e.getMessage()}（缺失退化为异常简单类名），
     * 统一脱敏 Cookie、Authorization、token 与签名凭证后再折叠空白并限制长度。
     * 与 {@link #summarizeError} 区别：那是顶层任务级 {@code last_message} 的展示摘要，会做大量整形并截到 300。
     */
    private static String pendingReason(Throwable e) {
        String raw = e.getMessage();
        if (raw == null || raw.isBlank()) {
            raw = e.getClass().getSimpleName();
        }
        String sanitized = ScheduleCredentialRedactor.redact(raw).replaceAll("\\s+", " ").trim();
        return sanitized.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "…";
    }

    /**
     * 发现 + 过滤 + 同步下载，返回实际完成的新下载数。
     *
     * @throws OveruseWarningException 检测到过度访问警告（轮首 / 轮内 N 检查点）
     * @throws ScheduleSuspendException cookie 依赖型 dead cookie / 单作品连续失败熔断
     * @throws PixivFetchService.PixivFetchException 发现阶段鉴权失效
     */
    private int runTask(
            ScheduledTask task,
            List<PendingExhaustedNotification> pendingNotifications,
            boolean[] degraded,
            AtomicReference<ScheduledCheckpoint> candidateCheckpoint) throws Exception {
        // 来源 planning 只允许读取任务定义和选择执行模式；cookie、探活与任何来源网络访问必须等复合 lease 完整取得。
        try (SchedulePlanningLease planning = scheduleCapabilityRegistry.tryAcquireSource(task.sourceType())
                .orElseThrow(() -> new ScheduleSourceUnavailableException(task.sourceType()))) {
            if (!Objects.equals(task.sourceOwnerPluginId(), planning.owner().featurePluginId())
                    || !Objects.equals(task.sourceType(), planning.sourceType())) {
                throw new ScheduleSourceUnavailableException(task.sourceType() + " (owner mismatch)");
            }
            if (planning.sourceExecutor().isPresent()) {
                planning.close();
                ScheduleExecutionResult result = scheduleExecutionEngine.execute(task, event -> {
                    try {
                        pendingNotifications.add(new PendingExhaustedNotification(
                                PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL.equals(event.workType()),
                                Long.parseLong(event.workId()), event.attempts(),
                                event.triggerTime(), event.reasonCode()));
                    } catch (NumberFormatException ignored) {
                        log.debug("Scheduled task {} omitted non-numeric pending notification id", task.id());
                    }
                });
                candidateCheckpoint.set(result.candidateCheckpoint());
                degraded[0] = result.credentialRevoked();
                return result.completedWorkCount();
            }
            if (scheduleExecutionEngine != null) {
                throw new ScheduleSourceUnavailableException(task.sourceType());
            }
            if (!DownloadWorkbenchPlugin.ID.equals(planning.owner().featurePluginId())) {
                throw new ScheduleSourceUnavailableException(task.sourceType());
            }
            ScheduledSource sourceProvider = planning.legacySourceProvider()
                    .filter(ScheduledSource.class::isInstance)
                    .map(ScheduledSource.class::cast)
                    .orElseThrow(() -> new ScheduleSourceUnavailableException(task.sourceType()));
            ScheduleTaskSnapshot snapshot = parseTaskSnapshot(task);
            boolean novel = snapshot.novel();
            JsonNode source = snapshot.source();
            DiscoveryMode discoveryMode = sourceProvider.mode(source);
            Set<String> requiredWorkTypes = discoveryMode == DiscoveryMode.COLLECTION
                    ? Set.of(ScheduledWorkKind.ILLUST, ScheduledWorkKind.NOVEL)
                    : Set.of(novel ? ScheduledWorkKind.NOVEL : ScheduledWorkKind.ILLUST);

            ScheduleExecutionLease execution = scheduleCapabilityRegistry
                    .tryExpandLegacy(planning, requiredWorkTypes)
                    .orElseThrow(() -> new ScheduleExecutorUnavailableException(
                            task.sourceType(), requiredWorkTypes));
            ScheduledCancellation executionCancellation = execution.cancellation();
            int completed;
            try (execution) {
                completed = runTaskWithLease(task, pendingNotifications, degraded, candidateCheckpoint, sourceProvider,
                        snapshot, novel, source, discoveryMode, execution.legacyWorkRunners(),
                        executionCancellation);
            }
            // close 与 owner retire 在线程安全计数槽上线性化：retire 先发生会留下取消信号，
            // close 先发生则说明所有插件行为已完成。close 后检查可消除「末次检查→释放租约」窗口。
            ensureCapabilityAvailable(executionCancellation, task.sourceType());
            return completed;
        }
    }

    private ScheduleTaskSnapshot parseTaskSnapshot(ScheduledTask task) throws ScheduleDefinitionException {
        if (!PixivSchedulePersistenceCodec.DEFINITION_SCHEMA.equals(task.definitionSchema())
                || task.definitionVersion() == null
                || task.definitionVersion() != PixivSchedulePersistenceCodec.DEFINITION_VERSION) {
            throw new ScheduleDefinitionException(
                    "unsupported Pixiv schedule definition schema or version");
        }
        try {
            return ScheduleTaskSnapshot.parse(objectMapper, task.definitionJson());
        } catch (Exception e) {
            throw new ScheduleDefinitionException("invalid Pixiv schedule definition", e);
        }
    }

    private int runTaskWithLease(
            ScheduledTask task,
            List<PendingExhaustedNotification> pendingNotifications,
            boolean[] degraded,
            AtomicReference<ScheduledCheckpoint> candidateCheckpoint,
            ScheduledSource sourceProvider,
            ScheduleTaskSnapshot snapshot,
            boolean novel,
            JsonNode source,
            DiscoveryMode discoveryMode,
            Map<String, ScheduledWorkRunner> workRunners,
            ScheduledCancellation cancellation) throws Exception {
        ensureCapabilityAvailable(cancellation, task.sourceType());

        boolean credentialBound = DownloadWorkbenchPlugin.ID.equals(task.credentialPolicyOwnerPluginId())
                && PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID.equals(task.credentialPolicyId())
                && task.credentialSecretReference() != null;
        String cookie = credentialBound
                ? store.findCredentialSecret(
                        task.id(), DownloadWorkbenchPlugin.ID,
                        PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID)
                : null;

        Filters filters = snapshot.filters();
        Download download = snapshot.download();
        // 抓取上限（0 = 不限 / 全量）。语义随来源是否「ID 单调可水位线」二分：
        //   · 水位线类（USER_NEW / USER_REQUEST / FOLLOW_LATEST / date_d 翻页到底 SEARCH）：仅<b>首轮</b>（watermark 未建立）封顶，
        //     按进入本轮运行队列的作品数计（已下载 / 筛选跳过也占额度）；随后水位线推进到最新 ID、更老积压永久跳过；
        //   · 非水位线类（MY_BOOKMARKS / COLLECTION / 非 date_d 翻页到底 SEARCH）：作为<b>每轮上限</b>逐轮抽干积压，
        //     只数实际进入派发的新作（已下载跳过免费推进、不占额度；筛选跳过仍占额度以限住每轮元数据请求量）；
        //   · SERIES / 固定页 SEARCH 不应用（前端也隐藏该字段）。
        int fetchLimit = snapshot.fetchLimit();

        // ── 轮首检查点：cookie-bound 任务读站内信（过度访问判定 + cookie 存活探测）──────────────
        if (credentialBound) {
            OveruseWarningService.Result result =
                    overuseWarningService.check(cookie, acknowledgedWarningTime(task), System.currentTimeMillis());
            if (result.isCookieDead()) {
                if (snapshot.cookieDependent() || sourceProvider.accountScoped()) {
                    // 账号私有来源（收藏 / 关注新作 / 珍藏集）无法匿名续跑，dead cookie 一律挂起。
                    throw new ScheduleSuspendException(ScheduleSuspendException.Reason.COOKIE_DEAD);
                }
                // 非依赖型：失效 Cookie 已无价值，自动清除快照转受限模式——避免每轮再用死 cookie 探测站内信、
                // 也避免每轮重复发降级通知；本轮降级匿名续跑（全年龄作品照样抓全、不丢、不浪费），运行成功后发一次降级通知。
                // 连带清除由 Cookie 派生的账号绑定（account_id / ack_warning_time）：任务已转受限、不再使用该账号凭证，
                // 残留账号标识会让它仍被同账号其它任务的过度访问冻结牵连、被账号级恢复 / 通知按原账号归类。
                cookie = null;
                degraded[0] = true;
            } else if (result.isWarned()) {
                throw new OveruseWarningException(result.modifiedAt(), result.excerpt());
            }
        }

        // COLLECTION 是插画+小说混合来源，分两遍各自走对应下载管线，单独处理（不经共享扫描驱动）。
        if (discoveryMode == DiscoveryMode.COLLECTION) {
            return runCollectionTask(task, source, cookie, filters, download, fetchLimit, pendingNotifications,
                    workRunners, cancellation);
        }

        // 开新一轮的运行队列（整体替换上一轮）。
        ScheduleRunQueue.Run run = runQueue.begin(task.id(),
                novel ? ScheduleRunQueue.KIND_NOVEL : ScheduleRunQueue.KIND_ILLUST);

        // 本轮去重谓词：插画按「实际目录检测」开关在 isArtworkDownloaded(verify) 与 hasArtwork 之间二选一，
        // 小说始终走 hasNovel（无 verify）。作品间礼貌延迟取任务级「作品间隔」。
        LongPredicate alreadyDownloaded = alreadyDownloadedPredicate(novel, download);
        Runnable politeDelay = politeDelayFor(download);
        TaskExecutor pool = novel ? novelDownloadTaskExecutor : downloadTaskExecutor;
        int concurrency = effectiveConcurrency(novel, download.concurrent());
        WorkRunner runner = new WorkRunner(task, novel, cookie, filters, download, run, concurrency, pool,
                pendingNotifications, workRunners, cancellation);

        try {
            // ── 每轮无条件先消费隔离表（不再依赖 pendingRetryArmed 武装位）：走正常 dispatch 路径，
            //    计入 N 检查点与过度访问风险；单作品 attempts 自动 +1，达到上限时发通知转人工。
            //    若作品在隔离期间已被其它路径（手动 / 别的任务）下载，先清隔离表条目、跳过重试。──
            retryPending(task.id(), runner, politeDelay, alreadyDownloaded);

            // 经来源 provider 发现并派发本轮新作（水位线 / 边界 / 全量按来源在 discoverAndDispatch 内自定；
            // 共享扫描驱动 / 作品级并发 / 限流 / 过度访问检查 / 水位线推进封装在 RunContext 背后的调度壳里）。
            sourceProvider.discoverAndDispatch(
                    new RunContext(task, source, cookie, novel, fetchLimit, run, runner,
                            alreadyDownloaded, politeDelay, candidateCheckpoint));
        } finally {
            // 等本轮所有在途下载完成：保证挂起 / 异常 unwind 时已派发的下载跑完、失败者已入隔离表，
            // 终态标记与「checkpoint 不提交」一致（候选水位线只会在完整 join 后随成功结果原子提交）。
            runner.awaitAll();
        }
        ensureCapabilityAvailable(cancellation, task.sourceType());

        int completed = runner.completed();

        // 小说系列合订：best-effort、幂等；本轮有新章节时立即合订（已在上面 join，章节均已落库）。
        // 经核心契约 ScheduledWorkRunner.mergeSeries 走小说执行器（novel 任务已由解析门保证执行器在场）。
        if (novel && sourceProvider.seriesMergeApplies() && download.novelMerge() && completed > 0) {
            long seriesId = source.path("seriesId").asLong(0);
            if (seriesId > 0) {
                ensureCapabilityAvailable(cancellation, task.sourceType());
                try {
                    workRunners.get(ScheduledWorkKind.NOVEL)
                            .mergeSeries(seriesId, download.novelMergeFormat());
                } catch (Exception e) {
                    log.warn("Scheduled task {} series merge failed [{}]: {}",
                            task.id(), e.getClass().getSimpleName(), summarizeError(e));
                }
            }
        }
        // mergeSeries 是插件执行器上的长调用：owner 在调用期间撤回时，generation drain 会等本租约释放。
        // 调用返回后必须重新观察复合取消信号，避免把已撤回执行器完成的旧代结果误记为 OK。
        ensureCapabilityAvailable(cancellation, task.sourceType());
        return completed;
    }

    private Long acknowledgedWarningTime(ScheduledTask task) throws ScheduleDefinitionException {
        String state = task.credentialPolicyStateJson();
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return persistenceCodec.decodeAcknowledgedWarningTime(state);
        } catch (IllegalArgumentException e) {
            throw new ScheduleDefinitionException("invalid Pixiv credential policy state", e);
        }
    }

    private static void ensureCapabilityAvailable(
            ScheduledCancellation cancellation, String unresolvedType)
            throws ScheduleSourceUnavailableException {
        if (cancellation.isCancellationRequested()) {
            throw new ScheduleSourceUnavailableException(unresolvedType + " (capability retired)");
        }
    }

    /**
     * 重试隔离表中尚未达到上限的作品；成功 DELETE、失败 incPendingAttempts，全经正常分类路径。
     * 若作品在隔离期间已被其它路径（手动 / 别的任务）下载，直接清隔离表条目并跳过 dispatch，
     * 避免重复下载或在已成功后仍累计失败计数。
     */
    private void retryPending(long taskId, WorkRunner runner, Runnable politeDelay,
                              LongPredicate alreadyDownloaded)
            throws OveruseWarningException, ScheduleSuspendException, SchedulePauseException,
            ScheduleSourceUnavailableException, ScheduleDefinitionException {
        int max = scheduleConfig.getPendingMaxAttempts();
        for (ScheduledPendingWork p : store.listPendingWork(taskId)) {
            if (!runner.workType().equals(p.workType())) {
                throw new ScheduleDefinitionException(
                        "pending work type does not match the task execution plan");
            }
            long numericWorkId;
            String id;
            try {
                ScheduledWork work = persistenceCodec.fromPendingWork(p);
                id = persistenceCodec.decodeWorkId(work);
                numericWorkId = Long.parseLong(id);
            } catch (IllegalArgumentException e) {
                throw new ScheduleDefinitionException("invalid Pixiv pending work envelope", e);
            }
            if (p.attempts() >= max) {
                runner.claimWithoutDispatch(numericWorkId);
                continue; // 需人工，停止自动重试，也不允许本轮来源发现绕过隔离状态
            }
            if (alreadyDownloaded.test(numericWorkId)) {
                runner.claimWithoutDispatch(numericWorkId);
                // 已在别处下载完：清隔离条目、本轮不再 dispatch；不入运行队列展示，避免和正常发现的「已下载跳过」混淆。
                store.deletePendingWork(taskId, p.workType(), p.workId());
                continue;
            }
            runner.run().discovered(id);
            runner.process(id, numericWorkId, true);
            politeDelay.run();
        }
    }

    /**
     * 珍藏集（插画+小说混合）专用：发现成员后分两遍各自走对应下载管线（插画用 {@code downloadTaskExecutor}、
     * 小说用 {@code novelDownloadTaskExecutor}），完成数相加。两遍共用同一轮运行队列（按成员自身 kind 登记）。
     * 隔离表重试按「成员是否仍在珍藏集内」的成员关系判定：仍在则本轮以 retry 语义重派，已被移出的孤儿条目本轮跳过。
     */
    private int runCollectionTask(ScheduledTask task, JsonNode source, String cookie,
                                  Filters filters, Download download, int fetchLimit,
                                  List<PendingExhaustedNotification> pendingNotifications,
                                  Map<String, ScheduledWorkRunner> workRunners,
                                  ScheduledCancellation cancellation) throws Exception {
        ensureCapabilityAvailable(cancellation, task.sourceType());
        String collectionId = source.path("collectionId").asText("");
        PixivFetchService.CollectionWorkIds ids = pixivFetchService.discoverCollectionWorkIds(collectionId, cookie);

        ScheduleRunQueue.Run run = runQueue.begin(task.id(), ScheduleRunQueue.KIND_ILLUST);
        Runnable politeDelay = politeDelayFor(download);

        // 隔离表中尚未达上限的待重试成员（混合来源不记 kind，靠本轮发现的成员关系区分插画/小说）。
        int max = scheduleConfig.getPendingMaxAttempts();
        Map<PendingWorkKey, Boolean> pending = new HashMap<>();
        for (ScheduledPendingWork p : store.listPendingWork(task.id())) {
            try {
                ScheduledWork work = persistenceCodec.fromPendingWork(p);
                String workId = persistenceCodec.decodeWorkId(work);
                Long.parseLong(workId);
                pending.put(new PendingWorkKey(work.key().workType(), workId), p.attempts() < max);
            } catch (IllegalArgumentException e) {
                throw new ScheduleDefinitionException("invalid Pixiv collection pending envelope", e);
            }
        }

        // 珍藏集无水位线、ID 非单调 → 每轮上限：两遍（插画 + 小说）共享同一预算，本轮最多派发 fetchLimit 个新作
        // （已下载跳过免费推进、不占预算，逐轮抽干积压）；隔离表重试不计入预算（属既有积压的恢复）。-1 = 不限。
        int[] budget = {fetchLimit > 0 ? fetchLimit : -1};

        int completed = 0;
        completed += runCollectionPass(task, false, cookie, filters, download, run, ids.illustIds(), pending,
                politeDelay, budget, pendingNotifications, workRunners, cancellation);
        completed += runCollectionPass(task, true, cookie, filters, download, run, ids.novelIds(), pending,
                politeDelay, budget, pendingNotifications, workRunners, cancellation);
        return completed;
    }

    /**
     * 珍藏集单 kind 一遍：跳过已下载 → 派发（命中隔离表则按 retry 语义），结束前 join 本遍在途下载。
     * {@code budget[0]} 为两遍共享的每轮预算（{@code -1} 不限，{@code 0} 已耗尽），<b>只数实际进入派发的新作</b>
     * （含被筛选跳过的；已下载跳过免费推进、不占预算，否则窗口永远停在已下载的表头、旧积压永不补下）：预算耗尽即
     * 跳过剩余新作、不入本轮运行队列（留待下一轮）；重试不受预算约束。
     */
    private int runCollectionPass(ScheduledTask task, boolean novel, String cookie, Filters filters,
                                  Download download, ScheduleRunQueue.Run run, List<String> ids,
                                  Map<PendingWorkKey, Boolean> pending, Runnable politeDelay, int[] budget,
                                  List<PendingExhaustedNotification> pendingNotifications,
                                  Map<String, ScheduledWorkRunner> workRunners,
                                  ScheduledCancellation cancellation) throws Exception {
        LongPredicate alreadyDownloaded = alreadyDownloadedPredicate(novel, download);
        TaskExecutor pool = novel ? novelDownloadTaskExecutor : downloadTaskExecutor;
        int concurrency = effectiveConcurrency(novel, download.concurrent());
        WorkRunner runner = new WorkRunner(task, novel, cookie, filters, download, run, concurrency, pool,
                pendingNotifications, workRunners, cancellation);
        String itemKind = novel ? ScheduleRunQueue.KIND_NOVEL : ScheduleRunQueue.KIND_ILLUST;
        String workType = novel ? ScheduledWorkKind.NOVEL : ScheduledWorkKind.ILLUST;
        try {
            for (String id : ids) {
                long workId;
                try {
                    workId = Long.parseLong(id);
                } catch (NumberFormatException e) {
                    continue;
                }
                PendingWorkKey pendingKey = new PendingWorkKey(workType, id);
                Boolean retryable = pending.get(pendingKey);
                if (Boolean.FALSE.equals(retryable)) {
                    continue;
                }
                boolean isRetry = Boolean.TRUE.equals(retryable);
                // 重试条目若已经在别处被下载（手动 / 别的任务）：清隔离条目、跳过 dispatch，避免重复下载。
                if (isRetry && alreadyDownloaded.test(workId)) {
                    store.deletePendingWork(task.id(), workType, id);
                    pending.remove(pendingKey);
                    continue;
                }
                // 已下载免费跳过：登记 + 跳过、不占预算（且不受预算是否耗尽影响），否则窗口永远停在已下载的表头、旧积压永不补下。
                if (!isRetry && alreadyDownloaded.test(workId)) {
                    run.discovered(id, itemKind);
                    run.mark(id, itemKind, ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
                    continue;
                }
                // 每轮上限：预算耗尽即静默跳过剩余新作（不登记发现、不入运行队列，下一轮再处理）；重试不受限。
                if (!isRetry && budget[0] == 0) {
                    continue;
                }
                run.discovered(id, itemKind);
                // 上限只数实际进入派发的新作（含被筛选跳过的）；重试不占预算。
                if (!isRetry && budget[0] > 0) {
                    budget[0]--;
                }
                runner.process(id, workId, isRetry);
                politeDelay.run();
            }
        } finally {
            runner.awaitAll();
        }
        ensureCapabilityAvailable(cancellation, task.sourceType());
        return runner.completed();
    }

    private record PendingWorkKey(String workType, String workId) {
    }

    /**
     * 来源 provider 的运行期上下文实现（调度壳内部类）：把共享扫描驱动 + 本轮任务级状态封装给来源对象。
     * 来源经本上下文读参数 / 抓取 / 驱动扫描；背后的作品级并发、限流、过度访问轮内检查、隔离重试、水位线推进
     * 全部留在调度壳，来源不接触。{@code watermarkScan} / {@code boundaryScan} / {@code fullScan} 是早前
     * {@code runWatermarkMode} / {@code runDownloadedBoundarySearch} 去掉按类型分支后的等价封装。
     */
    private final class RunContext implements ScheduledSourceContext {
        private final ScheduledTask task;
        private final JsonNode source;
        private final String cookie;
        private final boolean novel;
        private final int fetchLimit;
        private final ScheduleRunQueue.Run run;
        private final WorkRunner runner;
        private final LongPredicate alreadyDownloaded;
        private final Runnable politeDelay;
        private final AtomicReference<ScheduledCheckpoint> candidateCheckpoint;

        RunContext(ScheduledTask task, JsonNode source, String cookie, boolean novel, int fetchLimit,
                   ScheduleRunQueue.Run run, WorkRunner runner, LongPredicate alreadyDownloaded,
                   Runnable politeDelay,
                   AtomicReference<ScheduledCheckpoint> candidateCheckpoint) {
            this.task = task;
            this.source = source;
            this.cookie = cookie;
            this.novel = novel;
            this.fetchLimit = fetchLimit;
            this.run = run;
            this.runner = runner;
            this.alreadyDownloaded = alreadyDownloaded;
            this.politeDelay = politeDelay;
            this.candidateCheckpoint = candidateCheckpoint;
        }

        @Override
        public ScheduledTask task() {
            return task;
        }

        @Override
        public JsonNode source() {
            return source;
        }

        @Override
        public String cookie() {
            return cookie;
        }

        @Override
        public boolean novel() {
            return novel;
        }

        @Override
        public int fetchLimit() {
            return fetchLimit;
        }

        @Override
        public PixivFetchService fetch() {
            return pixivFetchService;
        }

        @Override
        public Runnable watermarkPageDelay() {
            return ScheduleExecutor.this::watermarkPageDelay;
        }

        @Override
        public void watermarkScan(PageSupplier pages, Runnable pageDelay) throws Exception {
            WorkDispatcher dispatcher = (id, workId) -> runner.process(id, workId, false);
            long watermark = decodeWatermark(task);
            // 仅首轮（水位线未建立）封顶：入队数达到上限即停，newestSeen 已记到最新 ID、水位线照常推进到最新，
            // 更老的积压会被永久跳过（这正是「只要最新 N 个、之后只追新」的预期语义）。
            int queueLimit = (watermark == 0 && fetchLimit > 0) ? fetchLimit : 0;
            WatermarkScanResult result = runWatermarkScan(
                    pages, watermark, alreadyDownloaded, dispatcher, politeDelay, pageDelay, run, queueLimit);
            // 扫描正常返回（无挂起异常）后，先等本轮在途下载完成再推进水位线：确保失败者已入隔离表、
            // watermark 推进不会越过尚未真正落盘的作品（崩溃抗空洞）。挂起异常上抛时本方法不会执行到这里。
            runner.awaitAll();
            ensureCapabilityAvailable(runner.capabilityCancellation, task.sourceType());
            // reached 边界且无挂起条件即推进（哪怕本轮有作品进隔离表）。
            if (result.newestSeen() > 0) {
                candidateCheckpoint.set(persistenceCodec.encodeCheckpoint(result.newestSeen()));
            }
        }

        @Override
        public void boundaryScan(PageSupplier pages) throws Exception {
            WorkDispatcher dispatcher = (id, workId) -> runner.process(id, workId, false);
            // 非 date_d 的「翻页到底」没有可靠的 ID 水位线 → 封顶按「每轮上限」语义（每轮最多登记 fetchLimit 个队列项）。
            int queueLimit = fetchLimit > 0 ? fetchLimit : 0;
            runDownloadedBoundaryScan(
                    pages, alreadyDownloaded, dispatcher, politeDelay,
                    ScheduleExecutor.this::watermarkPageDelay, run, queueLimit);
        }

        private long decodeWatermark(ScheduledTask task) throws ScheduleDefinitionException {
            if (task.checkpointSchema() == null
                    && task.checkpointVersion() == null
                    && task.checkpointJson() == null) {
                return 0L;
            }
            try {
                return persistenceCodec.decodeCheckpoint(new ScheduledCheckpoint(
                        task.checkpointSchema(), task.checkpointVersion(), task.checkpointJson()));
            } catch (IllegalArgumentException e) {
                throw new ScheduleDefinitionException("invalid Pixiv checkpoint", e);
            }
        }

        @Override
        public void fullScan(List<String> ids, int queueLimit) throws Exception {
            WorkDispatcher dispatcher = (id, workId) -> runner.process(id, workId, false);
            runFullDiscoveryCapScan(ids, alreadyDownloaded, dispatcher, politeDelay, run, queueLimit);
        }
    }

    /**
     * 处理单个未下载作品（派发 + 异常分类 + 隔离/计数 + 到 N 过度访问检查）。
     * 单作品级的可恢复失败由实现内部隔离、绝不上抛；只有挂起信号上抛。
     *
     * @return true 表示成功派发下载（计入 dispatched）。
     * @throws OveruseWarningException 轮内 N 检查命中过度访问警告
     * @throws ScheduleSuspendException 单作品连续失败熔断
     */
    @FunctionalInterface
    interface WorkDispatcher {
        boolean dispatch(String id, long workId)
                throws OveruseWarningException, ScheduleSuspendException, SchedulePauseException,
                ScheduleSourceUnavailableException;
    }

    /** 水位线 SEARCH 翻到下一页前的强制礼貌延迟。 */
    static final long WATERMARK_PAGE_DELAY_MS = 10_000L;

    /** 水位线扫描结果：入队数与发现到的最新 ID（正常返回即代表追到边界、可推进水位线）。 */
    record WatermarkScanResult(int queued, long newestSeen) {
    }

    /**
     * 水位线增量扫描的纯逻辑（package-private + static，便于单测）：逐页取 ID（最新在前），
     * 累积 {@code newestSeen = max(所有发现到的 ID)}；命中 {@code watermark > 0 && id <= watermark}
     * 即停止整轮翻页；已下载（去重命中）跳过；连续<b>一整页全部已下载</b>兜底停；空页停。
     * 单作品的可恢复失败由 {@code dispatcher} 内部隔离、不影响 watermark 推进；
     * 挂起信号（过度访问 / 熔断）与发现阶段鉴权失效上抛，本方法不返回 → 不推进 watermark。
     *
     * <p>{@code queueLimit > 0} 时为<b>首轮封顶</b>：入队数达到上限即停止整轮、正常返回。此时
     * {@code newestSeen} 已是本轮发现到的最大 ID（最新在前，故为来源最新作），调用方据此把水位线推进到最新，
     * 更老的积压被永久跳过——「只要最新 N 个、之后只追新」。{@code queueLimit <= 0} 表示不限。
     */
    static WatermarkScanResult runWatermarkScan(PageSupplier pages, long watermark,
                                                java.util.function.LongPredicate alreadyDownloaded,
                                                WorkDispatcher dispatcher, Runnable politeDelay,
                                                Runnable pageDelay, ScheduleRunQueue.Run run,
                                                int queueLimit) throws Exception {
        int queued = 0;
        long newestSeen = 0L;
        for (int p = 1; ; p++) {
            List<String> ids = pages.get(p);
            if (ids == null || ids.isEmpty()) break;
            boolean wholePageAlreadyDownloaded = true;
            for (String id : ids) {
                long workId;
                try {
                    workId = Long.parseLong(id);
                } catch (NumberFormatException e) {
                    continue;
                }
                newestSeen = Math.max(newestSeen, workId);
                if (watermark > 0 && workId <= watermark) {
                    return new WatermarkScanResult(queued, newestSeen);
                }
                run.discovered(id);
                queued++;
                boolean reachedQueueLimit = queueLimit > 0 && queued >= queueLimit;
                if (alreadyDownloaded.test(workId)) {
                    run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
                    if (reachedQueueLimit) {
                        return new WatermarkScanResult(queued, newestSeen);
                    }
                    continue;
                }
                wholePageAlreadyDownloaded = false;
                dispatcher.dispatch(id, workId);
                if (reachedQueueLimit) {
                    return new WatermarkScanResult(queued, newestSeen);
                }
                politeDelay.run();
            }
            if (wholePageAlreadyDownloaded) break;
            pageDelay.run();
        }
        return new WatermarkScanResult(queued, newestSeen);
    }

    /**
     * 非 date_d 增量搜索的纯逻辑：排序不保证 ID 单调，不用 watermark，逐页处理到命中第一个已下载作品即停。
     *
     * <p>{@code queueLimit > 0} 时为<b>每轮上限</b>：入队数达到上限即停止本轮、正常返回（无水位线，
     * 故每轮各抓不超过上限的队列项，逐轮抽干）。{@code queueLimit <= 0} 表示不限。
     */
    static int runDownloadedBoundaryScan(PageSupplier pages,
                                         java.util.function.LongPredicate alreadyDownloaded,
                                         WorkDispatcher dispatcher, Runnable politeDelay,
                                         Runnable pageDelay, ScheduleRunQueue.Run run,
                                         int queueLimit) throws Exception {
        int queued = 0;
        for (int p = 1; ; p++) {
            List<String> ids = pages.get(p);
            if (ids == null || ids.isEmpty()) break;
            for (String id : ids) {
                long workId;
                try {
                    workId = Long.parseLong(id);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (alreadyDownloaded.test(workId)) {
                    return queued;
                }
                run.discovered(id);
                queued++;
                boolean reachedQueueLimit = queueLimit > 0 && queued >= queueLimit;
                dispatcher.dispatch(id, workId);
                if (reachedQueueLimit) {
                    return queued;
                }
                politeDelay.run();
            }
            pageDelay.run();
        }
        return queued;
    }

    /**
     * 非水位线「全量发现」来源（MY_BOOKMARKS / SERIES / 固定页 SEARCH）的每轮扫描纯逻辑
     * （package-private + static，便于单测）：按发现顺序逐个登记、已下载免费跳过、未下载者派发。
     *
     * <p>{@code queueLimit > 0} 时为<b>每轮上限</b>：只数实际进入派发的新作（含被筛选跳过的——它们已耗一次
     * 元数据请求），达上限即停本轮、剩余留待下一轮。<b>已下载跳过不占额度</b>——否则在稳定排序的来源上，
     * 表头那批已下载作品会把每轮额度吃满、窗口永远停在原地，上限之外更早的积压永远不被补下；免费推进后
     * 每轮真正下满 N 个尚未下载的新作，分多轮逐步抽干积压。{@code queueLimit <= 0} 表示不限（全量）。
     *
     * @return 本轮实际派发的新作数。
     */
    static int runFullDiscoveryCapScan(List<String> ids,
                                       java.util.function.LongPredicate alreadyDownloaded,
                                       WorkDispatcher dispatcher, Runnable politeDelay,
                                       ScheduleRunQueue.Run run, int queueLimit)
            throws OveruseWarningException, ScheduleSuspendException, SchedulePauseException,
            ScheduleSourceUnavailableException {
        int queued = 0;
        for (String id : ids) {
            long workId;
            try {
                workId = Long.parseLong(id);
            } catch (NumberFormatException e) {
                continue;
            }
            run.discovered(id);
            if (alreadyDownloaded.test(workId)) {
                run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
                continue;
            }
            dispatcher.dispatch(id, workId);
            queued++;
            if (queueLimit > 0 && queued >= queueLimit) {
                break;
            }
            politeDelay.run();
        }
        return queued;
    }

    /**
     * 串行预备阶段的产物：一个可异步执行的阻塞下载。返回 {@code true} 表示下载成功。
     * 取元数据 / 筛选 / 解析 URL 已在创建本对象时（调度主线程）完成，{@code run()} 只做阻塞下载本身，
     * 可安全提交到下载线程池并发执行。
     */
    @FunctionalInterface
    interface DownloadJob {
        boolean run();
    }

    /**
     * 抓取插画元数据、应用筛选；命中则组装请求并返回一个待提交线程池的下载任务，被筛选跳过返回 {@code null}。
     * 取 meta / 解析 URL 的异常向上抛出，由 {@link WorkRunner#process} 串行分类（隔离 / 熔断）。
     */
    private DownloadJob prepareArtwork(String id, long artworkId, String cookie,
                                       Filters filters, Download download, ScheduleRunQueue.Run run,
                                       Map<Long, PixivFetchService.IllustSeriesMeta> seriesCache,
                                       ScheduledWorkRunner runner)
            throws Exception {
        PixivFetchService.ArtworkMetaCapture capture = pixivFetchService.fetchArtworkMetaCapture(id, cookie);
        PixivFetchService.ArtworkMeta meta = capture.meta();
        run.setMeta(id, ScheduledWorkKind.ILLUST, meta.title(), meta.xRestrict(), meta.ai());
        if (!ScheduleWorkFilter.artworkMatches(meta, filters)) {
            run.mark(id, ScheduledWorkKind.ILLUST, ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            return null;
        }
        // 系列富信息（标题 + 简介 + 封面）：与 web 链路一致，本轮按 seriesId 缓存、best-effort，失败不挡下载。
        // 按「仅 seriesId 有效 + 非空白」过滤后填入中性载体（null 即不设置，执行器据此原样跳过 set）。
        String seriesTitle = null;
        String seriesDescription = null;
        String seriesCoverUrl = null;
        if (meta.seriesId() != null && meta.seriesId() > 0) {
            seriesTitle = meta.seriesTitle();
            PixivFetchService.IllustSeriesMeta sm = resolveIllustSeriesMeta(meta.seriesId(), cookie, seriesCache);
            if (sm != null) {
                if (sm.caption() != null && !sm.caption().isBlank()) seriesDescription = sm.caption();
                if (sm.coverUrl() != null && !sm.coverUrl().isBlank()) seriesCoverUrl = sm.coverUrl();
            }
        }

        boolean ugoira = false;
        String ugoiraZipUrl = null;
        List<Integer> ugoiraDelays = null;
        List<String> imageUrls;
        JsonNode pagesBody = null;
        if (meta.isUgoira()) {
            PixivFetchService.UgoiraInfo ug = pixivFetchService.resolveUgoira(id, cookie);
            if (ug.zipUrl() == null || ug.zipUrl().isEmpty()) {
                throw new IllegalStateException("empty ugoira zip url");
            }
            ugoira = true;
            ugoiraZipUrl = ug.zipUrl();
            ugoiraDelays = ug.delays();
            imageUrls = List.of(ug.zipUrl());
        } else {
            PixivFetchService.ArtworkPages pages = pixivFetchService.resolveArtworkPages(id, cookie);
            imageUrls = pages.urls();
            pagesBody = pages.body();
            if (imageUrls.isEmpty()) {
                throw new IllegalStateException("no image urls resolved");
            }
        }

        // 抓元数据 / 筛选 / 系列补全 / URL 解析在调度壳主线程完成后，把已解析好的中性载体交插画执行器构造下载请求
        // 并阻塞下载——调度壳不直碰下载实现（图片间隔等下载设置由执行器映射进请求）。
        ScheduledIllustWork work = new ScheduledIllustWork(
                artworkId, meta.title(), meta.authorId(), meta.authorName(), meta.xRestrict(), meta.ai(),
                meta.description(), meta.tags(), meta.seriesId(), meta.seriesOrder(), seriesTitle,
                meta.illustType(), seriesDescription, seriesCoverUrl,
                ugoira, ugoiraZipUrl, ugoiraDelays, imageUrls, PIXIV_REFERER + "artworks/" + id);
        ScheduledIllustSettings settings = new ScheduledIllustSettings(
                download.fileNameTemplate(), download.bookmark(), download.collectionId(), download.imageDelayMs());
        // 下载已抓的 illust / pages body 别丢——下载成功后旁路归一化 meta sidecar + 列投影（零额外请求、best-effort）。
        JsonNode illustBody = capture.body();
        JsonNode capturedPagesBody = pagesBody;
        return () -> {
            boolean ok = runner.download(work, settings, cookie);
            if (ok) {
                workMetaCaptureService.captureArtwork(artworkId, illustBody, capturedPagesBody, "schedule");
            }
            return ok;
        };
    }

    /** 抓取小说详情、应用筛选；命中则组装请求并返回待提交线程池的下载任务，被筛选跳过返回 {@code null}。 */
    private DownloadJob prepareNovel(String id, long novelId, String cookie,
                                     Filters filters, Download download, ScheduleRunQueue.Run run,
                                     Map<Long, PixivFetchService.NovelSeriesMeta> seriesCache,
                                     ScheduledWorkRunner runner)
            throws Exception {
        PixivFetchService.NovelDetailCapture capture = pixivFetchService.fetchNovelDetailCapture(id, cookie);
        PixivFetchService.NovelDetail d = capture.detail();
        run.setMeta(id, ScheduledWorkKind.NOVEL, d.title(), d.xRestrict(), d.ai());
        if (!ScheduleWorkFilter.novelMatches(d, filters)) {
            run.mark(id, ScheduledWorkKind.NOVEL, ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            return null;
        }
        // 系列富信息（简介 + 封面 + 系列标签）：与 web 链路一致，本轮按 seriesId 缓存、best-effort，失败不挡下载。
        // 按「非空白 / 非空集合」过滤后填入中性载体（null 即不设置，下载侧据此原样跳过 set）。
        String seriesDescription = null;
        String seriesCoverUrl = null;
        List<TagDto> seriesTags = null;
        if (d.seriesId() != null && d.seriesId() > 0) {
            PixivFetchService.NovelSeriesMeta sm = resolveNovelSeriesMeta(d.seriesId(), cookie, seriesCache);
            if (sm != null) {
                if (sm.caption() != null && !sm.caption().isBlank()) seriesDescription = sm.caption();
                if (sm.coverUrl() != null && !sm.coverUrl().isBlank()) seriesCoverUrl = sm.coverUrl();
                if (sm.tags() != null && !sm.tags().isEmpty()) seriesTags = sm.tags();
            }
        }
        // 抓详情 / 筛选 / 系列补全在调度壳完成后，把中性载体交小说插件构造 NovelDownloadRequest 并下载——
        // 调度壳不再 import 任何 novel 包类型。
        ScheduledNovelWork work = new ScheduledNovelWork(
                d.novelId(), d.title(), d.content(),
                d.authorId(), d.authorName(), d.xRestrict(), d.ai(),
                d.original(), d.language(),
                d.wordCount(), d.textLength(), d.readingTimeSeconds(), d.pageCount(),
                d.description(), d.tags(),
                d.seriesId(), d.seriesOrder(), d.seriesTitle(),
                d.uploadTimestamp(), d.coverUrl(), d.embeddedImages(),
                seriesDescription, seriesCoverUrl, seriesTags);
        // 下载即自动翻译（admin 身份运行，恒可触发）：翻译走服务端独立队列、不阻塞调度 tick；译文合订交由
        // 每本译完后的合订（沿用下载设置的「生成合订本」），与 web 链路一致。autoTranslate 为假时下载侧不应用后四字段。
        ScheduledNovelSettings settings = new ScheduledNovelSettings(
                download.fileNameTemplate(), download.bookmark(), download.collectionId(), download.novelFormat(),
                download.novelAutoTranslate(), download.novelTranslateLanguage(),
                download.novelTranslateSegmentSize(), download.novelMerge(), download.novelMergeFormat());
        // 下载已抓的 novel body 别丢——下载成功后旁路归一化 meta sidecar + upload_time 列投影（零额外请求、best-effort）。
        JsonNode novelBody = capture.body();
        return () -> {
            boolean ok = runner.download(work, settings, cookie);
            if (ok) {
                workMetaCaptureService.captureNovel(novelId, novelBody, "schedule");
            }
            return ok;
        };
    }

    /**
     * 解析插画 / 漫画系列富信息（简介 + 封面），按 {@code seriesId} 在本轮内缓存（含失败的空结果），
     * 仅在调度主线程串行调用。系列补信息是 best-effort：抓取失败只记 debug、返回空，绝不让作品下载失败。
     */
    private PixivFetchService.IllustSeriesMeta resolveIllustSeriesMeta(
            long seriesId, String cookie, Map<Long, PixivFetchService.IllustSeriesMeta> cache) {
        PixivFetchService.IllustSeriesMeta cached = cache.get(seriesId);
        if (cached != null) {
            return cached;
        }
        PixivFetchService.IllustSeriesMeta meta;
        try {
            meta = pixivFetchService.fetchIllustSeriesMeta(seriesId, cookie);
        } catch (Exception e) {
            log.debug("Scheduled illust series {} enrichment skipped: {}", seriesId, pendingReason(e));
            meta = new PixivFetchService.IllustSeriesMeta("", "");
        }
        cache.put(seriesId, meta);
        return meta;
    }

    /**
     * 解析小说系列富信息（简介 + 封面 + 系列标签），语义同 {@link #resolveIllustSeriesMeta}：本轮缓存、best-effort。
     */
    private PixivFetchService.NovelSeriesMeta resolveNovelSeriesMeta(
            long seriesId, String cookie, Map<Long, PixivFetchService.NovelSeriesMeta> cache) {
        PixivFetchService.NovelSeriesMeta cached = cache.get(seriesId);
        if (cached != null) {
            return cached;
        }
        PixivFetchService.NovelSeriesMeta meta;
        try {
            meta = pixivFetchService.fetchNovelSeriesMeta(seriesId, cookie);
        } catch (Exception e) {
            log.debug("Scheduled novel series {} enrichment skipped: {}", seriesId, pendingReason(e));
            meta = new PixivFetchService.NovelSeriesMeta("", "", List.of());
        }
        cache.put(seriesId, meta);
        return meta;
    }

    /** 本轮中达到自动重试上限、需要在最终 next_run_time 确定后再发送的通知事件。 */
    private record PendingExhaustedNotification(
            boolean novel, long workId, int attempts, long triggerTime, String reason) {}

    /**
     * 一轮运行内对单作品派发做异常分类、隔离表记账、连续失败熔断与到 N 过度访问检查的有状态封装。
     *
     * <p><b>串行预备 + 并发下载</b>：{@link #process} 在调度主线程串行执行取 meta / 筛选 / 解析 URL（异常在此就地分类），
     * 仅把阻塞下载经 {@link Semaphore} 限流后提交到下载线程池。下载完成回调（{@link #onComplete}，在池线程上）
     * 才更新 run 状态 / 隔离表 / 完成计数 / 清零连续失败计数。{@link #awaitAll} 等本轮全部在途下载收尾。
     *
     * <p><b>并发下的线程安全</b>：{@code completedDownloads} 与 {@code consecutiveFailures} 用 {@link AtomicInteger}；
     * 隔离表读写经 {@code pendingLock} 串行化（{@code incPendingAttempts}+阈值判定原子，通知在锁外发）。
     * {@code dispatchedSinceCheck} 仅调度主线程访问。「连续失败」熔断计数在并发下退化为「按完成顺序的连续」（可接受）。
     */
    private final class WorkRunner {
        private final long taskId;
        private final Long ackWarningTime;
        private final boolean novel;
        private final String cookie;
        /** 任务级单独代理（host:port，可空）：池线程上的阻塞下载也要套用同一覆盖。 */
        private final String proxy;
        private final Filters filters;
        private final Download download;
        private final ScheduleRunQueue.Run run;
        private final Semaphore permits;
        private final TaskExecutor pool;
        private final List<PendingExhaustedNotification> pendingNotifications;
        private final String sourceType;
        private final ScheduledWorkRunner workRunner;
        private final ScheduledCancellation capabilityCancellation;
        // 仅调度主线程顺序追加 / 读取（process 与 awaitAll 都在主线程），无需并发容器。
        private final List<CompletableFuture<Void>> inflight = new ArrayList<>();
        // 本轮系列富信息缓存（按 seriesId）：同一系列的多个章节只查一次 Pixiv 系列 AJAX，仅主线程访问。
        private final Map<Long, PixivFetchService.IllustSeriesMeta> illustSeriesCache = new HashMap<>();
        private final Map<Long, PixivFetchService.NovelSeriesMeta> novelSeriesCache = new HashMap<>();
        private final Object pendingLock = new Object();
        private final Set<Long> claimedWorkIds = ConcurrentHashMap.newKeySet();
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicInteger completedDownloads = new AtomicInteger(0);
        private int dispatchedSinceCheck = 0;

        WorkRunner(ScheduledTask task, boolean novel, String cookie, Filters filters, Download download,
                   ScheduleRunQueue.Run run, int concurrency, TaskExecutor pool,
                   List<PendingExhaustedNotification> pendingNotifications,
                   Map<String, ScheduledWorkRunner> workRunners,
                   ScheduledCancellation capabilityCancellation) throws ScheduleDefinitionException {
            this.taskId = task.id();
            this.ackWarningTime = acknowledgedWarningTime(task);
            this.novel = novel;
            this.cookie = cookie;
            this.proxy = task.proxySnapshot();
            this.filters = filters;
            this.download = download;
            this.run = run;
            this.permits = new Semaphore(Math.max(1, concurrency));
            this.pool = pool;
            this.pendingNotifications = pendingNotifications;
            this.sourceType = task.sourceType();
            this.workRunner = workRunners.get(novel ? ScheduledWorkKind.NOVEL : ScheduledWorkKind.ILLUST);
            if (this.workRunner == null) {
                throw new IllegalStateException("leased work runner unavailable");
            }
            this.capabilityCancellation = capabilityCancellation;
        }

        ScheduleRunQueue.Run run() {
            return run;
        }

        String workType() {
            return novel
                    ? PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL
                    : PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST;
        }

        void claimWithoutDispatch(long workId) {
            claimedWorkIds.add(workId);
        }

        /** 本轮实际成功完成的下载数（join 后才稳定）。 */
        int completed() {
            return completedDownloads.get();
        }

        /** 等本轮所有在途下载完成（幂等：可重复调用）。 */
        void awaitAll() {
            CompletableFuture<?>[] arr = inflight.toArray(new CompletableFuture[0]);
            try {
                CompletableFuture.allOf(arr).join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof Error) {
                    throw (Error) e.getCause();
                }
                throw new IllegalStateException(
                        "in-flight work did not finish with durable success or pending state", e);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "in-flight work did not finish with durable success or pending state", e);
            }
        }

        /**
         * @param isRetry true 表示来自隔离表重试（失败 {@code incPendingAttempts}）；否则新失败 {@code insertPending}
         * @return true 仅当成功把下载提交到线程池（不代表下载已成功——结果在 {@link #onComplete} 统计）
         */
        boolean process(String id, long workId, boolean isRetry)
                throws OveruseWarningException, ScheduleSuspendException, SchedulePauseException,
                ScheduleSourceUnavailableException {
            if (!claimedWorkIds.add(workId)) {
                return false;
            }
            // 协作式取消：用户在运行中按下「暂停」后，下一个作品派发前抛 PauseException 干净 unwind。
            // 已提交的下载在池里继续跑完不打断；此后不再发起新的派发。
            if (runState.isCancelRequested(taskId)) {
                throw new SchedulePauseException();
            }
            ensureCapabilityAvailable(capabilityCancellation, sourceType);
            DownloadJob job;
            try {
                job = novel
                        ? prepareNovel(id, workId, cookie, filters, download, run, novelSeriesCache, workRunner)
                        : prepareArtwork(id, workId, cookie, filters, download, run, illustSeriesCache, workRunner);
            } catch (HttpClientErrorException e) {
                int sc = e.getStatusCode().value();
                if (sc == 404 || sc == 403) {
                    // 真已删除 / 注销：跳过、不进隔离、不挡 watermark
                    run.mark(id, workType(), ScheduleRunQueue.STATUS_FAILED, "gone " + sc);
                    return false;
                }
                // 其它 4xx（含 429）：可恢复瞬时，隔离不计熔断
                recordRecoverable(workId, isRetry, "http " + sc);
                return false;
            } catch (PixivFetchService.PixivFetchException e) {
                // 受限 / 需登录（可恢复）：隔离 + 连续失败计数；连续 M 次熔断
                String reason = pendingReason(e);
                run.mark(id, workType(), ScheduleRunQueue.STATUS_FAILED, reason);
                recordRecoverable(workId, isRetry, reason);
                int cf = consecutiveFailures.incrementAndGet();
                if (cf >= scheduleConfig.getAuthFailureCircuitBreaker()) {
                    throw new ScheduleSuspendException(
                            ScheduleSuspendException.Reason.CIRCUIT_BREAKER, cf, reason);
                }
                return false;
            } catch (OveruseWarningException | ScheduleSuspendException e) {
                throw e;
            } catch (Exception e) {
                // 瞬时 IO / 解析错误：隔离、不计熔断
                String reason = pendingReason(e);
                run.mark(id, workType(), ScheduleRunQueue.STATUS_FAILED, reason);
                recordRecoverable(workId, isRetry, reason);
                return false;
            }

            if (job == null) {
                // 被筛选跳过：重试条目从隔离表移除（不再属于「待重试」）。
                if (isRetry) {
                    synchronized (pendingLock) {
                        store.deletePendingWork(taskId, workType(), Long.toString(workId));
                    }
                }
                return false;
            }

            ensureCapabilityAvailable(capabilityCancellation, sourceType);

            // 准备就绪：限流后提交到下载池并发执行；派发点（提交成功）即做过度访问 N 检查。
            submit(id, workId, isRetry, job);
            ensureCapabilityAvailable(capabilityCancellation, sourceType);
            afterDispatchCheckpoint();
            return true;
        }

        /** 取一个许可后把阻塞下载提交到池；完成回调与许可释放都在池线程的 finally 里。 */
        private void submit(String id, long workId, boolean isRetry, DownloadJob job) {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while scheduling download", e);
            }
            CompletableFuture<Void> f;
            try {
                f = CompletableFuture.runAsync(() -> {
                    try {
                        boolean[] ok = {false};
                        // 任务级单独代理：阻塞下载在共享下载池线程上执行（图片 / 动图 ZIP / 小说封面与内嵌图 / 下载后收藏
                        // 全部内联其中）；经 runScoped 套用线程级覆盖并在结束后必定清除，避免污染后续交互式下载的代理路由
                        // （跨任务上下文清理契约，见 OutboundProxyOverride#runScoped）。
                        OutboundProxyOverride.runScoped(proxy, () -> {
                            try {
                                if (!capabilityCancellation.isCancellationRequested()) {
                                    ok[0] = job.run();
                                }
                            } catch (Exception e) {
                                // 下载器抛出的 message 可能含上游错误 URL（带 PHPSESSID 查询串）或 cookie 头，先脱敏。
                                log.warn("Scheduled task {} download work {} threw: {}",
                                        taskId, workId,
                                        ScheduleCredentialRedactor.redact(String.valueOf(e.getMessage())));
                            }
                        });
                        onComplete(id, workId, isRetry, ok[0]);
                    } finally {
                        permits.release();
                    }
                }, pool);
            } catch (Throwable failure) {
                permits.release();
                throw propagate(failure);
            }
            inflight.add(f);
        }

        /** 下载完成回调（池线程）：成功清隔离 + 计数 + 清零连续失败；失败隔离、不计熔断。 */
        private void onComplete(String id, long workId, boolean isRetry, boolean ok) {
            if (ok) {
                run.mark(id, workType(), ScheduleRunQueue.STATUS_DOWNLOADED, null);
                // 本轮确实下载完成、且开启了「下载即自动翻译」的小说才登记：队列视图据此只对本轮真正提交过翻译的条目
                // 叠加翻译状态，不读 novelId 上一轮残留的终态（已下载跳过 / 关闭翻译的条目都不会被叠加）。
                if (novel && download.novelAutoTranslate()) {
                    run.markAutoTranslateSubmitted(id, workType());
                }
                consecutiveFailures.set(0);
                synchronized (pendingLock) {
                    store.deletePendingWork(taskId, workType(), Long.toString(workId));
                }
                completedDownloads.incrementAndGet();
            } else {
                // 下载器顶层异常被吞返 false：隔离、不计熔断
                run.mark(id, workType(), ScheduleRunQueue.STATUS_FAILED, "download failed");
                recordRecoverable(workId, isRetry, "download failed");
            }
        }

        private void recordRecoverable(long workId, boolean isRetry, String reason) {
            long now = System.currentTimeMillis();
            boolean exhausted = false;
            int attemptsAtLimit = 0;
            synchronized (pendingLock) {
                if (isRetry) {
                    store.incrementPendingAttempts(taskId, workType(), Long.toString(workId), now);
                    log.warn("Scheduled task {} retry work {} failed: {}", taskId, workId, reason);
                    // 刚跨过自动重试上限：发通知转人工。临界判断（== max）确保只在跨越那一次触发，
                    // 不会因为后续仍在表里的同行（attempts >= max 已被 retryPending 跳过）重复发。
                    int max = scheduleConfig.getPendingMaxAttempts();
                    ScheduledPendingWork pending = store.findPendingWork(
                            taskId, workType(), Long.toString(workId));
                    Integer attempts = pending == null ? null : pending.attempts();
                    if (attempts != null && attempts == max) {
                        exhausted = true;
                        attemptsAtLimit = attempts;
                    }
                } else {
                    ScheduledWork work = persistenceCodec.createWorkEnvelope(
                            workType(), Long.toString(workId));
                    store.upsertPendingWork(persistenceCodec.toPendingWork(
                            taskId, work, "PIXIV_RETRYABLE",
                            safeDetailJson("message", reason, "category", "recoverable"),
                            0, now, null));
                    log.warn("Scheduled task {} isolated work {}: {}", taskId, workId, reason);
                }
            }
            if (exhausted) {
                // worker 线程里只登记事件；最终 next_run_time 要等 runTask join 完、completedAt 确定后才能准确计算。
                pendingNotifications.add(new PendingExhaustedNotification(novel, workId, attemptsAtLimit, now, reason));
            }
        }

        /** 成功派发后到 N 触发过度访问检查；{@code WARNED} 干净 unwind（COOKIE_DEAD 轮内不双重判定，交给熔断）。 */
        private void afterDispatchCheckpoint() throws OveruseWarningException {
            if (cookie == null) {
                return; // 已降级匿名或本就无 cookie：读不到站内信
            }
            int n = scheduleConfig.getInboxCheckEvery();
            dispatchedSinceCheck++;
            if (n <= 0 || dispatchedSinceCheck % n != 0) {
                return;
            }
            // 必须传任务的 ackWarningTime：管理员已显式放行 / 延迟的同账号警告不应在轮内再次触发暂停。
            OveruseWarningService.Result r =
                    overuseWarningService.check(cookie, ackWarningTime, System.currentTimeMillis());
            if (r.isWarned()) {
                throw new OveruseWarningException(r.modifiedAt(), r.excerpt());
            }
        }
    }

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
        String kindLabel = messages.get(locale, event.novel()
                ? "mail.template.pending-exhausted.kind.novel"
                : "mail.template.pending-exhausted.kind.illust");
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", task.name() == null ? "-" : task.name());
        ph.put("task_id", String.valueOf(task.id()));
        ph.put("task_type", taskTypeLabel(locale, task.sourceType()));
        ph.put("task_trigger", triggerLabel(locale, task.triggerKind(), task.intervalMinutes(), task.cronExpr()));
        ph.put("work_id", String.valueOf(event.workId()));
        ph.put("work_kind", kindLabel);
        ph.put("work_url", workUrl(event.novel(), event.workId()));
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

    /**
     * 计划任务类型的本地化标签（与邮件 / 推送共用同一组 i18n key）。类型→标签 key 由各来源对象
     * （{@link ScheduledSource#notificationLabelKey()}）承载，经来源注册中心解析——调度器不再按
     * 枚举 switch 取标签。解析不到（不应发生：自动挂起态不发通知）退化为「-」。
     */
    private String taskTypeLabel(Locale locale, String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return "-";
        }
        SchedulePlanningLease planning = scheduleCapabilityRegistry.tryAcquireSource(sourceType).orElse(null);
        if (planning == null) {
            return "-";
        }
        try (planning) {
            ScheduledSource source = planning.legacySourceProvider()
                    .filter(ScheduledSource.class::isInstance)
                    .map(ScheduledSource.class::cast)
                    .orElse(null);
            if (source == null) {
                return "-";
            }
            return messages.get(locale, source.notificationLabelKey());
        }
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

    /** 失败作品的 Pixiv 直链（外部静态链接，不指向本服务）：小说走 novel show，其余走 artworks。 */
    private static String workUrl(boolean novel, long workId) {
        return novel
                ? "https://www.pixiv.net/novel/show.php?id=" + workId
                : "https://www.pixiv.net/artworks/" + workId;
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
        String displayName = setupService.getDisplayName();
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

    /**
     * 插画去重谓词：按「实际目录检测」开关选 isArtworkDownloaded(verify) 或裸 hasArtwork；小说恒为 hasNovel。
     * 「允许已删除的作品被重新下载」开启时，软删除标记的记录视为未下载（重新下载落库会替换残留行、标记复位）；
     * 关闭（默认）时软删除记录照样命中「已下载」而被跳过。verify 对软删除行无意义（文件本就已删），
     * 且 {@code isArtworkDownloaded} 对软删除行短路返回已下载，故开启重下时先按 deleted 排除。
     */
    private LongPredicate alreadyDownloadedPredicate(boolean novel, Download download) {
        if (novel) {
            return download.redownloadDeleted() ? novelMetadataRepository::hasActiveNovel : novelMetadataRepository::hasNovel;
        }
        if (download.redownloadDeleted()) {
            return download.verifyFiles()
                    ? id -> !pixivDatabase.isArtworkDeleted(id) && artworkDownloader.isArtworkDownloaded(id, true)
                    : pixivDatabase::hasActiveArtwork;
        }
        return download.verifyFiles()
                ? id -> artworkDownloader.isArtworkDownloaded(id, true)
                : pixivDatabase::hasArtwork;
    }

    /** 作品间礼貌延迟：取任务级「作品间隔」（毫秒）；缺省 / 0 不延迟。 */
    private Runnable politeDelayFor(Download download) {
        long ms = download.intervalMs() == null ? 0L : Math.max(0L, download.intervalMs());
        return () -> sleepMs(ms);
    }

    /** 有效作品级并发数：clamp 到对应下载池大小，避免在池外堆积过多在途任务。 */
    private int effectiveConcurrency(boolean novel, int taskConcurrent) {
        int poolSize = novel ? downloadConfig.getNovelMaxConcurrent() : downloadConfig.getMaxConcurrent();
        return Math.max(1, Math.min(Math.max(1, taskConcurrent), poolSize));
    }

    private static void sleepMs(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void watermarkPageDelay() {
        sleepMs(WATERMARK_PAGE_DELAY_MS);
    }

}
