package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.i18n.AppLocale;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.mail.MailService;
import top.sywyar.pixivdownload.mail.template.MailTemplateRegistry;
import top.sywyar.pixivdownload.mail.template.RenderedMail;
import top.sywyar.pixivdownload.novel.NovelDownloadService;
import top.sywyar.pixivdownload.novel.NovelDownloader;
import top.sywyar.pixivdownload.novel.NovelMergeService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskDatabase;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskPending;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *       非依赖型降级匿名续跑；{@code WARNED} 抛 {@link OveruseWarningException} → 账号级冻结。</li>
 *   <li><b>轮内 N 检查点</b>：每成功派发 N（{@code schedule.inbox-check-every}）个下载读一次站内信，{@code WARNED} 干净 unwind。</li>
 *   <li><b>单作品异常分类</b>：404/403-gone 跳过不挡 watermark；可恢复 {@link PixivFetchService.PixivFetchException}
 *       记隔离表 + 连续计数，连续 M（{@code schedule.auth-failure-circuit-breaker}）次熔断挂起。</li>
 *   <li><b>watermark</b>：reached 边界且无挂起条件即推进（哪怕本轮有作品进隔离表）；挂起异常上抛时绝不推进。</li>
 * </ul>
 * 自动挂起均发通知邮件（best-effort）；手动 {@code PAUSED} 不发。
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
    private final MailService mailService;
    private final MailTemplateRegistry mailTemplateRegistry;
    private final AppMessages messages;

    /** {@code last_message} 失败原因摘要的最大长度（截断防止超长异常文本撑爆列）。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;
    private static final Pattern COOKIE_HEADER_PATTERN = Pattern.compile("(?i)\\b(cookie\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern PHPSESSID_PATTERN = Pattern.compile("(?i)\\b(PHPSESSID\\s*=\\s*)[^;\\s]+");
    private static final Pattern COOKIE_PAIR_PATTERN = Pattern.compile("(?i)(^|[;\\s])([A-Za-z0-9_-]+\\s*=\\s*)[^;\\s]+");

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
        try {
            // 落库本轮开始时刻：正常结束（含干净挂起）时 updateRunResult 会清为 NULL；进程被强杀则残留 → 中断红灯。
            database.mapper().updateRunStarted(task.id(), System.currentTimeMillis());
            int completed = runTask(task);
            status = STATUS_OK;
            log.info("Scheduled task {} ({}) completed {} new download(s)", task.id(), task.name(), completed);
        } catch (OveruseWarningException e) {
            // 过度访问警告：账号级暂停 + 冻结同账号 + 邮件；干净挂起（清 run_started_time）。
            status = STATUS_OVERUSE_PAUSED;
            message = String.valueOf(e.modifiedAt()); // 触发警告 modifiedAt：供卡片展示 + 账号级 ack 取用
            handleOveruse(task, e);
            log.warn("Scheduled task {} ({}) paused: overuse warning", task.id(), task.name());
        } catch (ScheduleSuspendException e) {
            // cookie 依赖型 dead cookie / 单作品连续失败熔断：任务级挂起 + 邮件。
            status = STATUS_AUTH_EXPIRED;
            handleSuspend(task, e);
            log.warn("Scheduled task {} ({}) suspended: {}", task.id(), task.name(), e.reason());
        } catch (SchedulePauseException e) {
            // 用户在本轮派发循环中按了「暂停」：DB 已被 ScheduleService.pause 写成 PAUSED，
            // 此处仅记日志、不冻账号、不发邮件；下面的 updateRunResult 走 CASE 保留 PAUSED 状态。
            // 已派发的下载继续在 @Async 池中跑完（取消点位于下个作品派发前，不打断进行中的下载），
            // 未派发的作品本轮不再继续。
            status = ScheduledTask.STATUS_PAUSED;
            log.info("Scheduled task {} ({}) paused mid-run by user", task.id(), task.name());
        } catch (PixivFetchService.PixivFetchException e) {
            // 发现阶段鉴权失效（轮首/翻页）：不写 cookie 到日志，挂起并发邮件等管理员重授权。
            status = STATUS_AUTH_EXPIRED;
            handleSuspend(task, new ScheduleSuspendException(ScheduleSuspendException.Reason.COOKIE_DEAD));
            log.warn("Scheduled task {} ({}) auth expired, awaiting re-authorization", task.id(), task.name());
        } catch (Exception e) {
            status = STATUS_ERROR;
            message = summarizeError(e);
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
        String collapsed = raw.replaceAll("\\s+", " ").trim();
        collapsed = COOKIE_HEADER_PATTERN.matcher(collapsed).replaceAll("$1[redacted]");
        if (collapsed.toLowerCase(Locale.ROOT).contains("phpsessid=")) {
            collapsed = COOKIE_PAIR_PATTERN.matcher(collapsed).replaceAll("$1$2[redacted]");
        } else {
            collapsed = PHPSESSID_PATTERN.matcher(collapsed).replaceAll("$1[redacted]");
        }
        if (collapsed.length() > MAX_ERROR_MESSAGE_LENGTH) {
            collapsed = collapsed.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "…";
        }
        return collapsed;
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
        String redacted = COOKIE_HEADER_PATTERN.matcher(raw).replaceAll("$1[redacted]");
        if (redacted.toLowerCase(Locale.ROOT).contains("phpsessid=")) {
            redacted = COOKIE_PAIR_PATTERN.matcher(redacted).replaceAll("$1$2[redacted]");
        } else {
            redacted = PHPSESSID_PATTERN.matcher(redacted).replaceAll("$1[redacted]");
        }
        return redacted;
    }

    /**
     * 发现 + 过滤 + 同步下载，返回实际完成的新下载数。
     *
     * @throws OveruseWarningException 检测到过度访问警告（轮首 / 轮内 N 检查点）
     * @throws ScheduleSuspendException cookie 依赖型 dead cookie / 单作品连续失败熔断
     * @throws PixivFetchService.PixivFetchException 发现阶段鉴权失效
     */
    private int runTask(ScheduledTask task) throws Exception {
        String cookie = ScheduledTask.COOKIE_BOUND.equals(task.cookieMode())
                ? database.mapper().findCookieSnapshot(task.id())
                : null;

        JsonNode root = objectMapper.readTree(task.paramsJson() == null ? "{}" : task.paramsJson());
        boolean novel = KIND_NOVEL.equalsIgnoreCase(root.path("kind").asText("illust"));
        JsonNode source = root.path("source");
        Filters filters = parseFilters(root.path("filters"));
        Download download = parseDownload(root.path("download"));

        // ── 轮首检查点：cookie-bound 任务读站内信（过度访问判定 + cookie 存活探测）──────────────
        if (ScheduledTask.COOKIE_BOUND.equals(task.cookieMode())) {
            OveruseWarningService.Result result =
                    overuseWarningService.check(cookie, task.ackWarningTime(), System.currentTimeMillis());
            if (result.isCookieDead()) {
                if (isCookieDependent(root)) {
                    throw new ScheduleSuspendException(ScheduleSuspendException.Reason.COOKIE_DEAD);
                }
                // 非依赖型：降级匿名续跑（全年龄作品照样抓全、不丢、不浪费）
                cookie = null;
            } else if (result.isWarned()) {
                throw new OveruseWarningException(result.modifiedAt(), result.excerpt());
            }
        }

        // 开新一轮的运行队列（整体替换上一轮）。
        ScheduleRunQueue.Run run = runQueue.begin(task.id(),
                novel ? ScheduleRunQueue.KIND_NOVEL : ScheduleRunQueue.KIND_ILLUST);

        WorkRunner runner = new WorkRunner(task, novel, cookie, filters, download, run);
        int completed = 0;

        // ── 每轮无条件先消费隔离表（不再依赖 pendingRetryArmed 武装位）：走正常 dispatch 路径，
        //    计入 N 检查点与过度访问风险；单作品 attempts 自动 +1，达到上限时发邮件转人工。──
        completed += retryPending(task.id(), runner);

        if (isWatermarkMode(task.type(), source)) {
            completed += runWatermarkMode(task, novel, source, cookie, runner, run);
        } else if (isDownloadedBoundarySearchMode(task.type(), source)) {
            completed += runDownloadedBoundarySearch(novel, source, cookie, runner, run);
        } else {
            List<String> ids = discoverIds(task.type(), novel, source, cookie);
            ids.forEach(run::discovered);
            for (String id : ids) {
                long workId;
                try {
                    workId = Long.parseLong(id);
                } catch (NumberFormatException e) {
                    continue;
                }
                boolean already = novel ? novelDatabase.hasNovel(workId) : pixivDatabase.hasArtwork(workId);
                if (already) {
                    run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
                    continue;
                }
                if (runner.process(id, workId, false)) {
                    completed++;
                }
                politeDelay();
            }
        }

        // 小说系列合订：best-effort、幂等；本轮有新章节时立即合订。
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

    /** 重试隔离表中尚未达到上限的作品；成功 DELETE、失败 incPendingAttempts，全经正常分类路径。 */
    private int retryPending(long taskId, WorkRunner runner)
            throws OveruseWarningException, ScheduleSuspendException, SchedulePauseException {
        int max = scheduleConfig.getPendingMaxAttempts();
        int completed = 0;
        for (ScheduledTaskPending p : database.mapper().listPending(taskId)) {
            if (p.attempts() >= max) {
                continue; // 需人工，停止自动重试
            }
            String id = String.valueOf(p.workId());
            runner.run().discovered(id);
            if (runner.process(id, p.workId(), true)) {
                completed++;
            }
            politeDelay();
        }
        return completed;
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
        };
    }

    private static boolean isWatermarkMode(ScheduledTaskType type, JsonNode source) {
        if (type == ScheduledTaskType.USER_NEW) return true;
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

    private int runWatermarkMode(ScheduledTask task, boolean novel, JsonNode source, String cookie,
                                 WorkRunner runner, ScheduleRunQueue.Run run) throws Exception {
        PageSupplier pages;
        if (task.type() == ScheduledTaskType.USER_NEW) {
            String userId = source.path("userId").asText("");
            List<String> all = novel ? pixivFetchService.discoverUserNovelIds(userId, cookie)
                                     : pixivFetchService.discoverUserArtworkIds(userId, cookie);
            pages = p -> p == 1 ? all : List.of();
        } else {
            pages = searchPages(novel, source, cookie);
        }
        java.util.function.LongPredicate already = novel ? novelDatabase::hasNovel : pixivDatabase::hasArtwork;
        WorkDispatcher dispatcher = (id, workId) -> runner.process(id, workId, false);
        long watermark = task.watermarkId() == null ? 0L : task.watermarkId();
        Runnable pageDelay = task.type() == ScheduledTaskType.SEARCH ? this::watermarkPageDelay : () -> {};
        WatermarkScanResult result = runWatermarkScan(
                pages, watermark, already, dispatcher, this::politeDelay, pageDelay, run);
        // reached 边界且无挂起条件即推进（哪怕本轮有作品进隔离表）；挂起异常上抛时本方法不会执行到这里。
        if (result.newestSeen() > 0) {
            database.mapper().updateWatermark(task.id(), result.newestSeen());
        }
        return result.dispatched();
    }

    private int runDownloadedBoundarySearch(boolean novel, JsonNode source, String cookie,
                                            WorkRunner runner, ScheduleRunQueue.Run run) throws Exception {
        PageSupplier pages = searchPages(novel, source, cookie);
        java.util.function.LongPredicate already = novel ? novelDatabase::hasNovel : pixivDatabase::hasArtwork;
        WorkDispatcher dispatcher = (id, workId) -> runner.process(id, workId, false);
        return runDownloadedBoundaryScan(
                pages, already, dispatcher, this::politeDelay, this::watermarkPageDelay, run);
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

    /** 水位线扫描结果：派发数与发现到的最新 ID（正常返回即代表追到边界、可推进水位线）。 */
    record WatermarkScanResult(int dispatched, long newestSeen) {
    }

    /**
     * 水位线增量扫描的纯逻辑（package-private + static，便于单测）：逐页取 ID（最新在前），
     * 累积 {@code newestSeen = max(所有发现到的 ID)}；命中 {@code watermark > 0 && id <= watermark}
     * 即停止整轮翻页；已下载（去重命中）跳过；连续<b>一整页全部已下载</b>兜底停；空页停。
     * 单作品的可恢复失败由 {@code dispatcher} 内部隔离、不影响 watermark 推进；
     * 挂起信号（过度访问 / 熔断）与发现阶段鉴权失效上抛，本方法不返回 → 不推进 watermark。
     */
    static WatermarkScanResult runWatermarkScan(PageSupplier pages, long watermark,
                                                java.util.function.LongPredicate alreadyDownloaded,
                                                WorkDispatcher dispatcher, Runnable politeDelay,
                                                Runnable pageDelay, ScheduleRunQueue.Run run) throws Exception {
        int dispatched = 0;
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
                    return new WatermarkScanResult(dispatched, newestSeen);
                }
                run.discovered(id);
                if (alreadyDownloaded.test(workId)) {
                    run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
                    continue;
                }
                wholePageAlreadyDownloaded = false;
                if (dispatcher.dispatch(id, workId)) {
                    dispatched++;
                }
                politeDelay.run();
            }
            if (wholePageAlreadyDownloaded) break;
            pageDelay.run();
        }
        return new WatermarkScanResult(dispatched, newestSeen);
    }

    /**
     * 非 date_d 增量搜索的纯逻辑：排序不保证 ID 单调，不用 watermark，逐页处理到命中第一个已下载作品即停。
     */
    static int runDownloadedBoundaryScan(PageSupplier pages,
                                         java.util.function.LongPredicate alreadyDownloaded,
                                         WorkDispatcher dispatcher, Runnable politeDelay,
                                         Runnable pageDelay, ScheduleRunQueue.Run run) throws Exception {
        int dispatched = 0;
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
                    return dispatched;
                }
                run.discovered(id);
                if (dispatcher.dispatch(id, workId)) {
                    dispatched++;
                }
                politeDelay.run();
            }
            pageDelay.run();
        }
        return dispatched;
    }

    /** 单作品派发结果（区分过滤跳过、成功下载、下载失败），供 {@link WorkRunner} 分类。 */
    enum DispatchOutcome {DOWNLOADED, FILTERED, DOWNLOAD_FAILED}

    /** 抓取插画元数据、应用筛选，命中则派发下载。 */
    private DispatchOutcome dispatchArtwork(String id, long artworkId, String cookie,
                                            Filters filters, Download download, ScheduleRunQueue.Run run)
            throws Exception {
        PixivFetchService.ArtworkMeta meta = pixivFetchService.fetchArtworkMeta(id, cookie);
        run.setMeta(id, meta.title(), meta.xRestrict(), meta.ai());
        if (!artworkMatches(meta, filters)) {
            run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            return DispatchOutcome.FILTERED;
        }
        DownloadRequest.Other other = new DownloadRequest.Other();
        other.setAuthorId(meta.authorId());
        other.setAuthorName(meta.authorName());
        other.setXRestrict(meta.xRestrict());
        other.setAi(meta.ai());
        other.setSeriesId(meta.seriesId());
        other.setSeriesOrder(meta.seriesOrder());
        other.setIllustType(meta.illustType());
        other.setFileNameTemplate(download.fileNameTemplate());
        other.setBookmark(download.bookmark());
        other.setCollectionId(download.collectionId());

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

        boolean downloaded = artworkDownloader.downloadImagesBlocking(
                artworkId, meta.title(), imageUrls,
                PIXIV_REFERER + "artworks/" + id, other, cookie, null);
        run.mark(id, downloaded ? ScheduleRunQueue.STATUS_DOWNLOADED
                : ScheduleRunQueue.STATUS_FAILED, null);
        return downloaded ? DispatchOutcome.DOWNLOADED : DispatchOutcome.DOWNLOAD_FAILED;
    }

    /** 抓取小说详情、应用筛选，命中则按快照设置组装并派发小说下载。 */
    private DispatchOutcome dispatchNovel(String id, long novelId, String cookie,
                                          Filters filters, Download download, ScheduleRunQueue.Run run)
            throws Exception {
        PixivFetchService.NovelDetail d = pixivFetchService.fetchNovelDetail(id, cookie);
        run.setMeta(id, d.title(), d.xRestrict(), d.ai());
        if (!novelMatches(d, filters)) {
            run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            return DispatchOutcome.FILTERED;
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
        req.setOther(o);

        boolean downloaded = novelDownloader.downloadBlocking(req, null);
        run.mark(id, downloaded ? ScheduleRunQueue.STATUS_DOWNLOADED
                : ScheduleRunQueue.STATUS_FAILED, null);
        return downloaded ? DispatchOutcome.DOWNLOADED : DispatchOutcome.DOWNLOAD_FAILED;
    }

    /**
     * 一轮运行内对单作品派发做异常分类、隔离表记账、连续失败熔断与到 N 过度访问检查的有状态封装。
     * 连续失败计数与派发计数在整轮内跨页累积；任一成功派发即清零连续失败计数。
     */
    private final class WorkRunner {
        private final long taskId;
        private final String taskName;
        private final boolean novel;
        private final String cookie;
        private final Filters filters;
        private final Download download;
        private final ScheduleRunQueue.Run run;

        private int consecutiveFailures = 0;
        private int dispatchedSinceCheck = 0;

        WorkRunner(ScheduledTask task, boolean novel, String cookie, Filters filters, Download download,
                   ScheduleRunQueue.Run run) {
            this.taskId = task.id();
            this.taskName = task.name();
            this.novel = novel;
            this.cookie = cookie;
            this.filters = filters;
            this.download = download;
            this.run = run;
        }

        ScheduleRunQueue.Run run() {
            return run;
        }

        /**
         * @param isRetry true 表示来自隔离表重试（失败 {@code incPendingAttempts}）；否则新失败 {@code insertPending}
         * @return true 仅当成功派发下载
         */
        boolean process(String id, long workId, boolean isRetry)
                throws OveruseWarningException, ScheduleSuspendException, SchedulePauseException {
            // 协作式取消：用户在运行中按下「暂停」后，下一个作品派发前抛 PauseException 干净 unwind。
            // 已派发的作品在 @Async 下载池里继续跑完不打断；此后不再发起新的派发。
            if (runState.isCancelRequested(taskId)) {
                throw new SchedulePauseException();
            }
            DispatchOutcome outcome;
            try {
                outcome = novel
                        ? dispatchNovel(id, workId, cookie, filters, download, run)
                        : dispatchArtwork(id, workId, cookie, filters, download, run);
            } catch (HttpClientErrorException e) {
                int sc = e.getStatusCode().value();
                if (sc == 404 || sc == 403) {
                    // 真已删除 / 注销：跳过、不进隔离、不挡 watermark
                    run.mark(id, ScheduleRunQueue.STATUS_FAILED, "gone " + sc);
                    return false;
                }
                // 其它 4xx（含 429）：可恢复瞬时，隔离不计熔断
                recordRecoverable(id, workId, isRetry, "http " + sc);
                return false;
            } catch (PixivFetchService.PixivFetchException e) {
                // 受限 / 需登录（可恢复）：隔离 + 连续失败计数；连续 M 次熔断
                String reason = pendingReason(e);
                run.mark(id, ScheduleRunQueue.STATUS_FAILED, reason);
                recordRecoverable(id, workId, isRetry, reason);
                consecutiveFailures++;
                if (consecutiveFailures >= scheduleConfig.getAuthFailureCircuitBreaker()) {
                    throw new ScheduleSuspendException(
                            ScheduleSuspendException.Reason.CIRCUIT_BREAKER, consecutiveFailures, reason);
                }
                return false;
            } catch (OveruseWarningException | ScheduleSuspendException e) {
                throw e;
            } catch (Exception e) {
                // 瞬时 IO / 解析错误：隔离、不计熔断
                String reason = pendingReason(e);
                run.mark(id, ScheduleRunQueue.STATUS_FAILED, reason);
                recordRecoverable(id, workId, isRetry, reason);
                return false;
            }

            switch (outcome) {
                case DOWNLOADED -> {
                    consecutiveFailures = 0;
                    database.mapper().deletePending(taskId, workId);
                    afterDispatchCheckpoint();
                    return true;
                }
                case FILTERED -> {
                    if (isRetry) {
                        database.mapper().deletePending(taskId, workId);
                    }
                    return false;
                }
                default -> { // DOWNLOAD_FAILED（顶层异常被下载器吞掉返 false）：隔离、不计熔断
                    recordRecoverable(id, workId, isRetry, "download failed");
                    return false;
                }
            }
        }

        private void recordRecoverable(String id, long workId, boolean isRetry, String reason) {
            long now = System.currentTimeMillis();
            if (isRetry) {
                database.mapper().incPendingAttempts(taskId, workId, now);
                log.warn("Scheduled task {} retry work {} failed: {}", taskId, workId, reason);
                // 刚跨过自动重试上限：发邮件转人工。临界判断（== max）确保只在跨越那一次触发，
                // 不会因为后续仍在表里的同行（attempts >= max 已被 retryPending 跳过）重复发。
                int max = scheduleConfig.getPendingMaxAttempts();
                Integer attempts = database.mapper().selectPendingAttempts(taskId, workId);
                if (attempts != null && attempts == max) {
                    notifyPendingExhausted(workId, attempts, reason);
                }
            } else {
                database.mapper().insertPending(taskId, workId, reason, now);
                log.warn("Scheduled task {} isolated work {}: {}", taskId, workId, reason);
            }
        }

        /** attempts 刚到达 {@code schedule.pending-max-attempts} 时发邮件，best-effort、不影响调度。 */
        private void notifyPendingExhausted(long workId, int attempts, String reason) {
            Locale locale = AppLocale.normalize(Locale.getDefault());
            String kindLabel = messages.get(locale, novel
                    ? "mail.template.pending-exhausted.kind.novel"
                    : "mail.template.pending-exhausted.kind.illust");
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("task_name", taskName == null ? "-" : taskName);
            ph.put("task_id", String.valueOf(taskId));
            ph.put("work_id", String.valueOf(workId));
            ph.put("work_kind", kindLabel);
            ph.put("attempts", String.valueOf(attempts));
            ph.put("trigger_time", formatTime(System.currentTimeMillis()));
            ph.put("last_error_excerpt", reason == null ? "" : reason);
            sendNotification(MailTemplateRegistry.TEMPLATE_PENDING_EXHAUSTED, ph);
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
            OveruseWarningService.Result r =
                    overuseWarningService.check(cookie, null, System.currentTimeMillis());
            if (r.isWarned()) {
                throw new OveruseWarningException(r.modifiedAt(), r.excerpt());
            }
        }
    }

    // ── 自动挂起通知邮件（best-effort） ────────────────────────────────────────────

    /** 过度访问：冻结同账号所有非挂起态任务 + 发 overuse-paused 邮件。 */
    private void handleOveruse(ScheduledTask task, OveruseWarningException e) {
        String accountId = task.accountId();
        String message = String.valueOf(e.modifiedAt());
        int frozen = 1;
        if (accountId != null && !accountId.isBlank()) {
            frozen = Math.max(1, database.mapper().freezeAccount(
                    accountId, ScheduledTask.STATUS_OVERUSE_PAUSED, message));
        }
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("account_id", accountId == null ? "-" : accountId);
        ph.put("tasks_count", String.valueOf(frozen));
        ph.put("warning_time", formatTime(e.modifiedAt()));
        ph.put("trigger_time", formatTime(System.currentTimeMillis()));
        ph.put("warning_excerpt", e.excerpt());
        sendNotification(MailTemplateRegistry.TEMPLATE_OVERUSE_PAUSED, ph);
    }

    /** 任务级挂起：发 auth-expired（dead cookie）或 circuit-breaker（熔断）邮件。 */
    private void handleSuspend(ScheduledTask task, ScheduleSuspendException e) {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", task.name() == null ? "-" : task.name());
        ph.put("task_id", String.valueOf(task.id()));
        ph.put("trigger_time", formatTime(System.currentTimeMillis()));
        if (e.reason() == ScheduleSuspendException.Reason.CIRCUIT_BREAKER) {
            ph.put("consecutive_failures", String.valueOf(e.consecutiveFailures()));
            ph.put("last_error_excerpt", e.lastErrorExcerpt() == null ? "" : e.lastErrorExcerpt());
            sendNotification(MailTemplateRegistry.TEMPLATE_CIRCUIT_BREAKER, ph);
        } else {
            // reason 文案走模板 i18n，运行期只给一个稳定的 key 标识
            ph.put("reason", e.reason().name());
            sendNotification(MailTemplateRegistry.TEMPLATE_AUTH_EXPIRED, ph);
        }
    }

    /** 渲染模板并发信，全程 best-effort：渲染或发信失败仅记日志，绝不影响调度。 */
    private void sendNotification(String templateId, Map<String, String> placeholders) {
        try {
            Locale locale = AppLocale.normalize(Locale.getDefault());
            RenderedMail mail = mailTemplateRegistry.render(templateId, locale, placeholders);
            mailService.send(mail.subject(), mail.htmlBody());
        } catch (Exception ex) {
            log.error("Schedule notification mail [{}] failed: {}", templateId, ex.getMessage());
        }
    }

    private static String formatTime(long epochMs) {
        if (epochMs <= 0) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(epochMs));
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
        return tagsAllMatch(m.tags(), f.tagsExact(), false)
                && tagsAllMatch(m.tags(), f.tagsFuzzy(), true);
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

    private void politeDelay() {
        long delay = scheduleConfig.getFetchDelayMs();
        if (delay <= 0) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void watermarkPageDelay() {
        try {
            Thread.sleep(WATERMARK_PAGE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 任务快照的筛选条件（来自 params_json 的 {@code filters} 段）。 */
    record Filters(String content, String aiFilter, List<String> tagsExact, List<String> tagsFuzzy,
                   String typeFilter, Integer pagesMin, Integer pagesMax,
                   Integer wordsMin, Integer wordsMax, Integer bookmarksMin, Integer bookmarksMax) {
    }

    /** 任务快照的下载设置（来自 params_json 的 {@code download} 段）。 */
    record Download(String fileNameTemplate, boolean bookmark, Long collectionId,
                    String novelFormat, boolean novelMerge, String novelMergeFormat) {
    }
}
