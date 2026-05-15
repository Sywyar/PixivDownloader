package top.sywyar.pixivdownload.maintenance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 维护窗口协调器。
 *
 * <p>调度：Cron {@code 0 0 10 ? * MON} 每周一 10:00 触发，依次执行已注册的 {@link MaintenanceTask}。
 * 维护期间 {@link #isPaused()} 返回 {@code true}，由 {@code AuthFilter} 拦截非本地管理员请求并返回 503。
 *
 * <p>可通过 POST {@code /api/admin/maintenance/run}（仅本地管理员）手动触发，便于排错。
 *
 * <p>{@code maintenance.enabled} 在运行时被读取（支持热重载）：调度触发与手动触发都会先检查该开关。
 */
@Slf4j
@Component
public class MaintenanceCoordinator {

    private final List<MaintenanceTask> tasks;
    private final MaintenanceProperties properties;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Long lastStartedAt;
    private volatile Long lastFinishedAt;
    private volatile String lastTriggeredBy;

    public MaintenanceCoordinator(List<MaintenanceTask> tasks, MaintenanceProperties properties) {
        this.tasks = tasks == null ? List.of() : List.copyOf(tasks);
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public boolean isPaused() {
        return paused.get();
    }

    public Long getLastStartedAt() {
        return lastStartedAt;
    }

    public Long getLastFinishedAt() {
        return lastFinishedAt;
    }

    public String getLastTriggeredBy() {
        return lastTriggeredBy;
    }

    @Scheduled(cron = "0 0 10 ? * MON")
    public void runScheduled() {
        if (!properties.isEnabled()) {
            log.debug("Skipping scheduled maintenance: maintenance.enabled=false");
            return;
        }
        runMaintenance("schedule");
    }

    /**
     * 手动触发；若禁用或已在运行返回 {@code false}。
     */
    public synchronized boolean runManually() {
        if (!properties.isEnabled()) return false;
        if (paused.get()) return false;
        runMaintenance("manual");
        return true;
    }

    private synchronized void runMaintenance(String trigger) {
        if (!paused.compareAndSet(false, true)) {
            log.warn("Maintenance is already running, skipping new {} trigger", trigger);
            return;
        }
        long started = System.currentTimeMillis();
        lastStartedAt = started;
        lastTriggeredBy = trigger;
        log.info("Maintenance window OPEN ({}); {} task(s) registered", trigger, tasks.size());
        try {
            MaintenanceContext ctx = new MaintenanceContext(trigger, started);
            for (MaintenanceTask task : tasks) {
                long taskStart = System.currentTimeMillis();
                String name = task.name();
                try {
                    log.info("  → Maintenance task '{}' START", name);
                    task.execute(ctx);
                    log.info("  ← Maintenance task '{}' OK ({} ms)", name,
                            System.currentTimeMillis() - taskStart);
                } catch (Throwable t) {
                    log.error("  ✗ Maintenance task '{}' FAILED ({} ms): {}",
                            name, System.currentTimeMillis() - taskStart, t.getMessage(), t);
                }
            }
        } finally {
            long finished = System.currentTimeMillis();
            lastFinishedAt = finished;
            paused.set(false);
            log.info("Maintenance window CLOSED ({} ms total)", finished - started);
        }
    }
}
