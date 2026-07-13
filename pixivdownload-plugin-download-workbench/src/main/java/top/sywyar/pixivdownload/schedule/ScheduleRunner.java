package top.sywyar.pixivdownload.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleSingleCapabilityLease;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 计划任务调度器：本功能<b>唯一</b>的 {@code @Scheduled} tick。
 *
 * <p>每隔 {@code schedule.tick-interval-ms} 查一次到期任务（{@code enabled && next_run_time<=now}），
 * <b>串行</b>逐个交给 {@link ScheduleExecutor} 执行。
 *
 * <p><b>合规：</b>这是功能型调度、不是 DB 清理，因此<b>不</b>进 {@code MaintenanceCoordinator}
 * （后者的 pause / 503 短路语义是给 DB 维护的），tick 内也不顺手做 DB 清理。调度以管理员身份运行、不受限流 / 配额。
 */
@Slf4j
@PluginManagedBean
@RequiredArgsConstructor
public class ScheduleRunner {

    private final ScheduledTaskStore store;
    private final ScheduleExecutor executor;
    private final ScheduleConfig config;
    private final ScheduleRunState runState;
    private final ScheduleCapabilityRegistry scheduleCapabilityRegistry;

    /** 单飞：上一轮 tick 仍在跑（任务多 / 抓取慢）时直接跳过本轮，避免重入。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${schedule.tick-interval-ms:60000}")
    public void tick() {
        var handle = scheduleCapabilityRegistry.resolveOwner(DownloadWorkbenchPlugin.ID).orElse(null);
        if (handle == null) {
            return;
        }
        ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> hostLease =
                scheduleCapabilityRegistry.prepareAcquire(handle).orElse(null);
        try (hostLease) {
            if (hostLease == null || !scheduleCapabilityRegistry.activate(hostLease)) {
                return;
            }
            tickLeased(hostLease.cancellation());
        }
    }

    private void tickLeased(ScheduledCancellation cancellation) {
        if (!config.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Schedule tick skipped: previous run still in progress");
            return;
        }
        List<QueuedTask> queued = new ArrayList<>();
        Throwable fatalFailure = null;
        try {
            long now = System.currentTimeMillis();
            recoverOrphanedClaims(cancellation);
            recoverAvailableTasks(cancellation, now);
            List<ScheduledTask> due = store.findDue(now);
            // 「同一时刻只跑一个」串行约束：本轮全部到期任务先标记排队中，再逐个转为运行中。
            queued = new ArrayList<>(due.size());
            for (ScheduledTask task : due) {
                if (cancellation.isCancellationRequested()) {
                    break;
                }
                ScheduleRunState.Claim claim = runState.tryMarkQueued(task.id());
                if (claim == null) {
                    log.debug("Scheduled task {} skipped by tick: already queued or running", task.id());
                    continue;
                }
                String claimToken = null;
                ScheduleRunToken runToken;
                try {
                    claimToken = UUID.randomUUID().toString();
                    runToken = store.tryQueueDue(
                                    task.id(), task.stateVersion(), claimToken,
                                    System.currentTimeMillis())
                            .orElse(null);
                } catch (Throwable e) {
                    try {
                        if (claimToken != null) {
                            executor.releaseClaim(task.id(), claimToken, task.nextRunTime());
                        }
                    } catch (Throwable cleanupFailure) {
                        ScheduleExecutor.addCleanupFailure(e, cleanupFailure);
                    }
                    try {
                        runState.clear(claim);
                    } catch (Throwable cleanupFailure) {
                        ScheduleExecutor.addCleanupFailure(e, cleanupFailure);
                    }
                    throw ScheduleExecutor.propagate(e);
                }
                if (runToken == null) {
                    runState.clear(claim);
                    log.debug("Scheduled task {} skipped by tick: durable claim rejected", task.id());
                    continue;
                }
                queued.add(new QueuedTask(task, claim, runToken));
            }
            while (!queued.isEmpty()) {
                QueuedTask queuedTask = queued.remove(0);
                if (cancellation.isCancellationRequested()) {
                    releaseQueuedTask(queuedTask);
                    continue;
                }
                try {
                    executor.runTaskAndRecord(
                            queuedTask.task(), queuedTask.claim(), queuedTask.runToken());
                } catch (Exception e) {
                    // 单任务异常不应中断整轮（executor 内已尽量兜底，这里再保一层）
                    releaseQueuedTask(queuedTask);
                    log.error("Scheduled task {} unexpected failure [{}]",
                            queuedTask.task().id(), e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.error("Schedule tick failed while claiming due tasks [{}]",
                    e.getClass().getSimpleName());
        } catch (Throwable e) {
            fatalFailure = e;
        } finally {
            for (QueuedTask queuedTask : queued) {
                try {
                    releaseQueuedTask(queuedTask);
                } catch (Throwable cleanupFailure) {
                    if (fatalFailure == null) {
                        fatalFailure = cleanupFailure;
                    } else {
                        ScheduleExecutor.addCleanupFailure(fatalFailure, cleanupFailure);
                    }
                }
            }
            running.set(false);
        }
        if (fatalFailure != null) {
            throw ScheduleExecutor.propagate(fatalFailure);
        }
    }

    private void releaseQueuedTask(QueuedTask queuedTask) {
        try {
            executor.releaseQueued(queuedTask.task().id(), queuedTask.runToken());
        } catch (Throwable failure) {
            try {
                executor.releaseClaim(
                        queuedTask.task().id(),
                        queuedTask.runToken().claimToken(),
                        queuedTask.task().nextRunTime());
            } catch (Throwable cleanupFailure) {
                ScheduleExecutor.addCleanupFailure(failure, cleanupFailure);
            }
            try {
                runState.clear(queuedTask.claim());
            } catch (Throwable cleanupFailure) {
                ScheduleExecutor.addCleanupFailure(failure, cleanupFailure);
            }
            throw ScheduleExecutor.propagate(failure);
        }
        try {
            runState.clear(queuedTask.claim());
        } catch (Throwable failure) {
            throw ScheduleExecutor.propagate(failure);
        }
    }

    private record QueuedTask(
            ScheduledTask task,
            ScheduleRunState.Claim claim,
            ScheduleRunToken runToken) {
    }

    private void recoverOrphanedClaims(ScheduledCancellation cancellation) {
        for (ScheduledTask task : store.findAll()) {
            if (cancellation.isCancellationRequested()) {
                return;
            }
            if (task.runState() == null || runState.get(task.id()) != null) {
                continue;
            }
            try {
                executor.recoverOrphanedClaim(task);
            } catch (RuntimeException e) {
                log.error("Scheduled task {} orphaned claim recovery failed [{}]",
                        task.id(), e.getClass().getSimpleName());
            }
        }
    }

    private void recoverAvailableTasks(ScheduledCancellation cancellation, long now) {
        for (ScheduledTask task : store.findAll()) {
            if (cancellation.isCancellationRequested()) {
                return;
            }
            ScheduleSuspendReason reason = task.suspendReason();
            if (task.runState() != null
                    || (reason != ScheduleSuspendReason.SOURCE_UNAVAILABLE
                    && reason != ScheduleSuspendReason.EXECUTOR_UNAVAILABLE
                    && reason != ScheduleSuspendReason.QUIESCED)) {
                continue;
            }
            if (executor.canResolveExecution(task)) {
                store.resume(task.id(), task.stateVersion(), reason, task.suspendCode(), now);
            }
        }
    }
}
