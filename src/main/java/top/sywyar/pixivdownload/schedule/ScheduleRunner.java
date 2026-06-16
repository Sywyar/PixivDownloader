package top.sywyar.pixivdownload.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskDatabase;

import java.util.ArrayList;
import java.util.List;
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

    private final ScheduledTaskDatabase database;
    private final ScheduleExecutor executor;
    private final ScheduleConfig config;
    private final ScheduleRunState runState;

    /** 单飞：上一轮 tick 仍在跑（任务多 / 抓取慢）时直接跳过本轮，避免重入。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${schedule.tick-interval-ms:60000}")
    public void tick() {
        if (!config.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Schedule tick skipped: previous run still in progress");
            return;
        }
        try {
            List<ScheduledTask> due = database.mapper().findDue(System.currentTimeMillis());
            // 「同一时刻只跑一个」串行约束：本轮全部到期任务先标记排队中，再逐个转为运行中。
            List<QueuedTask> queued = new ArrayList<>(due.size());
            for (ScheduledTask task : due) {
                ScheduleRunState.Claim claim = runState.tryMarkQueued(task.id());
                if (claim == null) {
                    log.debug("Scheduled task {} skipped by tick: already queued or running", task.id());
                    continue;
                }
                queued.add(new QueuedTask(task, claim));
            }
            for (QueuedTask queuedTask : queued) {
                try {
                    executor.runTaskAndRecord(queuedTask.task(), queuedTask.claim());
                } catch (Exception e) {
                    // 单任务异常不应中断整轮（executor 内已尽量兜底，这里再保一层）
                    log.error("Scheduled task {} unexpected failure: {}", queuedTask.task().id(), e.getMessage(), e);
                }
            }
        } finally {
            running.set(false);
        }
    }

    private record QueuedTask(ScheduledTask task, ScheduleRunState.Claim claim) {
    }
}
