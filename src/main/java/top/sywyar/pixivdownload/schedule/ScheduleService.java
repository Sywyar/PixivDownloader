package top.sywyar.pixivdownload.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.novel.NovelAutoTranslateService;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskDatabase;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskInsert;
import top.sywyar.pixivdownload.schedule.dto.AccountResumeRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final ScheduleRunQueue runQueue;
    private final NovelAutoTranslateService novelAutoTranslateService;

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
        row.setProxySnapshot(null);
        row.setNextRunTime(ScheduleTiming.computeNextRun(
                triggerKind, req.getIntervalMinutes(), req.getCronExpr(), now));
        row.setLastRunTime(null);
        row.setLastStatus(null);
        row.setLastMessage(null);
        row.setWatermarkId(null);
        row.setRunStartedTime(null);
        row.setAccountId(null);
        row.setAckWarningTime(null);
        row.setPendingRetryArmed(0);
        row.setCreatedTime(now);

        database.mapper().insert(row);
        return get(row.getId());
    }

    @Transactional
    public ScheduleTaskView update(long id, ScheduleTaskRequest req) {
        requireExisting(id);
        requireNotBusy(id);
        String triggerKind = validateTrigger(req);
        Long nextRun = ScheduleTiming.computeNextRun(
                triggerKind, req.getIntervalMinutes(), req.getCronExpr(), System.currentTimeMillis());
        database.mapper().updateDefinition(
                id, req.getName().trim(), req.getType(), req.getParamsJson(),
                triggerKind, req.getIntervalMinutes(), emptyToNull(req.getCronExpr()), nextRun);
        // 编辑后的任务可能换了 type / source / filters，旧隔离表里的 workId 在新定义下没意义
        // （甚至 kind 也可能从插画切到小说）。一并清掉，避免用错误的下载管线复活旧失败条目。
        database.mapper().deleteAllPending(id);
        return get(id);
    }

    @Transactional
    public ScheduleTaskView setEnabled(long id, boolean enabled) {
        requireExisting(id);
        requireNotBusy(id);
        database.mapper().updateEnabled(id, enabled);
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        requireExisting(id);
        requireNotBusy(id);
        // 隔离表无 FK / 触发器，必须显式清理；否则同 task_id 在 AUTOINCREMENT 下虽不复用，
        // 但残留行会成为孤儿数据。
        database.mapper().deleteAllPending(id);
        // 任务删除即清 cookie 快照（行删除连带 cookie_snapshot 一并消失）
        database.mapper().delete(id);
        // 连带清除内存中的本轮运行队列，避免删除后残留
        runQueue.remove(id);
    }

    /**
     * 取该任务最近一轮运行队列（管理员专用，供前端「本轮队列详情」展示）。
     * 从未运行或进程重启后返回空队列（{@code startedTime=null}、{@code items} 为空）。
     */
    public ScheduleQueueView queue(long id) {
        requireExisting(id);
        ScheduleRunQueue.Run run = runQueue.get(id);
        if (run == null) {
            return new ScheduleQueueView(id, null, false, 0, List.of());
        }
        List<ScheduleQueueView.Item> items = run.snapshot().stream()
                .map(this::toQueueItem)
                .toList();
        return new ScheduleQueueView(id, run.startedTime(), run.truncated(), items.size(), items);
    }

    /**
     * 把内存队列条目映射为对外视图，并为小说叠加「下载即自动翻译」的实时状态（读取时取，非阻塞）。
     *
     * <p>仅对<b>本轮确实提交过自动翻译</b>的条目（{@link ScheduleRunQueue.Item#isAutoTranslateSubmitted()}）叠加：
     * 翻译状态按 {@code novelId} 全局保存且终态不随轮次清理，若对所有小说一律叠加，会把同一 {@code novelId} 上一轮
     * （甚至别的任务）译过的旧 DONE/FAILED 误显示到本轮「已存在跳过」「未开启翻译」的条目上。
     */
    private ScheduleQueueView.Item toQueueItem(ScheduleRunQueue.Item it) {
        String translatePhase = null;
        Long translateElapsed = null;
        Integer translatePending = null;
        if (ScheduleRunQueue.KIND_NOVEL.equals(it.getKind()) && it.isAutoTranslateSubmitted()) {
            try {
                long novelId = Long.parseLong(it.getId());
                NovelAutoTranslateService.StatusView tv = novelAutoTranslateService.getStatus(novelId);
                if (tv != null) {
                    translatePhase = tv.phase();
                    translateElapsed = tv.elapsedSeconds();
                    translatePending = tv.seriesPending();
                }
            } catch (NumberFormatException ignored) {
                // 非数字 ID（不应出现于小说）：跳过叠加
            }
        }
        return new ScheduleQueueView.Item(
                it.getId(), it.getTitle(), it.getKind(),
                it.getXRestrict(), it.getAi(), it.getStatus(), it.getMessage(),
                translatePhase, translateElapsed, translatePending);
    }

    /** PHPSESSID 形如 {@code {userId}_{session}}：下划线前缀即非敏感 Pixiv userId。 */
    private static final Pattern PHPSESSID_PATTERN = Pattern.compile("PHPSESSID=([^;\\s]+)");

    /**
     * 为任务快照绑定 Cookie。校验含 {@code PHPSESSID} 后写入；cookie 绝不写日志 / 回显。
     *
     * <p>同时解析非敏感 {@code account_id}（PHPSESSID 下划线前缀 = Pixiv userId）写入；
     * 这是 {@code AUTH_EXPIRED} 的恢复入口——仅当任务当前为 {@code AUTH_EXPIRED} 时才清挂起 + 重算 next_run。
     * 处于 {@code OVERUSE_PAUSED} / 手动 {@code PAUSED} 的任务在重新授权后不会被静默恢复（必须走对应的恢复入口）。
     * 隔离表不再需要"武装"：{@link ScheduleExecutor#runTask} 每轮无条件先消费隔离表。
     *
     * <p><b>重新授权必须用「新的」Cookie</b>：提交的 Cookie 与当前已绑定的快照完全一致时直接拒绝、不做任何写入。
     * 这同时兜住「任务因 Cookie 失效（{@code AUTH_EXPIRED}）被挂起后，管理员误用同一份失效 Cookie 重新授权」——
     * 同一份 Cookie 不可能修复失效，若放行只会清掉挂起态、下一轮再次失效并重复发通知，白白空跑。
     * 由于在任何写库之前就抛出，故既不覆盖快照、也不清除 {@code AUTH_EXPIRED} 状态。
     */
    @Transactional
    public ScheduleTaskView authorizeCookie(long id, String cookie) {
        requireExisting(id);
        requireNotBusy(id);
        if (cookie == null || !cookie.contains("PHPSESSID")) {
            throw LocalizedException.badRequest(
                    "schedule.error.cookie-invalid", "Cookie 无效：缺少 PHPSESSID");
        }
        String trimmed = cookie.trim();
        String existing = database.mapper().findCookieSnapshot(id);
        if (existing != null && existing.equals(trimmed)) {
            throw LocalizedException.badRequest(
                    "schedule.error.cookie-unchanged",
                    "Cookie 与当前已绑定的相同，未做更新；若任务因 Cookie 失效被挂起，请改用新的有效 Cookie");
        }
        database.mapper().updateCookie(id, trimmed, ScheduledTask.COOKIE_BOUND);
        database.mapper().updateAccountId(id, parsePixivUserId(trimmed));
        ScheduledTask task = database.mapper().findById(id);
        database.mapper().clearSuspendIfStatus(
                id, nextRunFor(task), ScheduledTask.STATUS_AUTH_EXPIRED);
        return get(id);
    }

    /**
     * 从 Cookie 串解析非敏感 Pixiv userId（PHPSESSID 下划线前缀）。
     * 无 PHPSESSID / 无下划线前缀 / 前缀非数字 → 返回 {@code null}（不阻断授权）。
     */
    static String parsePixivUserId(String cookie) {
        if (cookie == null) return null;
        Matcher m = PHPSESSID_PATTERN.matcher(cookie);
        if (!m.find()) return null;
        String value = m.group(1);
        int underscore = value.indexOf('_');
        if (underscore <= 0) return null;
        String prefix = value.substring(0, underscore);
        return prefix.chars().allMatch(Character::isDigit) ? prefix : null;
    }

    /**
     * 解除 Cookie 授权：清空快照回到受限模式，并一并清除由 Cookie 派生的账号绑定
     * （{@code account_id} / {@code ack_warning_time}）——否则任务虽已转受限（匿名），仍会被同账号
     * 的过度访问冻结、横幅分组与账号级恢复误命中。详见
     * {@link top.sywyar.pixivdownload.schedule.db.ScheduledTaskMapper#clearCookieAndAccount}。
     */
    @Transactional
    public ScheduleTaskView revokeCookie(long id) {
        requireExisting(id);
        requireNotBusy(id);
        database.mapper().clearCookieAndAccount(id, ScheduledTask.COOKIE_RESTRICTED);
        return get(id);
    }

    /**
     * 设置 / 清除「任务级单独代理」（{@code host:port}，非凭证）。设置后该任务每轮运行中对 Pixiv 的全部
     * 出站请求（发现 / 元数据 / 下载 / 站内信检测）都改走它；{@code null} / 空白 = 清除并回退全局代理设置。
     */
    @Transactional
    public ScheduleTaskView updateProxy(long id, String proxy) {
        requireExisting(id);
        requireNotBusy(id);
        String normalized = emptyToNull(proxy);
        if (normalized != null && OutboundProxyOverride.parse(normalized) == null) {
            throw LocalizedException.badRequest(
                    "schedule.error.proxy-invalid", "代理格式无效，应为 host:port（例如 127.0.0.1:7890）");
        }
        database.mapper().updateProxy(id, normalized);
        return get(id);
    }

    /**
     * 「立即运行」端点入口：在 {@link #runOnce} 之上加状态门——任务必须 {@code enabled}、未在运行 / 排队、
     * 且未处于暂停 / 挂起态才允许手动触发。前端会据此禁用按钮，这里是后端防护（防陈旧 UI / 直连 API）。
     */
    public void manualRun(long id) {
        ScheduledTask task = database.mapper().findById(id);
        if (task == null) {
            throw LocalizedException.badRequest("schedule.error.not-found", "计划任务不存在: {0}", id);
        }
        requireNotBusy(id);
        if (!task.enabled()) {
            throw LocalizedException.badRequest("schedule.error.run-disabled", "任务已停用，请先启用再运行");
        }
        if (isSuspended(task)) {
            throw LocalizedException.badRequest(
                    "schedule.error.run-suspended", "任务处于暂停 / 挂起状态，请先恢复或重新授权再运行");
        }
        runOnce(id);
    }

    /**
     * 立即运行一次（后台异步执行，不阻塞调用方）。<b>不含状态门</b>：供 {@link #manualRun} 与
     * {@link ScheduleController#resume} 复用——前者已在外层校验状态，后者在 resume 事务提交、确认 enabled 后调用。
     * 已在运行 / 排队（有 Claim）时静默跳过，靠 next_run 兜底由 tick 接管。
     */
    public void runOnce(long id) {
        requireExisting(id);
        ScheduleRunState.Claim claim = runState.tryMarkQueued(id);
        if (claim == null) {
            log.debug("Scheduled task {} manual run ignored: already queued or running", id);
            return;
        }
        try {
            executor.runTaskAsync(id, claim);
        } catch (RuntimeException e) {
            runState.clear(claim);
            throw e;
        }
    }

    // ── 暂停 / 恢复 ───────────────────────────────────────────────────────────────

    /**
     * 手动暂停（任务级 PAUSED）：不冻账号、不发邮件；findDue 状态门挡住，不再到期触发。
     *
     * <p><b>仅在任务正在运行 / 排队（busy）时可暂停</b>：暂停语义是「打断当前这一轮」，空闲任务没有可打断的运行，
     * 应改用「停用」阻止自动调度。空闲时直接拒绝（前端也会禁用按钮，这里是后端防护）。
     *
     * <p>通过 {@link ScheduleRunState#requestCancel(long)} 给正在运行的本轮派发循环发协作式取消信号：
     * executor 在下一个作品派发前抛 {@link SchedulePauseException} 干净 unwind 本轮，
     * 这样「按下暂停立刻见效」，已下载的不回滚、未派发的不再继续，最终 {@code updateRunResult}
     * 由 CASE 保留 PAUSED 状态。
     */
    @Transactional
    public ScheduleTaskView pause(long id) {
        requireExisting(id);
        if (!isBusy(id)) {
            throw LocalizedException.badRequest(
                    "schedule.error.pause-idle", "任务当前未在运行，无需暂停；如需阻止自动运行请使用「停用」");
        }
        database.mapper().setStatus(id, ScheduledTask.STATUS_PAUSED);
        runState.requestCancel(id);
        return get(id);
    }

    /**
     * 恢复手动暂停 / 单任务挂起：清挂起，并把 {@code next_run_time} 置为<b>当前时刻</b>，使其立刻到期。
     *
     * <p>恢复语义是「立即继续这个任务」：调用方（{@link ScheduleController}）在本方法事务提交后会再触发一次
     * 后台运行（{@code runOnce}）真正立刻跑起来；这里把 {@code next_run_time=now} 作为兜底——即便那次即时触发
     * 因竞态被跳过，下一拍调度 tick 也会因「已到期」立即把它捡起来跑，绝不让恢复后白等一个完整周期。
     */
    @Transactional
    public ScheduleTaskView resume(long id) {
        ScheduledTask task = database.mapper().findById(id);
        if (task == null) {
            throw LocalizedException.badRequest("schedule.error.not-found", "计划任务不存在: {0}", id);
        }
        if (!ScheduledTask.STATUS_PAUSED.equals(task.lastStatus())) {
            throw LocalizedException.badRequest(
                    "schedule.error.resume-not-paused", "任务未处于手动暂停状态，无法恢复");
        }
        requireNotBusy(id);
        database.mapper().clearSuspend(id, System.currentTimeMillis());
        return get(id);
    }

    /**
     * 账号级（过度访问）恢复，对同账号所有任务生效（{@link AccountResumeRequest}）。
     */
    @Transactional
    public void resumeAccount(String accountId, AccountResumeRequest req) {
        List<ScheduledTask> tasks = database.mapper().findByAccountId(accountId);
        if (tasks.isEmpty()) {
            throw LocalizedException.badRequest(
                    "schedule.error.account-not-found", "账号下无计划任务: {0}", accountId);
        }
        boolean hasOverusePaused = tasks.stream()
                .anyMatch(t -> ScheduledTask.STATUS_OVERUSE_PAUSED.equals(t.lastStatus()));
        if (!hasOverusePaused) {
            // 账号级恢复仅针对过度访问暂停。AUTH_EXPIRED / 手动 PAUSED 必须各自走对应恢复入口，
            // 不能借账号级按钮一并清除（会越权放行管理员未确认的状态）。
            throw LocalizedException.badRequest(
                    "schedule.error.account-not-overuse-paused",
                    "账号下没有过度访问暂停的计划任务: {0}", accountId);
        }
        String mode = req.getMode() == null ? "" : req.getMode().trim();
        Long ackTime = latestOveruseWarning(tasks);
        long now = System.currentTimeMillis();
        if (AccountResumeRequest.MODE_IGNORE.equals(mode)) {
            if (ackTime != null) {
                database.mapper().updateAckWarning(accountId, ackTime);
            }
            database.mapper().clearSuspendForAccount(accountId, now);
        } else if (AccountResumeRequest.MODE_DEFER.equals(mode)) {
            int minutes = req.getMinutes() == null
                    ? config.getOveruseDeferDefaultMinutes() : req.getMinutes();
            if (minutes < 60) {
                throw LocalizedException.badRequest(
                        "schedule.error.defer-minutes-min", "延迟分钟数最低为 60");
            }
            if (ackTime != null) {
                database.mapper().updateAckWarning(accountId, ackTime); // 保险
            }
            database.mapper().clearSuspendForAccount(accountId, now + minutes * 60_000L);
        } else {
            throw LocalizedException.badRequest("schedule.error.resume-mode-invalid", "恢复方式无效");
        }
    }

    /** 隔离表（待重试）行视图，供前端「待重试 / 需人工」面板展示。 */
    public List<SchedulePendingView> pending(long id) {
        requireExisting(id);
        int max = config.getPendingMaxAttempts();
        return database.mapper().listPending(id).stream()
                .map(p -> SchedulePendingView.of(p, max))
                .toList();
    }

    /** 手动清除隔离表中某个「需人工」条目（运行 / 排队中拒绝，避免与本轮的隔离表读写竞态）。 */
    @Transactional
    public void clearPending(long id, long workId) {
        requireExisting(id);
        requireNotBusy(id);
        database.mapper().deletePending(id, workId);
    }

    /** 取同账号 OVERUSE_PAUSED 任务里 last_message 记录的最新触发警告 modifiedAt（毫秒）。 */
    private static Long latestOveruseWarning(List<ScheduledTask> tasks) {
        Long best = null;
        for (ScheduledTask t : tasks) {
            if (!ScheduledTask.STATUS_OVERUSE_PAUSED.equals(t.lastStatus())) continue;
            String msg = t.lastMessage();
            if (msg == null || msg.isBlank()) continue;
            try {
                long v = Long.parseLong(msg.trim());
                if (best == null || v > best) best = v;
            } catch (NumberFormatException ignored) {
                // last_message 非纯数字（异常路径）：忽略
            }
        }
        return best;
    }

    private static Long nextRunFor(ScheduledTask task) {
        return ScheduleTiming.computeNextRun(
                task.triggerKind(), task.intervalMinutes(), task.cronExpr(), System.currentTimeMillis());
    }

    // ── 内部 ────────────────────────────────────────────────────────────────────

    private void requireExisting(long id) {
        if (database.mapper().findById(id) == null) {
            throw LocalizedException.badRequest("schedule.error.not-found", "计划任务不存在: {0}", id);
        }
    }

    /** 任务是否正处于瞬时运行态（QUEUED / RUNNING）。结构性操作在此期间应拒绝。 */
    private boolean isBusy(long id) {
        return runState.get(id) != null;
    }

    /** 运行 / 排队中拒绝结构性操作（编辑 / 启停 / 删除 / 授权 / 解绑 / 清待重试 / 恢复等）。 */
    private void requireNotBusy(long id) {
        if (isBusy(id)) {
            throw LocalizedException.badRequest(
                    "schedule.error.busy", "任务正在运行或排队中，请等待本轮结束后再操作");
        }
    }

    /** 是否处于暂停 / 挂起态（手动暂停 / 过度访问 / cookie 失效）。 */
    private static boolean isSuspended(ScheduledTask t) {
        String s = t.lastStatus();
        return ScheduledTask.STATUS_PAUSED.equals(s)
                || ScheduledTask.STATUS_OVERUSE_PAUSED.equals(s)
                || ScheduledTask.STATUS_AUTH_EXPIRED.equals(s);
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
