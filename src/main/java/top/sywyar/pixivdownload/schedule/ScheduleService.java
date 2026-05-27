package top.sywyar.pixivdownload.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskDatabase;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskInsert;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;

import java.util.List;

/**
 * 计划任务的增删改查、Cookie 授权与「立即运行」入口。
 *
 * <p>运行编排在 {@link ScheduleExecutor} / {@link ScheduleRunner}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduledTaskDatabase database;
    private final ScheduleExecutor executor;
    private final ScheduleConfig config;
    private final ScheduleRunState runState;

    public List<ScheduleTaskView> list() {
        return database.mapper().findAll().stream()
                .map(t -> ScheduleTaskView.of(t, runState.get(t.id())))
                .toList();
    }

    public ScheduleTaskView get(long id) {
        ScheduledTask task = database.mapper().findById(id);
        if (task == null) {
            throw LocalizedException.badRequest("schedule.error.not-found", "计划任务不存在: {0}", id);
        }
        return ScheduleTaskView.of(task, runState.get(id));
    }

    @Transactional
    public ScheduleTaskView create(ScheduleTaskRequest req) {
        if (database.mapper().countAll() >= config.getMaxTasks()) {
            throw LocalizedException.badRequest(
                    "schedule.error.max-tasks", "计划任务数量已达上限: {0}", config.getMaxTasks());
        }
        String triggerKind = validateTrigger(req);
        long now = System.currentTimeMillis();

        ScheduledTaskInsert row = new ScheduledTaskInsert();
        row.setName(req.getName().trim());
        row.setEnabled(true);
        row.setType(req.getType());
        row.setParamsJson(req.getParamsJson());
        row.setTriggerKind(triggerKind);
        row.setIntervalMinutes(req.getIntervalMinutes());
        row.setCronExpr(emptyToNull(req.getCronExpr()));
        // 创建时默认受限模式（无 cookie）；管理员可随后授权 cookie 升级为 bound
        row.setCookieMode(ScheduledTask.COOKIE_RESTRICTED);
        row.setCookieSnapshot(null);
        row.setNextRunTime(ScheduleTiming.computeNextRun(
                triggerKind, req.getIntervalMinutes(), req.getCronExpr(), now));
        row.setLastRunTime(null);
        row.setLastStatus(null);
        row.setLastMessage(null);
        row.setWatermarkId(null);
        row.setRunStartedTime(null);
        row.setCreatedTime(now);

        database.mapper().insert(row);
        return get(row.getId());
    }

    @Transactional
    public ScheduleTaskView update(long id, ScheduleTaskRequest req) {
        requireExisting(id);
        String triggerKind = validateTrigger(req);
        Long nextRun = ScheduleTiming.computeNextRun(
                triggerKind, req.getIntervalMinutes(), req.getCronExpr(), System.currentTimeMillis());
        database.mapper().updateDefinition(
                id, req.getName().trim(), req.getType(), req.getParamsJson(),
                triggerKind, req.getIntervalMinutes(), emptyToNull(req.getCronExpr()), nextRun);
        return get(id);
    }

    @Transactional
    public ScheduleTaskView setEnabled(long id, boolean enabled) {
        requireExisting(id);
        database.mapper().updateEnabled(id, enabled);
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        requireExisting(id);
        // 任务删除即清 cookie 快照（行删除连带 cookie_snapshot 一并消失）
        database.mapper().delete(id);
    }

    /**
     * 为任务快照绑定 Cookie。校验含 {@code PHPSESSID} 后写入；cookie 绝不写日志 / 回显。
     */
    @Transactional
    public ScheduleTaskView authorizeCookie(long id, String cookie) {
        requireExisting(id);
        if (cookie == null || !cookie.contains("PHPSESSID")) {
            throw LocalizedException.badRequest(
                    "schedule.error.cookie-invalid", "Cookie 无效：缺少 PHPSESSID");
        }
        database.mapper().updateCookie(id, cookie.trim(), ScheduledTask.COOKIE_BOUND);
        return get(id);
    }

    /** 解除 Cookie 授权：清空快照并回到受限模式。 */
    @Transactional
    public ScheduleTaskView revokeCookie(long id) {
        requireExisting(id);
        database.mapper().updateCookie(id, null, ScheduledTask.COOKIE_RESTRICTED);
        return get(id);
    }

    /** 立即运行一次（后台异步执行，不阻塞调用方）。 */
    public void runOnce(long id) {
        requireExisting(id);
        executor.runTaskAsync(id);
    }

    // ── 内部 ────────────────────────────────────────────────────────────────────

    private void requireExisting(long id) {
        if (database.mapper().findById(id) == null) {
            throw LocalizedException.badRequest("schedule.error.not-found", "计划任务不存在: {0}", id);
        }
    }

    private String validateTrigger(ScheduleTaskRequest req) {
        String kind = req.getTriggerKind() == null ? "" : req.getTriggerKind().trim();
        if (ScheduledTask.TRIGGER_INTERVAL.equals(kind)) {
            if (req.getIntervalMinutes() == null || req.getIntervalMinutes() <= 0) {
                throw LocalizedException.badRequest(
                        "schedule.error.interval-invalid", "固定周期分钟数必须为正整数");
            }
            return kind;
        }
        if (ScheduledTask.TRIGGER_CRON.equals(kind)) {
            String expr = req.getCronExpr();
            if (expr == null || expr.isBlank() || !CronExpression.isValidExpression(expr.trim())) {
                throw LocalizedException.badRequest(
                        "schedule.error.cron-invalid", "Cron 表达式无效");
            }
            return kind;
        }
        throw LocalizedException.badRequest("schedule.error.trigger-invalid", "触发方式无效");
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
