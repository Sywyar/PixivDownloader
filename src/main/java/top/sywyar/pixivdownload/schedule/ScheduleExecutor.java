package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.i18n.AppLocale;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.notification.NotificationService;
import top.sywyar.pixivdownload.novel.NovelDownloadService;
import top.sywyar.pixivdownload.novel.NovelDownloader;
import top.sywyar.pixivdownload.novel.NovelMergeService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.push.MarkdownEscape;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskDatabase;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskPending;
import top.sywyar.pixivdownload.setup.SetupService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

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
 *   <li><b>单作品异常分类</b>：404/403-gone 跳过不挡 watermark；可恢复 {@link PixivFetchService.PixivFetchException}
 *       记隔离表 + 连续计数，连续 M（{@code schedule.auth-failure-circuit-breaker}）次熔断挂起。</li>
 *   <li><b>watermark</b>：reached 边界且无挂起条件即推进（哪怕本轮有作品进隔离表）；挂起异常上抛时绝不推进。</li>
 * </ul>
 * 自动挂起均发通知（邮件 + 推送并行，经 {@link NotificationService}，best-effort）；手动 {@code PAUSED} 不发。
 *
 * <p><b>作品级并发</b>：任务间本就串行（唯一 {@code @Scheduled} tick + 单飞），故一个任务内借用
 * 下载线程池（插画走 {@code downloadTaskExecutor}、小说走 {@code novelDownloadTaskExecutor}，与交互式
 * web 下载共享）做作品级并发。发现 / 翻页 / 水位线边界判定 / 去重 / 取元数据 / 服务端筛选 / 解析图片 URL /
 * 作品间礼貌延迟 / 过度访问轮内 N 检查全在调度主线程<b>串行</b>执行（保证 watermark 按新→旧判边界、限速安全）；
 * <b>仅</b>把阻塞下载提交到线程池，用 {@link Semaphore}（有效并发数 = {@code min(任务并发数, 对应池大小)}）限流：
 * 主线程提交前 {@code acquire}、异步任务 {@code finally} 里 {@code release}。novel 合订 / {@code updateWatermark} /
 * {@code runTask} 返回前等本轮所有在途下载完成（join）。「连续失败」熔断计数在并发下退化为「按完成顺序的连续」（可接受）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleExecutor {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_AUTH_EXPIRED = ScheduledTask.STATUS_AUTH_EXPIRED;
    public static final String STATUS_OVERUSE_PAUSED = ScheduledTask.STATUS_OVERUSE_PAUSED;
    public static final String STATUS_ERROR = "ERROR";

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private static final String KIND_NOVEL = "novel";

    private final ScheduledTaskDatabase database;
    private final PixivFetchService pixivFetchService;
    private final PixivDatabase pixivDatabase;
    private final ArtworkDownloader artworkDownloader;
    private final NovelDownloader novelDownloader;
    private final NovelDatabase novelDatabase;
    private final NovelMergeService novelMergeService;
    private final ScheduleConfig scheduleConfig;
    private final ScheduleRunState runState;
    private final ScheduleRunQueue runQueue;
    private final ObjectMapper objectMapper;
    private final OveruseWarningService overuseWarningService;
    private final NotificationService notificationService;
    private final AppMessages messages;
    private final SetupService setupService;
    private final DownloadConfig downloadConfig;
    // 字段名与 bean 名一致，借此按名解析到对应下载池（避免 @Primary 的 applicationTaskExecutor）。
    private final TaskExecutor downloadTaskExecutor;
    private final TaskExecutor novelDownloadTaskExecutor;

    /** {@code last_message} 失败原因摘要的最大长度（截断防止超长异常文本撑爆列）。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;
    /** 过度访问通知里逐条列出受影响任务的最大条数，超出附「等共 N 个」。 */
    private static final int TASK_LIST_LIMIT = 15;
    private static final Pattern COOKIE_HEADER_PATTERN = Pattern.compile("(?i)\\b(cookie\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern PHPSESSID_PATTERN = Pattern.compile("(?i)\\b(PHPSESSID\\s*=\\s*)[^;\\s&]+");
    // 涵盖 cookie 串前缀（^ / ; / 空白）以及 URL 查询串前缀（? / &），后者用于 `...?PHPSESSID=...` / `&PHPSESSID=...` 形式。
    private static final Pattern COOKIE_PAIR_PATTERN = Pattern.compile("(?i)(^|[;\\s?&])([A-Za-z0-9_-]+\\s*=\\s*)[^;\\s&]+");

    /** 后台异步运行一次（供「立即运行」端点用，避免阻塞 HTTP 请求线程）。 */
    @Async
    public void runTaskAsync(long taskId) {
        ScheduleRunState.Claim claim = runState.tryMarkQueued(taskId);
        if (claim == null) {
            log.debug("Scheduled task {} async run skipped: already queued or running", taskId);
            return;
        }
        runTaskAsync(taskId, claim);
    }

    /** 后台异步运行一个已经抢占瞬时态的任务。 */
    @Async
    public void runTaskAsync(long taskId, ScheduleRunState.Claim claim) {
        boolean delegated = false;
        try {
            ScheduledTask task = database.mapper().findById(taskId);
            if (task != null) {
                delegated = true;
                runTaskAndRecord(task, claim);
            }
        } finally {
            if (!delegated) {
                runState.clear(claim);
            }
        }
    }

    /**
     * 同步运行一个任务并把结果写回（last_run_time / last_status / next_run_time）。
     * 调度 tick 串行调用本方法；固定周期的下一次运行以本轮真实完成时间为基准。
     */
    public void runTaskAndRecord(ScheduledTask task) {
        ScheduleRunState.Claim claim = runState.tryMarkRunning(task.id());
        if (claim == null) {
            log.debug("Scheduled task {} ({}) skipped: already queued or running", task.id(), task.name());
            return;
        }
        runTaskAndRecord(task, claim);
    }

    void runTaskAndRecord(ScheduledTask task, ScheduleRunState.Claim claim) {
        if (!runState.markRunning(claim)) {
            log.debug("Scheduled task {} ({}) skipped: stale run claim", task.id(), task.name());
            return;
        }
        String status;
        String message = null;
        ScheduleSuspendException suspendNotification = null;
        long suspendTriggerTime = 0L;
        // 本轮是否因 cookie 失效但任务无需 cookie 而自动降级（runTask 内已清失效快照 + 转匿名续跑）；运行成功后据此发一次降级通知。
        boolean[] degraded = {false};
        int completedCount = 0;
        // 仅当 last_status 由「非 ERROR」转入 ERROR 时才发失败通知（连续失败不重复打扰）；进入 catch 前先读旧状态。
        boolean notifyRunFailed = false;
        List<PendingExhaustedNotification> pendingNotifications =
                Collections.synchronizedList(new ArrayList<>());
        try {
            // 落库本轮开始时刻：正常结束（含干净挂起）时 updateRunResult 会清为 NULL；进程被强杀则残留 → 中断红灯。
            database.mapper().updateRunStarted(task.id(), System.currentTimeMillis());
            completedCount = runTask(task, pendingNotifications, degraded);
            status = STATUS_OK;
            log.info("Scheduled task {} ({}) completed {} new download(s)", task.id(), task.name(), completedCount);
        } catch (OveruseWarningException e) {
            // 过度访问警告：账号级暂停 + 冻结同账号 + 通知；干净挂起（清 run_started_time）。
            status = STATUS_OVERUSE_PAUSED;
            message = String.valueOf(e.modifiedAt()); // 触发警告 modifiedAt：供卡片展示 + 账号级 ack 取用
            handleOveruse(task, e);
            log.warn("Scheduled task {} ({}) paused: overuse warning", task.id(), task.name());
        } catch (ScheduleSuspendException e) {
            // cookie 依赖型 dead cookie / 单作品连续失败熔断：任务级挂起 + 通知。
            status = STATUS_AUTH_EXPIRED;
            suspendNotification = e;
            suspendTriggerTime = System.currentTimeMillis();
            log.warn("Scheduled task {} ({}) suspended: {}", task.id(), task.name(), e.reason());
        } catch (SchedulePauseException e) {
            // 用户在本轮派发循环中按了「暂停」：DB 已被 ScheduleService.pause 写成 PAUSED，
            // 此处仅记日志、不冻账号、不发邮件；下面的 updateRunResult 走 CASE 保留 PAUSED 状态。
            // 已派发的下载继续在 @Async 池中跑完（取消点位于下个作品派发前，不打断进行中的下载），
            // 未派发的作品本轮不再继续。
            status = ScheduledTask.STATUS_PAUSED;
            log.info("Scheduled task {} ({}) paused mid-run by user", task.id(), task.name());
        } catch (PixivFetchService.PixivFetchException e) {
            // 发现阶段鉴权失效（轮首/翻页）：不写 cookie 到日志，挂起并发通知等管理员重授权。
            status = STATUS_AUTH_EXPIRED;
            suspendNotification = new ScheduleSuspendException(ScheduleSuspendException.Reason.COOKIE_DEAD);
            suspendTriggerTime = System.currentTimeMillis();
            log.warn("Scheduled task {} ({}) auth expired, awaiting re-authorization", task.id(), task.name());
        } catch (Exception e) {
            status = STATUS_ERROR;
            message = summarizeError(e);
            notifyRunFailed = !STATUS_ERROR.equals(task.lastStatus());
            log.error("Scheduled task {} ({}) failed [{}]: {}",
                    task.id(), task.name(), e.getClass().getSimpleName(), message);
        } finally {
            runState.clear(claim);
        }
        long completedAt = System.currentTimeMillis();
        Long nextRun = ScheduleTiming.computeNextRun(
                task.triggerKind(), task.intervalMinutes(), task.cronExpr(), completedAt);
        // 挂起态写未来 next_run 仅供展示；findDue 的 last_status 门控会挡住它，不会立即重跑。
        database.mapper().updateRunResult(task.id(), completedAt, status, message, nextRun);
        Long notificationNextRun = persistedNextRun(task.id(), nextRun);
        if (suspendNotification != null) {
            handleSuspend(task, suspendNotification, suspendTriggerTime);
        }
        sendPendingExhaustedNotifications(task, pendingNotifications, notificationNextRun);
        // ── 运行结束通知（best-effort，不影响调度）：成功时按「是否自动降级 / 是否有新下载」二选一，失败时按「转入 ERROR」发一次。──
        if (STATUS_OK.equals(status)) {
            if (degraded[0]) {
                notifyDegradedAnonymous(task, completedCount, completedAt, notificationNextRun);
            } else if (completedCount > 0) {
                notifyRunSummary(task, completedCount, completedAt, notificationNextRun);
            }
        } else if (notifyRunFailed) {
            notifyRunFailure(task, message, completedAt, notificationNextRun);
        }
    }

    /**
     * 把异常压缩成可安全展示的失败原因摘要：取 {@code getMessage()}（缺失时退化为异常简单类名），
     * 折叠空白、显式脱敏 Cookie 凭证，并截断到 {@link #MAX_ERROR_MESSAGE_LENGTH}。
     */
    private static String summarizeError(Throwable e) {
        String raw = e.getMessage();
        if (raw == null || raw.isBlank()) {
            raw = e.getClass().getSimpleName();
        }
        String collapsed = redactCookies(raw.replaceAll("\\s+", " ").trim());
        if (collapsed.length() > MAX_ERROR_MESSAGE_LENGTH) {
            collapsed = collapsed.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "…";
        }
        return collapsed;
    }

    /** 更新运行结果后读取数据库中的真实 next_run_time；若读取失败则回退到本轮刚计算出的值。 */
    private Long persistedNextRun(long taskId, Long fallback) {
        try {
            ScheduledTask refreshed = database.mapper().findById(taskId);
            return refreshed == null ? fallback : refreshed.nextRunTime();
        } catch (RuntimeException e) {
            log.debug(messages.getForLog("schedule.log.next-run.reload-failed", taskId, e.getMessage()));
            return fallback;
        }
    }

    /**
     * 单作品隔离表 {@code reason} 列入库专用：直接取 {@code e.getMessage()}（缺失退化为异常简单类名），
     * 仅做 cookie 脱敏（项目红线，绝不入库），不折叠空白、不截断长度。
     * 与 {@link #summarizeError} 区别：那是顶层任务级 {@code last_message} 的展示摘要，会做大量整形并截到 300。
     */
    private static String pendingReason(Throwable e) {
        String raw = e.getMessage();
        if (raw == null || raw.isBlank()) {
            raw = e.getClass().getSimpleName();
        }
        return redactCookies(raw);
    }

    /**
     * 脱敏文本中的 cookie / PHPSESSID。<b>无条件</b>先后应用：
     * <ol>
     *   <li>{@code Cookie:} / {@code Cookie=} 整段头；</li>
     *   <li>独立 {@code PHPSESSID=value}（含 URL 查询串里的 {@code ?PHPSESSID=} / {@code &PHPSESSID=}）；</li>
     *   <li>cookie 串里的所有 {@code key=value} 对（含 URL 查询串前缀）。</li>
     * </ol>
     * 两个值-级 pattern 不再二选一——某些异常文案只命中其中一个（典型如 URL 形式的 {@code ?PHPSESSID=...}
     * 不命中早期 {@code COOKIE_PAIR_PATTERN}）。同时跑两遍是幂等的：前一遍把 PHPSESSID 值换成 {@code [redacted]}
     * 之后，后一遍仍可按 {@code key=...} 形态把其他配对正确清空。
     */
    static String redactCookies(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = COOKIE_HEADER_PATTERN.matcher(text).replaceAll("$1[redacted]");
        out = PHPSESSID_PATTERN.matcher(out).replaceAll("$1[redacted]");
        out = COOKIE_PAIR_PATTERN.matcher(out).replaceAll("$1$2[redacted]");
        return out;
    }

    /**
     * 发现 + 过滤 + 同步下载，返回实际完成的新下载数。
     *
     * @throws OveruseWarningException 检测到过度访问警告（轮首 / 轮内 N 检查点）
     * @throws ScheduleSuspendException cookie 依赖型 dead cookie / 单作品连续失败熔断
     * @throws PixivFetchService.PixivFetchException 发现阶段鉴权失效
     */
    private int runTask(ScheduledTask task, List<PendingExhaustedNotification> pendingNotifications,
                        boolean[] degraded) throws Exception {
        String cookie = ScheduledTask.COOKIE_BOUND.equals(task.cookieMode())
                ? database.mapper().findCookieSnapshot(task.id())
                : null;

        JsonNode root = objectMapper.readTree(task.paramsJson() == null ? "{}" : task.paramsJson());
        boolean novel = KIND_NOVEL.equalsIgnoreCase(root.path("kind").asText("illust"));
        JsonNode source = root.path("source");
        Filters filters = parseFilters(root.path("filters"));
        Download download = parseDownload(root.path("download"));
        // 抓取上限（0 = 不限 / 全量）。上限按进入本轮运行队列的作品数计算；语义随来源是否「ID 单调可水位线」二分：
        //   · 水位线类（USER_NEW / FOLLOW_LATEST / date_d 翻页到底 SEARCH）：仅<b>首轮</b>（watermark 未建立）封顶，
        //     随后水位线推进到最新 ID、更老积压永久跳过；
        //   · 非水位线类（MY_BOOKMARKS / COLLECTION / 非 date_d 翻页到底 SEARCH）：作为<b>每轮上限</b>逐轮抽干积压。
        //   · SERIES / 固定页 SEARCH 不应用（前端也隐藏该字段）。
        int fetchLimit = Math.max(0, root.path("fetchLimit").asInt(0));

        // ── 轮首检查点：cookie-bound 任务读站内信（过度访问判定 + cookie 存活探测）──────────────
        if (ScheduledTask.COOKIE_BOUND.equals(task.cookieMode())) {
            OveruseWarningService.Result result =
                    overuseWarningService.check(cookie, task.ackWarningTime(), System.currentTimeMillis());
            if (result.isCookieDead()) {
                if (isCookieDependent(root) || isAccountScopedType(task.type())) {
                    // 账号私有来源（收藏 / 关注新作 / 珍藏集）无法匿名续跑，dead cookie 一律挂起。
                    throw new ScheduleSuspendException(ScheduleSuspendException.Reason.COOKIE_DEAD);
                }
                // 非依赖型：失效 Cookie 已无价值，自动清除快照转受限模式——避免每轮再用死 cookie 探测站内信、
                // 也避免每轮重复发降级通知；本轮降级匿名续跑（全年龄作品照样抓全、不丢、不浪费），运行成功后发一次降级通知。
                database.mapper().updateCookie(task.id(), null, ScheduledTask.COOKIE_RESTRICTED);
                cookie = null;
                degraded[0] = true;
            } else if (result.isWarned()) {
                throw new OveruseWarningException(result.modifiedAt(), result.excerpt());
            }
        }

        // COLLECTION 是插画+小说混合来源，分两遍各自走对应下载管线，单独处理。
        if (task.type() == ScheduledTaskType.COLLECTION) {
            return runCollectionTask(task, source, cookie, filters, download, fetchLimit, pendingNotifications);
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
                pendingNotifications);

        try {
            // ── 每轮无条件先消费隔离表（不再依赖 pendingRetryArmed 武装位）：走正常 dispatch 路径，
            //    计入 N 检查点与过度访问风险；单作品 attempts 自动 +1，达到上限时发通知转人工。
            //    若作品在隔离期间已被其它路径（手动 / 别的任务）下载，先清隔离表条目、跳过重试。──
            retryPending(task.id(), runner, politeDelay, alreadyDownloaded);

            if (isWatermarkMode(task.type(), source)) {
                runWatermarkMode(task, novel, source, cookie, runner, run, alreadyDownloaded, politeDelay, fetchLimit);
            } else if (isDownloadedBoundarySearchMode(task.type(), source)) {
                runDownloadedBoundarySearch(novel, source, cookie, runner, run, alreadyDownloaded, politeDelay, fetchLimit);
            } else {
                // 全量发现 + 跳过已下载（SEARCH 固定页 / SERIES / MY_BOOKMARKS）。
                // 仅 MY_BOOKMARKS 应用「每轮上限」（收藏顺序非单调、无水位线，故逐轮各抓 fetchLimit 个队列项抽干积压）；
                // SERIES / 固定页 SEARCH 不封顶（前端也隐藏该字段，此处即便误带也不生效）。
                boolean cap = task.type() == ScheduledTaskType.MY_BOOKMARKS && fetchLimit > 0;
                List<String> ids = discoverIds(task.type(), novel, source, cookie);
                if (!cap) {
                    ids.forEach(run::discovered);
                }
                int queued = 0;
                for (String id : ids) {
                    long workId;
                    try {
                        workId = Long.parseLong(id);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    // 封顶路径在循环内逐个登记发现（越过上限的作品不入本轮运行队列、留待下一轮）。
                    boolean reachedCap = false;
                    if (cap) {
                        run.discovered(id);
                        reachedCap = ++queued >= fetchLimit;
                    }
                    if (alreadyDownloaded.test(workId)) {
                        run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
                        if (reachedCap) {
                            break;
                        }
                        continue;
                    }
                    runner.process(id, workId, false);
                    politeDelay.run();
                    if (reachedCap) {
                        break;
                    }
                }
            }
        } finally {
            // 等本轮所有在途下载完成：保证挂起 / 异常 unwind 时已派发的下载跑完、失败者已入隔离表，
            // 终态标记与「watermark 不推进」一致（updateWatermark 已在 runWatermarkMode 内 join 后才执行）。
            runner.awaitAll();
        }

        int completed = runner.completed();

        // 小说系列合订：best-effort、幂等；本轮有新章节时立即合订（已在上面 join，章节均已落库）。
        if (novel && task.type() == ScheduledTaskType.SERIES && download.novelMerge() && completed > 0) {
            long seriesId = source.path("seriesId").asLong(0);
            if (seriesId > 0) {
                try {
                    novelMergeService.merge(seriesId,
                            NovelDownloadService.NovelFormat.parse(download.novelMergeFormat()));
                } catch (Exception e) {
                    log.warn("Scheduled task {} series merge failed [{}]: {}",
                            task.id(), e.getClass().getSimpleName(), summarizeError(e));
                }
            }
        }
        return completed;
    }

    /**
     * 重试隔离表中尚未达到上限的作品；成功 DELETE、失败 incPendingAttempts，全经正常分类路径。
     * 若作品在隔离期间已被其它路径（手动 / 别的任务）下载，直接清隔离表条目并跳过 dispatch，
     * 避免重复下载或在已成功后仍累计失败计数。
     */
    private void retryPending(long taskId, WorkRunner runner, Runnable politeDelay,
                              LongPredicate alreadyDownloaded)
            throws OveruseWarningException, ScheduleSuspendException, SchedulePauseException {
        int max = scheduleConfig.getPendingMaxAttempts();
        for (ScheduledTaskPending p : database.mapper().listPending(taskId)) {
            if (p.attempts() >= max) {
                continue; // 需人工，停止自动重试
            }
            String id = String.valueOf(p.workId());
            if (alreadyDownloaded.test(p.workId())) {
                // 已在别处下载完：清隔离条目、本轮不再 dispatch；不入运行队列展示，避免和正常发现的「已下载跳过」混淆。
                database.mapper().deletePending(taskId, p.workId());
                continue;
            }
            runner.run().discovered(id);
            runner.process(id, p.workId(), true);
            politeDelay.run();
        }
    }

    private List<String> discoverIds(ScheduledTaskType type, boolean novel, JsonNode source, String cookie)
            throws Exception {
        return switch (type) {
            case USER_NEW -> {
                String userId = source.path("userId").asText("");
                yield novel ? pixivFetchService.discoverUserNovelIds(userId, cookie)
                            : pixivFetchService.discoverUserArtworkIds(userId, cookie);
            }
            case SEARCH -> {
                String word = source.path("word").asText("");
                String order = source.path("order").asText("date_d");
                String mode = source.path("mode").asText("all");
                String sMode = source.path("sMode").asText("s_tag");
                int maxPages = source.path("maxPages").asInt(3);
                yield novel ? pixivFetchService.discoverSearchNovelIds(word, order, mode, sMode, maxPages, cookie)
                            : pixivFetchService.discoverSearchArtworkIds(word, order, mode, sMode, maxPages, cookie);
            }
            case SERIES -> {
                String seriesId = source.path("seriesId").asText("");
                yield novel ? pixivFetchService.discoverNovelSeriesIds(seriesId, cookie)
                            : pixivFetchService.discoverSeriesArtworkIds(seriesId, cookie);
            }
            case MY_BOOKMARKS -> {
                String rest = source.path("rest").asText("show");
                yield novel ? pixivFetchService.discoverMyNovelBookmarkIds(rest, cookie)
                            : pixivFetchService.discoverMyIllustBookmarkIds(rest, cookie);
            }
            case FOLLOW_LATEST -> pixivFetchService.discoverFollowLatestIllustIds(cookie);
            // COLLECTION 是混合（插画+小说）来源，由 runCollectionTask 单独处理，不经此单 kind 入口。
            case COLLECTION -> throw new IllegalStateException("COLLECTION handled by runCollectionTask");
        };
    }

    /**
     * 「最新在前 + 只追加 + ID 单调」的来源走 ID 水位线增量发现：
     * <ul>
     *   <li>USER_NEW —— 单画师全量发现（ID 降序）；</li>
     *   <li>FOLLOW_LATEST —— 已关注用户的新作 feed（按发布时间倒序 ≈ ID 降序，仅头部追加）；</li>
     *   <li>SEARCH —— 仅 {@code date_d + maxPages==-1} 的增量搜索（按时间倒序、逐页翻到追平历史）。</li>
     * </ul>
     */
    static boolean isWatermarkMode(ScheduledTaskType type, JsonNode source) {
        if (type == ScheduledTaskType.USER_NEW) return true;
        if (type == ScheduledTaskType.FOLLOW_LATEST) return true;
        return type == ScheduledTaskType.SEARCH
                && source.path("maxPages").asInt(3) == -1
                && "date_d".equals(source.path("order").asText("date_d"));
    }

    private static boolean isDownloadedBoundarySearchMode(ScheduledTaskType type, JsonNode source) {
        return type == ScheduledTaskType.SEARCH
                && source.path("maxPages").asInt(3) == -1
                && !"date_d".equals(source.path("order").asText("date_d"));
    }

    /**
     * 判定任务是否「cookie 依赖型」（满足任一）：
     * <ul>
     *   <li>{@code filters.content != "safe"}（注意 {@code "all"} 也算——匿名看不到 R-18，发现阶段静默缩水，
     *       watermark 会越过当时不可见的 R-18 → 永久遗漏）；</li>
     *   <li>{@code source.mode == "r18"}；</li>
     *   <li>{@code download.bookmark == true}（收藏必须登录）。</li>
     * </ul>
     * 这类任务遇 dead bound cookie 必须立即挂起、绝不进入发现阶段。
     */
    static boolean isCookieDependent(JsonNode root) {
        String content = root.path("filters").path("content").asText("safe");
        if (!"safe".equals(content)) {
            return true;
        }
        if ("r18".equals(root.path("source").path("mode").asText(""))) {
            return true;
        }
        return root.path("download").path("bookmark").asBoolean(false);
    }

    /**
     * 账号私有来源类型：收藏 / 已关注用户的新作 / 珍藏集。这类发现接口必须用账号 cookie，
     * 无法匿名续跑，因此 dead / 缺失 cookie 时一律挂起 {@code AUTH_EXPIRED}（与 {@link #isCookieDependent} 合并判定）。
     */
    static boolean isAccountScopedType(ScheduledTaskType type) {
        return type == ScheduledTaskType.MY_BOOKMARKS
                || type == ScheduledTaskType.FOLLOW_LATEST
                || type == ScheduledTaskType.COLLECTION;
    }

    /**
     * 珍藏集（插画+小说混合）专用：发现成员后分两遍各自走对应下载管线（插画用 {@code downloadTaskExecutor}、
     * 小说用 {@code novelDownloadTaskExecutor}），完成数相加。两遍共用同一轮运行队列（按成员自身 kind 登记）。
     * 隔离表重试按「成员是否仍在珍藏集内」的成员关系判定：仍在则本轮以 retry 语义重派，已被移出的孤儿条目本轮跳过。
     */
    private int runCollectionTask(ScheduledTask task, JsonNode source, String cookie,
                                  Filters filters, Download download, int fetchLimit,
                                  List<PendingExhaustedNotification> pendingNotifications) throws Exception {
        String collectionId = source.path("collectionId").asText("");
        PixivFetchService.CollectionWorkIds ids = pixivFetchService.discoverCollectionWorkIds(collectionId, cookie);

        ScheduleRunQueue.Run run = runQueue.begin(task.id(), ScheduleRunQueue.KIND_ILLUST);
        Runnable politeDelay = politeDelayFor(download);

        // 隔离表中尚未达上限的待重试成员（混合来源不记 kind，靠本轮发现的成员关系区分插画/小说）。
        int max = scheduleConfig.getPendingMaxAttempts();
        Set<Long> pending = new HashSet<>();
        for (ScheduledTaskPending p : database.mapper().listPending(task.id())) {
            if (p.attempts() < max) pending.add(p.workId());
        }

        // 珍藏集无水位线、ID 非单调 → 每轮上限：两遍（插画 + 小说）共享同一预算，本轮最多登记 fetchLimit 个新队列项；
        // 隔离表重试不计入预算（属既有积压的恢复，且数量受上限约束）。-1 = 不限。
        int[] budget = {fetchLimit > 0 ? fetchLimit : -1};

        int completed = 0;
        completed += runCollectionPass(task, false, cookie, filters, download, run, ids.illustIds(), pending,
                politeDelay, budget, pendingNotifications);
        completed += runCollectionPass(task, true, cookie, filters, download, run, ids.novelIds(), pending,
                politeDelay, budget, pendingNotifications);
        return completed;
    }

    /**
     * 珍藏集单 kind 一遍：跳过已下载 → 派发（命中隔离表则按 retry 语义），结束前 join 本遍在途下载。
     * {@code budget[0]} 为两遍共享的每轮新队列项预算（{@code -1} 不限，{@code 0} 已耗尽）：预算耗尽即跳过剩余新作、
     * 不入本轮运行队列（留待下一轮）；重试不受预算约束。
     */
    private int runCollectionPass(ScheduledTask task, boolean novel, String cookie, Filters filters,
                                  Download download, ScheduleRunQueue.Run run, List<String> ids,
                                  Set<Long> pending, Runnable politeDelay, int[] budget,
                                  List<PendingExhaustedNotification> pendingNotifications) throws Exception {
        LongPredicate alreadyDownloaded = alreadyDownloadedPredicate(novel, download);
        TaskExecutor pool = novel ? novelDownloadTaskExecutor : downloadTaskExecutor;
        int concurrency = effectiveConcurrency(novel, download.concurrent());
        WorkRunner runner = new WorkRunner(task, novel, cookie, filters, download, run, concurrency, pool,
                pendingNotifications);
        String itemKind = novel ? ScheduleRunQueue.KIND_NOVEL : ScheduleRunQueue.KIND_ILLUST;
        try {
            for (String id : ids) {
                long workId;
                try {
                    workId = Long.parseLong(id);
                } catch (NumberFormatException e) {
                    continue;
                }
                boolean isRetry = pending.contains(workId);
                // 每轮上限：队列项预算耗尽即静默跳过（不登记发现、不入运行队列，下一轮再处理）；重试不受限。
                if (!isRetry && budget[0] == 0) {
                    continue;
                }
                // 重试条目若已经在别处被下载（手动 / 别的任务）：清隔离条目、跳过 dispatch，避免重复下载。
                if (isRetry && alreadyDownloaded.test(workId)) {
                    database.mapper().deletePending(task.id(), workId);
                    pending.remove(workId);
                    continue;
                }
                run.discovered(id, itemKind);
                if (!isRetry && alreadyDownloaded.test(workId)) {
                    run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
                    if (budget[0] > 0) {
                        budget[0]--;
                    }
                    continue;
                }
                if (!isRetry && budget[0] > 0) {
                    budget[0]--;
                }
                runner.process(id, workId, isRetry);
                politeDelay.run();
            }
        } finally {
            runner.awaitAll();
        }
        return runner.completed();
    }

    private void runWatermarkMode(ScheduledTask task, boolean novel, JsonNode source, String cookie,
                                  WorkRunner runner, ScheduleRunQueue.Run run,
                                  LongPredicate alreadyDownloaded, Runnable politeDelay,
                                  int fetchLimit) throws Exception {
        PageSupplier pages;
        Runnable pageDelay;
        if (task.type() == ScheduledTaskType.USER_NEW) {
            String userId = source.path("userId").asText("");
            List<String> all = novel ? pixivFetchService.discoverUserNovelIds(userId, cookie)
                                     : pixivFetchService.discoverUserArtworkIds(userId, cookie);
            pages = p -> p == 1 ? all : List.of();
            pageDelay = () -> {}; // 单次全量发现，无翻页
        } else if (task.type() == ScheduledTaskType.FOLLOW_LATEST) {
            pages = followLatestPages(cookie);
            pageDelay = () -> {}; // 与既有 follow_latest 全量发现一致，页间不强制延迟
        } else {
            pages = searchPages(novel, source, cookie);
            pageDelay = this::watermarkPageDelay; // SEARCH 翻页前强制 10s 礼貌延迟
        }
        WorkDispatcher dispatcher = (id, workId) -> runner.process(id, workId, false);
        long watermark = task.watermarkId() == null ? 0L : task.watermarkId();
        // 仅首轮（水位线未建立）封顶：入队数达到上限即停，newestSeen 已记到最新 ID、水位线照常推进到最新，
        // 更老的积压会被永久跳过（这正是「只要最新 N 个、之后只追新」的预期语义）。
        int queueLimit = (watermark == 0 && fetchLimit > 0) ? fetchLimit : 0;
        WatermarkScanResult result = runWatermarkScan(
                pages, watermark, alreadyDownloaded, dispatcher, politeDelay, pageDelay, run, queueLimit);
        // 扫描正常返回（无挂起异常）后，先等本轮在途下载完成再推进水位线：确保失败者已入隔离表、
        // watermark 推进不会越过尚未真正落盘的作品（崩溃抗空洞）。挂起异常上抛时本方法不会执行到这里。
        runner.awaitAll();
        // reached 边界且无挂起条件即推进（哪怕本轮有作品进隔离表）。
        if (result.newestSeen() > 0) {
            database.mapper().updateWatermark(task.id(), result.newestSeen());
        }
    }

    private void runDownloadedBoundarySearch(boolean novel, JsonNode source, String cookie,
                                             WorkRunner runner, ScheduleRunQueue.Run run,
                                             LongPredicate alreadyDownloaded, Runnable politeDelay,
                                             int fetchLimit) throws Exception {
        PageSupplier pages = searchPages(novel, source, cookie);
        WorkDispatcher dispatcher = (id, workId) -> runner.process(id, workId, false);
        // 非 date_d 的「翻页到底」没有可靠的 ID 水位线 → 封顶按「每轮上限」语义（每轮最多登记 fetchLimit 个队列项）。
        int queueLimit = fetchLimit > 0 ? fetchLimit : 0;
        runDownloadedBoundaryScan(
                pages, alreadyDownloaded, dispatcher, politeDelay, this::watermarkPageDelay, run, queueLimit);
    }

    private PageSupplier searchPages(boolean novel, JsonNode source, String cookie) {
        String word = source.path("word").asText("");
        String order = source.path("order").asText("date_d");
        String mode = source.path("mode").asText("all");
        String sMode = source.path("sMode").asText("s_tag");
        return novel
                ? p -> pixivFetchService.discoverSearchNovelIdsPage(word, order, mode, sMode, p, cookie)
                : p -> pixivFetchService.discoverSearchArtworkIdsPage(word, order, mode, sMode, p, cookie);
    }

    /**
     * FOLLOW_LATEST 的逐页 supplier：依次拉 follow_latest 单页喂给水位线扫描。{@code isLastPage}
     * 命中后下一次取页直接返回空 → 扫描自然停止（兼顾 Pixiv 偶发越界页不返回空数组的情形）。
     */
    private PageSupplier followLatestPages(String cookie) {
        boolean[] reachedLast = {false};
        return p -> {
            if (reachedLast[0]) {
                return List.of();
            }
            PixivFetchService.FollowLatestPage page = pixivFetchService.fetchFollowLatestPage(p, cookie);
            if (page.lastPage()) {
                reachedLast[0] = true;
            }
            return page.ids();
        };
    }

    /** 给定页码返回该页作品 ID（按页内顺序）；空 / null 表示无更多结果。 */
    @FunctionalInterface
    interface PageSupplier {
        List<String> get(int page) throws Exception;
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
                throws OveruseWarningException, ScheduleSuspendException, SchedulePauseException;
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
                                       Map<Long, PixivFetchService.IllustSeriesMeta> seriesCache)
            throws Exception {
        PixivFetchService.ArtworkMeta meta = pixivFetchService.fetchArtworkMeta(id, cookie);
        run.setMeta(id, meta.title(), meta.xRestrict(), meta.ai());
        if (!artworkMatches(meta, filters)) {
            run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            return null;
        }
        DownloadRequest.Other other = new DownloadRequest.Other();
        other.setAuthorId(meta.authorId());
        other.setAuthorName(meta.authorName());
        other.setXRestrict(meta.xRestrict());
        other.setAi(meta.ai());
        other.setDescription(meta.description());
        other.setTags(meta.tags());
        other.setSeriesId(meta.seriesId());
        other.setSeriesOrder(meta.seriesOrder());
        other.setIllustType(meta.illustType());
        other.setFileNameTemplate(download.fileNameTemplate());
        other.setBookmark(download.bookmark());
        other.setCollectionId(download.collectionId());
        // 图片间隔仅对多图插画有意义（下载器在相邻图片间 sleep）；小说不涉及。
        other.setDelayMs(download.imageDelayMs() == null ? 0 : Math.max(0, download.imageDelayMs()));
        // 系列富信息（标题 + 简介 + 封面）：与 web 链路一致，本轮按 seriesId 缓存、best-effort，失败不挡下载。
        if (meta.seriesId() != null && meta.seriesId() > 0) {
            other.setSeriesTitle(meta.seriesTitle());
            PixivFetchService.IllustSeriesMeta sm = resolveIllustSeriesMeta(meta.seriesId(), cookie, seriesCache);
            if (sm != null) {
                if (sm.caption() != null && !sm.caption().isBlank()) other.setSeriesDescription(sm.caption());
                if (sm.coverUrl() != null && !sm.coverUrl().isBlank()) other.setSeriesCoverUrl(sm.coverUrl());
            }
        }

        List<String> imageUrls;
        if (meta.isUgoira()) {
            PixivFetchService.UgoiraInfo ugoira = pixivFetchService.resolveUgoira(id, cookie);
            if (ugoira.zipUrl() == null || ugoira.zipUrl().isEmpty()) {
                throw new IllegalStateException("empty ugoira zip url");
            }
            other.setUgoira(true);
            other.setUgoiraZipUrl(ugoira.zipUrl());
            other.setUgoiraDelays(ugoira.delays());
            imageUrls = List.of(ugoira.zipUrl());
        } else {
            imageUrls = pixivFetchService.resolveImageUrls(id, cookie);
            if (imageUrls.isEmpty()) {
                throw new IllegalStateException("no image urls resolved");
            }
        }

        String title = meta.title();
        List<String> urls = imageUrls;
        String referer = PIXIV_REFERER + "artworks/" + id;
        return () -> artworkDownloader.downloadImagesBlocking(
                artworkId, title, urls, referer, other, cookie, null);
    }

    /** 抓取小说详情、应用筛选；命中则组装请求并返回待提交线程池的下载任务，被筛选跳过返回 {@code null}。 */
    private DownloadJob prepareNovel(String id, long novelId, String cookie,
                                     Filters filters, Download download, ScheduleRunQueue.Run run,
                                     Map<Long, PixivFetchService.NovelSeriesMeta> seriesCache)
            throws Exception {
        PixivFetchService.NovelDetail d = pixivFetchService.fetchNovelDetail(id, cookie);
        run.setMeta(id, d.title(), d.xRestrict(), d.ai());
        if (!novelMatches(d, filters)) {
            run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            return null;
        }
        NovelDownloadRequest req = new NovelDownloadRequest();
        req.setNovelId(d.novelId());
        req.setTitle(d.title());
        req.setCookie(cookie);
        req.setContent(d.content());
        NovelDownloadRequest.Other o = new NovelDownloadRequest.Other();
        o.setAuthorId(d.authorId());
        o.setAuthorName(d.authorName());
        o.setXRestrict(d.xRestrict());
        o.setAi(d.ai());
        o.setOriginal(d.original());
        o.setLanguage(d.language());
        o.setWordCount(d.wordCount());
        o.setTextLength(d.textLength());
        o.setReadingTimeSeconds(d.readingTimeSeconds());
        o.setPageCount(d.pageCount());
        o.setDescription(d.description());
        o.setTags(d.tags());
        o.setSeriesId(d.seriesId());
        o.setSeriesOrder(d.seriesOrder());
        o.setSeriesTitle(d.seriesTitle());
        o.setUploadTimestamp(d.uploadTimestamp());
        o.setCoverUrl(d.coverUrl());
        o.setEmbeddedImages(d.embeddedImages());
        o.setFileNameTemplate(download.fileNameTemplate());
        o.setBookmark(download.bookmark());
        o.setCollectionId(download.collectionId());
        o.setFormat(download.novelFormat());
        // 系列富信息（简介 + 封面 + 系列标签）：与 web 链路一致，本轮按 seriesId 缓存、best-effort，失败不挡下载。
        if (d.seriesId() != null && d.seriesId() > 0) {
            PixivFetchService.NovelSeriesMeta sm = resolveNovelSeriesMeta(d.seriesId(), cookie, seriesCache);
            if (sm != null) {
                if (sm.caption() != null && !sm.caption().isBlank()) o.setSeriesDescription(sm.caption());
                if (sm.coverUrl() != null && !sm.coverUrl().isBlank()) o.setSeriesCoverUrl(sm.coverUrl());
                if (sm.tags() != null && !sm.tags().isEmpty()) o.setSeriesTags(sm.tags());
            }
        }
        req.setOther(o);
        return () -> novelDownloader.downloadBlocking(req, null);
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
            log.debug("Scheduled illust series {} enrichment skipped: {}", seriesId, e.getMessage());
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
            log.debug("Scheduled novel series {} enrichment skipped: {}", seriesId, e.getMessage());
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
        private final Filters filters;
        private final Download download;
        private final ScheduleRunQueue.Run run;
        private final Semaphore permits;
        private final TaskExecutor pool;
        private final List<PendingExhaustedNotification> pendingNotifications;
        // 仅调度主线程顺序追加 / 读取（process 与 awaitAll 都在主线程），无需并发容器。
        private final List<CompletableFuture<Void>> inflight = new ArrayList<>();
        // 本轮系列富信息缓存（按 seriesId）：同一系列的多个章节只查一次 Pixiv 系列 AJAX，仅主线程访问。
        private final Map<Long, PixivFetchService.IllustSeriesMeta> illustSeriesCache = new HashMap<>();
        private final Map<Long, PixivFetchService.NovelSeriesMeta> novelSeriesCache = new HashMap<>();
        private final Object pendingLock = new Object();
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicInteger completedDownloads = new AtomicInteger(0);
        private int dispatchedSinceCheck = 0;

        WorkRunner(ScheduledTask task, boolean novel, String cookie, Filters filters, Download download,
                   ScheduleRunQueue.Run run, int concurrency, TaskExecutor pool,
                   List<PendingExhaustedNotification> pendingNotifications) {
            this.taskId = task.id();
            this.ackWarningTime = task.ackWarningTime();
            this.novel = novel;
            this.cookie = cookie;
            this.filters = filters;
            this.download = download;
            this.run = run;
            this.permits = new Semaphore(Math.max(1, concurrency));
            this.pool = pool;
            this.pendingNotifications = pendingNotifications;
        }

        ScheduleRunQueue.Run run() {
            return run;
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
            } catch (Exception e) {
                log.warn("Scheduled task {} await in-flight downloads failed: {}", taskId, e.getMessage());
            }
        }

        /**
         * @param isRetry true 表示来自隔离表重试（失败 {@code incPendingAttempts}）；否则新失败 {@code insertPending}
         * @return true 仅当成功把下载提交到线程池（不代表下载已成功——结果在 {@link #onComplete} 统计）
         */
        boolean process(String id, long workId, boolean isRetry)
                throws OveruseWarningException, ScheduleSuspendException, SchedulePauseException {
            // 协作式取消：用户在运行中按下「暂停」后，下一个作品派发前抛 PauseException 干净 unwind。
            // 已提交的下载在池里继续跑完不打断；此后不再发起新的派发。
            if (runState.isCancelRequested(taskId)) {
                throw new SchedulePauseException();
            }
            DownloadJob job;
            try {
                job = novel
                        ? prepareNovel(id, workId, cookie, filters, download, run, novelSeriesCache)
                        : prepareArtwork(id, workId, cookie, filters, download, run, illustSeriesCache);
            } catch (HttpClientErrorException e) {
                int sc = e.getStatusCode().value();
                if (sc == 404 || sc == 403) {
                    // 真已删除 / 注销：跳过、不进隔离、不挡 watermark
                    run.mark(id, ScheduleRunQueue.STATUS_FAILED, "gone " + sc);
                    return false;
                }
                // 其它 4xx（含 429）：可恢复瞬时，隔离不计熔断
                recordRecoverable(workId, isRetry, "http " + sc);
                return false;
            } catch (PixivFetchService.PixivFetchException e) {
                // 受限 / 需登录（可恢复）：隔离 + 连续失败计数；连续 M 次熔断
                String reason = pendingReason(e);
                run.mark(id, ScheduleRunQueue.STATUS_FAILED, reason);
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
                run.mark(id, ScheduleRunQueue.STATUS_FAILED, reason);
                recordRecoverable(workId, isRetry, reason);
                return false;
            }

            if (job == null) {
                // 被筛选跳过：重试条目从隔离表移除（不再属于「待重试」）。
                if (isRetry) {
                    synchronized (pendingLock) {
                        database.mapper().deletePending(taskId, workId);
                    }
                }
                return false;
            }

            // 准备就绪：限流后提交到下载池并发执行；派发点（提交成功）即做过度访问 N 检查。
            submit(id, workId, isRetry, job);
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
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                boolean ok;
                try {
                    ok = job.run();
                } catch (Exception e) {
                    // 下载器抛出的 message 可能含上游错误 URL（带 PHPSESSID 查询串）或 cookie 头，先脱敏。
                    log.warn("Scheduled task {} download work {} threw: {}",
                            taskId, workId, redactCookies(String.valueOf(e.getMessage())));
                    ok = false;
                }
                try {
                    onComplete(id, workId, isRetry, ok);
                } finally {
                    permits.release();
                }
            }, pool);
            inflight.add(f);
        }

        /** 下载完成回调（池线程）：成功清隔离 + 计数 + 清零连续失败；失败隔离、不计熔断。 */
        private void onComplete(String id, long workId, boolean isRetry, boolean ok) {
            if (ok) {
                run.mark(id, ScheduleRunQueue.STATUS_DOWNLOADED, null);
                consecutiveFailures.set(0);
                synchronized (pendingLock) {
                    database.mapper().deletePending(taskId, workId);
                }
                completedDownloads.incrementAndGet();
            } else {
                // 下载器顶层异常被吞返 false：隔离、不计熔断
                run.mark(id, ScheduleRunQueue.STATUS_FAILED, "download failed");
                recordRecoverable(workId, isRetry, "download failed");
            }
        }

        private void recordRecoverable(long workId, boolean isRetry, String reason) {
            long now = System.currentTimeMillis();
            boolean exhausted = false;
            int attemptsAtLimit = 0;
            synchronized (pendingLock) {
                if (isRetry) {
                    database.mapper().incPendingAttempts(taskId, workId, now);
                    log.warn("Scheduled task {} retry work {} failed: {}", taskId, workId, reason);
                    // 刚跨过自动重试上限：发通知转人工。临界判断（== max）确保只在跨越那一次触发，
                    // 不会因为后续仍在表里的同行（attempts >= max 已被 retryPending 跳过）重复发。
                    int max = scheduleConfig.getPendingMaxAttempts();
                    Integer attempts = database.mapper().selectPendingAttempts(taskId, workId);
                    if (attempts != null && attempts == max) {
                        exhausted = true;
                        attemptsAtLimit = attempts;
                    }
                } else {
                    database.mapper().insertPending(taskId, workId, reason, now);
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
        String accountId = task.accountId();
        String message = String.valueOf(e.modifiedAt());
        Locale locale = AppLocale.normalize(Locale.getDefault());
        // 冻结前先取「将被冻结」的同账号任务（状态门与 freezeAccount 一致），用于在通知里逐条列出被挂起的任务名 / ID。
        List<ScheduledTask> affected = collectFreezableTasks(task, accountId);
        int frozen = 1;
        if (accountId != null && !accountId.isBlank()) {
            frozen = Math.max(1, database.mapper().freezeAccount(
                    accountId, ScheduledTask.STATUS_OVERUSE_PAUSED, message));
        }
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
        ph.put("task_type", taskTypeLabel(locale, task.type()));
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
        ph.put("task_type", taskTypeLabel(locale, task.type()));
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
        ph.put("task_type", taskTypeLabel(locale, task.type()));
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

    /**
     * 取「将被账号级冻结」的任务列表（状态门与 {@link top.sywyar.pixivdownload.schedule.db.ScheduledTaskMapper#freezeAccount}
     * 一致：排除已挂起态），供通知逐条列出。无 account（restricted 任务）时退化为当前任务一条。
     */
    private List<ScheduledTask> collectFreezableTasks(ScheduledTask current, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return List.of(current);
        }
        List<ScheduledTask> result = new ArrayList<>();
        for (ScheduledTask t : database.mapper().findByAccountId(accountId)) {
            String st = t.lastStatus();
            boolean suspended = ScheduledTask.STATUS_OVERUSE_PAUSED.equals(st)
                    || ScheduledTask.STATUS_AUTH_EXPIRED.equals(st)
                    || ScheduledTask.STATUS_PAUSED.equals(st);
            if (!suspended) {
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
                    html ? escapeHtml(name) : MarkdownEscape.escape(name), t.id());
            lines.add(html ? item : "- " + item);
        }
        if (tasks.size() > limit) {
            String more = messages.get(locale, "mail.template.overuse-paused.task-more", tasks.size());
            lines.add(html ? more : "- " + more);
        }
        return String.join(html ? "<br>" : "\n", lines);
    }

    /** 计划任务类型的本地化标签（与邮件 / 推送共用同一组 i18n key）。 */
    private String taskTypeLabel(Locale locale, ScheduledTaskType type) {
        if (type == null) {
            return "-";
        }
        String key = switch (type) {
            case USER_NEW -> "mail.template.common.task-type.user-new";
            case SEARCH -> "mail.template.common.task-type.search";
            case SERIES -> "mail.template.common.task-type.series";
            case MY_BOOKMARKS -> "mail.template.common.task-type.my-bookmarks";
            case FOLLOW_LATEST -> "mail.template.common.task-type.follow-latest";
            case COLLECTION -> "mail.template.common.task-type.collection";
        };
        return messages.get(locale, key);
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

    // ── 服务端筛选 ────────────────────────────────────────────────────────────────

    static boolean artworkMatches(PixivFetchService.ArtworkMeta m, Filters f) {
        if (!contentMatches(f.content(), m.xRestrict())) return false;
        if ("exclude".equals(f.aiFilter()) && m.ai()) return false;
        if ("only".equals(f.aiFilter()) && !m.ai()) return false;
        if (!typeMatches(f.typeFilter(), m.illustType())) return false;
        if (m.pageCount() > 0) {
            if (f.pagesMin() != null && m.pageCount() < f.pagesMin()) return false;
            if (f.pagesMax() != null && m.pageCount() > f.pagesMax()) return false;
        }
        if (m.bookmarkCount() >= 0) {
            if (f.bookmarksMin() != null && m.bookmarkCount() < f.bookmarksMin()) return false;
            if (f.bookmarksMax() != null && m.bookmarkCount() > f.bookmarksMax()) return false;
        }
        List<String> tokens = tagTokens(m.tags());
        return tagsAllMatch(tokens, f.tagsExact(), false)
                && tagsAllMatch(tokens, f.tagsFuzzy(), true);
    }

    static boolean novelMatches(PixivFetchService.NovelDetail d, Filters f) {
        if (!contentMatches(f.content(), d.xRestrict())) return false;
        if ("exclude".equals(f.aiFilter()) && d.ai()) return false;
        if ("only".equals(f.aiFilter()) && !d.ai()) return false;
        if (d.wordCount() != null && d.wordCount() > 0) {
            if (f.wordsMin() != null && d.wordCount() < f.wordsMin()) return false;
            if (f.wordsMax() != null && d.wordCount() > f.wordsMax()) return false;
        }
        if (d.bookmarkCount() >= 0) {
            if (f.bookmarksMin() != null && d.bookmarkCount() < f.bookmarksMin()) return false;
            if (f.bookmarksMax() != null && d.bookmarkCount() > f.bookmarksMax()) return false;
        }
        List<String> tokens = tagTokens(d.tags());
        return tagsAllMatch(tokens, f.tagsExact(), false)
                && tagsAllMatch(tokens, f.tagsFuzzy(), true);
    }

    private static boolean contentMatches(String content, int xRestrict) {
        if (content == null) return true;
        return switch (content) {
            case "safe" -> xRestrict == 0;
            case "r18plus" -> xRestrict >= 1;
            case "r18" -> xRestrict == 1;
            case "r18g" -> xRestrict == 2;
            default -> true; // all
        };
    }

    private static boolean typeMatches(String typeFilter, int illustType) {
        if (typeFilter == null || "all".equals(typeFilter)) return true;
        return switch (typeFilter) {
            case "illust" -> illustType == 0;
            case "manga" -> illustType == 1;
            case "ugoira" -> illustType == 2;
            default -> true;
        };
    }

    private static boolean tagsAllMatch(List<String> tokens, List<String> required, boolean fuzzy) {
        if (required == null || required.isEmpty()) return true;
        for (String needle : required) {
            boolean hit = false;
            for (String tok : tokens) {
                if (fuzzy ? tok.contains(needle) : tok.equals(needle)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) return false;
        }
        return true;
    }

    private static List<String> tagTokens(List<TagDto> tags) {
        List<String> tokens = new ArrayList<>();
        if (tags == null) return tokens;
        for (TagDto tag : tags) {
            if (tag.getName() != null && !tag.getName().isBlank()) {
                tokens.add(tag.getName().toLowerCase(Locale.ROOT));
            }
            if (tag.getTranslatedName() != null && !tag.getTranslatedName().isBlank()) {
                tokens.add(tag.getTranslatedName().toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    // ── params 解析 ──────────────────────────────────────────────────────────────

    static Filters parseFilters(JsonNode f) {
        return new Filters(
                f.path("content").asText("all"),
                f.path("aiFilter").asText("all"),
                readLoweredList(f.path("tagsExact")),
                readLoweredList(f.path("tagsFuzzy")),
                f.path("typeFilter").asText("all"),
                intOrNull(f.path("pagesMin")), intOrNull(f.path("pagesMax")),
                intOrNull(f.path("wordsMin")), intOrNull(f.path("wordsMax")),
                intOrNull(f.path("bookmarksMin")), intOrNull(f.path("bookmarksMax")));
    }

    static Download parseDownload(JsonNode d) {
        String template = d.path("fileNameTemplate").asText("");
        return new Download(
                template.isBlank() ? null : template,
                d.path("bookmark").asBoolean(false),
                longOrNull(d.path("collectionId")),
                Math.max(1, d.path("concurrent").asInt(1)),
                longOrNull(d.path("intervalMs")),
                intOrNull(d.path("imageDelayMs")),
                d.path("verifyFiles").asBoolean(false),
                d.path("novelFormat").asText("txt"),
                d.path("novelMerge").asBoolean(false),
                d.path("novelMergeFormat").asText("epub"));
    }

    private static List<String> readLoweredList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String v = n.asText("").trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty()) out.add(v);
            }
        }
        return out;
    }

    private static Integer intOrNull(JsonNode n) {
        if (n.isNumber()) return n.asInt();
        if (n.isTextual() && !n.asText().isBlank()) {
            try {
                return Integer.parseInt(n.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Long longOrNull(JsonNode n) {
        if (n.isNumber()) return n.asLong();
        if (n.isTextual() && !n.asText().isBlank()) {
            try {
                return Long.parseLong(n.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** 插画去重谓词：按「实际目录检测」开关选 isArtworkDownloaded(verify) 或裸 hasArtwork；小说恒为 hasNovel。 */
    private LongPredicate alreadyDownloadedPredicate(boolean novel, Download download) {
        if (novel) {
            return novelDatabase::hasNovel;
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

    /** 任务快照的筛选条件（来自 params_json 的 {@code filters} 段）。 */
    record Filters(String content, String aiFilter, List<String> tagsExact, List<String> tagsFuzzy,
                   String typeFilter, Integer pagesMin, Integer pagesMax,
                   Integer wordsMin, Integer wordsMax, Integer bookmarksMin, Integer bookmarksMax) {
    }

    /**
     * 任务快照的下载设置（来自 params_json 的 {@code download} 段）。
     *
     * @param concurrent   最大并发数（作品级），实际取 {@code min(该值, 对应下载池大小)}
     * @param intervalMs   作品间隔（毫秒，礼貌延迟），{@code null} / 0 不延迟
     * @param imageDelayMs 图片间隔（毫秒，仅插画多图相邻图片间），{@code null} / 0 不延迟
     * @param verifyFiles  实际目录检测（仅插画）：去重时校验磁盘文件是否存在
     */
    record Download(String fileNameTemplate, boolean bookmark, Long collectionId,
                    int concurrent, Long intervalMs, Integer imageDelayMs, boolean verifyFiles,
                    String novelFormat, boolean novelMerge, String novelMergeFormat) {
    }
}
