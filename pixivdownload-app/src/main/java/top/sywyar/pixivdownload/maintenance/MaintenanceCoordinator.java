package top.sywyar.pixivdownload.maintenance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.notification.NotificationDispatcher;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 维护窗口协调器。
 *
 * <p>调度：每分钟检查一次 {@code maintenance.<weekday>.enabled/time}，命中后依次执行已注册的 {@link MaintenanceTask}。
 * 维护期间 {@link #isPaused()} 返回 {@code true}，由 {@code AuthFilter} 拦截非本地管理员请求并返回 503。
 *
 * <p>可通过 POST {@code /api/admin/maintenance/run}（仅本地管理员）手动触发，便于排错。
 *
 * <p>{@code maintenance.enabled} 在运行时被读取（支持热重载）：调度触发与手动触发都会先检查该开关。
 */
@Slf4j
@Component
public class MaintenanceCoordinator {

    private static final DateTimeFormatter SLOT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter FAILURE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final NotificationDispatcher NOOP_NOTIFICATION = (scenario, locale, placeholders) -> { };

    private final MaintenanceTaskRegistry taskRegistry;
    private final MaintenanceProperties properties;
    private final NotificationDispatcher notificationDispatcher;
    private final AppMessages messages;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Long lastStartedAt;
    private volatile Long lastFinishedAt;
    private volatile String lastTriggeredBy;
    private volatile String lastScheduledSlot;
    private volatile String lastInvalidScheduleWarning;

    /** Spring 上下文外 / 单元测试构造：全部传入任务视为始终保留的核心任务。 */
    public MaintenanceCoordinator(List<MaintenanceTask> tasks, MaintenanceProperties properties) {
        this(new MaintenanceTaskRegistry(tasks), properties, NOOP_NOTIFICATION, null);
    }

    /** 测试构造：不对外发送通知。 */
    public MaintenanceCoordinator(MaintenanceTaskRegistry taskRegistry, MaintenanceProperties properties) {
        this(taskRegistry, properties, NOOP_NOTIFICATION, null);
    }

    /** Spring 构造：每个维护窗口从 owner/publication-aware 注册中心取得一次稳定快照。 */
    @Autowired
    public MaintenanceCoordinator(MaintenanceTaskRegistry taskRegistry,
                                  MaintenanceProperties properties,
                                  NotificationDispatcher notificationDispatcher,
                                  AppMessages messages) {
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "maintenance task registry");
        this.properties = properties;
        this.notificationDispatcher = Objects.requireNonNull(notificationDispatcher, "notification dispatcher");
        this.messages = messages;
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

    @Scheduled(cron = "0 * * * * *")
    public void runScheduled() {
        runScheduledIfDue(LocalDateTime.now());
    }

    synchronized boolean runScheduledIfDue(LocalDateTime now) {
        if (!properties.isEnabled()) {
            log.debug(MessageBundles.get("maintenance.log.scheduled.disabled"));
            return false;
        }
        if (now == null) {
            return false;
        }

        DayOfWeek day = now.getDayOfWeek();
        MaintenanceProperties.DaySchedule schedule = properties.scheduleFor(day);
        if (!schedule.isEnabled()) {
            return false;
        }

        Optional<LocalTime> scheduledTime = properties.scheduledTime(day);
        if (scheduledTime.isEmpty()) {
            warnInvalidScheduleOnce(day, schedule.getTime());
            return false;
        }

        LocalDateTime slot = now.truncatedTo(ChronoUnit.MINUTES);
        LocalTime expected = scheduledTime.get();
        if (!expected.equals(slot.toLocalTime())) {
            return false;
        }

        String slotId = slot.toLocalDate() + "T" + expected.format(SLOT_TIME_FORMATTER);
        if (Objects.equals(lastScheduledSlot, slotId)) {
            return false;
        }
        lastScheduledSlot = slotId;
        runMaintenance("schedule");
        return true;
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
            log.warn(MessageBundles.get("maintenance.log.already-running", trigger));
            return;
        }
        long started = System.currentTimeMillis();
        lastStartedAt = started;
        lastTriggeredBy = trigger;
        List<MaintenanceTask> tasks = taskRegistry.tasks();
        MaintenanceStatusHolder.begin(trigger, tasks.size());
        log.info(MessageBundles.get("maintenance.log.window.opened", trigger, tasks.size()));
        try {
            MaintenanceContext ctx = new MaintenanceContext(
                    trigger, started, MaintenanceStatusHolder::updateProgress);
            int index = 0;
            for (MaintenanceTask task : tasks) {
                index++;
                long taskStart = System.currentTimeMillis();
                String name = "maintenance-task-" + index;
                boolean statusEntered = false;
                try {
                    name = task.name();
                    MaintenanceStatusHolder.enterTask(trigger, index, tasks.size(), name, taskStart);
                    statusEntered = true;
                    log.info(MessageBundles.get("maintenance.log.task.start", name));
                    task.execute(ctx);
                    log.info(MessageBundles.get("maintenance.log.task.ok", name,
                            System.currentTimeMillis() - taskStart));
                } catch (Throwable t) {
                    if (!statusEntered) {
                        MaintenanceStatusHolder.enterTask(trigger, index, tasks.size(), name, taskStart);
                    }
                    log.error(MessageBundles.get("maintenance.log.task.failed",
                            name, System.currentTimeMillis() - taskStart, t.getMessage()), t);
                    notifyTaskFailure(name, trigger, t);
                }
            }
        } finally {
            long finished = System.currentTimeMillis();
            lastFinishedAt = finished;
            paused.set(false);
            MaintenanceStatusHolder.clear();
            log.info(MessageBundles.get("maintenance.log.window.closed", finished - started));
        }
    }

    private void notifyTaskFailure(String taskName, String trigger, Throwable failure) {
        Locale locale = messages == null
                ? Locale.getDefault()
                : messages.normalizeLocale(Locale.getDefault());
        String safeTaskName = bounded(taskName);
        String safeTrigger = messages == null
                ? bounded(trigger)
                : messages.getOrDefault(locale, "notification.system.maintenance-trigger." + trigger,
                bounded(trigger));
        String failedAt = LocalDateTime.now().format(FAILURE_TIME_FORMATTER);
        String errorType = bounded(failure == null ? null : failure.getClass().getSimpleName());
        notificationDispatcher.notify(NotificationScenario.MAINTENANCE_TASK_FAILED, locale, Map.of(
                "task_name", safeTaskName,
                "task_name_html", HtmlUtils.htmlEscape(safeTaskName),
                "trigger", safeTrigger,
                "trigger_html", HtmlUtils.htmlEscape(safeTrigger),
                "failed_at", failedAt,
                "failed_at_html", HtmlUtils.htmlEscape(failedAt),
                "error_type", errorType,
                "error_type_html", HtmlUtils.htmlEscape(errorType)));
    }

    private static String bounded(String value) {
        String normalized = value == null || value.isBlank()
                ? "unknown"
                : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private void warnInvalidScheduleOnce(DayOfWeek day, String value) {
        String key = day + ":" + value;
        if (Objects.equals(lastInvalidScheduleWarning, key)) {
            return;
        }
        lastInvalidScheduleWarning = key;
        log.warn(MessageBundles.get("maintenance.log.invalid-schedule",
                day, day.name().toLowerCase(Locale.ROOT), value));
    }
}
