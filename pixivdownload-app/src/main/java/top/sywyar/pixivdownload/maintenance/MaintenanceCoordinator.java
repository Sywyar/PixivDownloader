package top.sywyar.pixivdownload.maintenance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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

    private final List<MaintenanceTask> tasks;
    private final MaintenanceProperties properties;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Long lastStartedAt;
    private volatile Long lastFinishedAt;
    private volatile String lastTriggeredBy;
    private volatile String lastScheduledSlot;
    private volatile String lastInvalidScheduleWarning;

    /** Spring 上下文外 / 单元测试构造：不按插件启用状态过滤（全部注入的任务都执行）。 */
    public MaintenanceCoordinator(List<MaintenanceTask> tasks, MaintenanceProperties properties) {
        this(tasks, properties, null);
    }

    /**
     * Spring 构造：按插件启用状态过滤维护任务。被禁用的插件经 {@code maintenanceTasks()} 声明拥有的
     * 任务不进入维护窗口；不被任何插件声明的任务（核心任务）始终执行。
     */
    @Autowired
    public MaintenanceCoordinator(List<MaintenanceTask> tasks, MaintenanceProperties properties,
                                  PluginRegistry pluginRegistry) {
        this.tasks = filterDisabledPluginTasks(tasks == null ? List.of() : tasks, pluginRegistry);
        this.properties = properties;
    }

    /**
     * 剔除被禁用插件拥有的维护任务。归属由各插件 {@code maintenanceTasks()} 声明（按类型）；
     * 用 {@code isInstance} 匹配以兼容 CGLIB 代理（代理是声明类型的子类）。{@code pluginRegistry} 为
     * {@code null}（非 Spring 构造）或无禁用插件时不过滤。
     */
    private static List<MaintenanceTask> filterDisabledPluginTasks(List<MaintenanceTask> tasks,
                                                                   PluginRegistry pluginRegistry) {
        if (pluginRegistry == null) {
            return List.copyOf(tasks);
        }
        Set<Class<?>> disabledTaskTypes = pluginRegistry.disabledPlugins().stream()
                .flatMap(plugin -> plugin.maintenanceTasks().stream())
                .collect(Collectors.toSet());
        if (disabledTaskTypes.isEmpty()) {
            return List.copyOf(tasks);
        }
        return tasks.stream()
                .filter(task -> disabledTaskTypes.stream().noneMatch(type -> type.isInstance(task)))
                .toList();
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
        MaintenanceStatusHolder.begin(trigger, tasks.size());
        log.info(MessageBundles.get("maintenance.log.window.opened", trigger, tasks.size()));
        try {
            MaintenanceContext ctx = new MaintenanceContext(trigger, started);
            int index = 0;
            for (MaintenanceTask task : tasks) {
                index++;
                long taskStart = System.currentTimeMillis();
                String name = task.name();
                MaintenanceStatusHolder.enterTask(trigger, index, tasks.size(), name, taskStart);
                try {
                    log.info(MessageBundles.get("maintenance.log.task.start", name));
                    task.execute(ctx);
                    log.info(MessageBundles.get("maintenance.log.task.ok", name,
                            System.currentTimeMillis() - taskStart));
                } catch (Throwable t) {
                    log.error(MessageBundles.get("maintenance.log.task.failed",
                            name, System.currentTimeMillis() - taskStart, t.getMessage()), t);
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
