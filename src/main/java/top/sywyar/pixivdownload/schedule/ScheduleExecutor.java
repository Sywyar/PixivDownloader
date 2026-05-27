package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.novel.NovelDownloadService;
import top.sywyar.pixivdownload.novel.NovelDownloader;
import top.sywyar.pixivdownload.novel.NovelMergeService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 计划任务的执行核心：按任务类型在服务端发现作品 ID、跳过已下载、逐个抓取元数据、
 * <b>按任务快照的筛选条件做服务端过滤</b>，再按快照的下载设置派发下载。
 *
 * <p>调度以管理员身份运行（{@code userUuid=null}，不计配额 / 限流）。本类只依赖 {@code download/} 的窄接缝
 * （{@link ArtworkDownloader} / {@link PixivFetchService} / {@link PixivDatabase}）、{@code novel/} 的窄接缝
 * （{@link NovelDownloader} / {@link NovelDatabase} / {@link NovelMergeService}）与本包的 {@code db/}，
 * 不反向依赖 {@link ScheduleService} / {@link ScheduleRunner}，因此调度包内无环。
 *
 * <p><b>params_json v2 约定</b>（由前端「存为计划任务」快照、本类解析）：
 * <pre>{@code
 * { "kind": "illust"|"novel",
 *   "source":   {"userId"} | {"word","order","mode","sMode","maxPages"} | {"seriesId"},
 *   "filters":  {"content","aiFilter","tagsExact":[],"tagsFuzzy":[],"typeFilter",
 *                "pagesMin","pagesMax","wordsMin","wordsMax","bookmarksMin","bookmarksMax"},
 *   "download": {"fileNameTemplate","bookmark","collectionId",
 *                "novelFormat","novelMerge","novelMergeFormat"} }
 * }</pre>
 * 客户端的「作品间隔 / 图片间隔 / 最大并发」是浏览器队列调度概念，服务端计划串行 + {@code schedule.fetch-delay-ms}，
 * 不在快照范围内；计划任务使用同步下载入口，直到本轮真实下载与后置动作完成后才记录下次运行时间；
 * 「跳过已下载」在计划侧恒为开（{@code hasArtwork} / {@code hasNovel}）。
 *
 * <p>健壮性：单个作品抓取 / 下载失败仅记日志、不挂整轮；捕获
 * {@link PixivFetchService.PixivFetchException}（鉴权失效）则立即停止本轮、标记
 * {@link #STATUS_AUTH_EXPIRED}，等待管理员重新授权，不空跑重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleExecutor {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_AUTH_EXPIRED = "AUTH_EXPIRED";
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
            // 落库本轮开始时刻：正常结束时 updateRunResult 会清为 NULL；进程被强杀（没走到 updateRunResult）则残留 →
            // 即「上次运行被中断」信号，供前端中断红灯展示，重跑会刷新它并最终补齐后清除。
            database.mapper().updateRunStarted(task.id(), System.currentTimeMillis());
            int completed = runTask(task);
            status = STATUS_OK;
            log.info("Scheduled task {} ({}) completed {} new download(s)", task.id(), task.name(), completed);
        } catch (PixivFetchService.PixivFetchException e) {
            // 鉴权失效：不写 cookie 到日志，仅记任务标识与提示
            status = STATUS_AUTH_EXPIRED;
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
     * 发现 + 过滤 + 同步下载，返回实际完成的新下载数。
     *
     * @throws PixivFetchService.PixivFetchException Cookie 失效 / 受限需登录时上抛，供 {@link #runTaskAndRecord} 标记鉴权失效
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

        // 开新一轮的运行队列（整体替换上一轮），供前端「本轮队列详情」展示本轮每个作品的处理结果。
        ScheduleRunQueue.Run run = runQueue.begin(task.id(),
                novel ? ScheduleRunQueue.KIND_NOVEL : ScheduleRunQueue.KIND_ILLUST);

        int completed;
        if (isWatermarkMode(task.type(), source)) {
            // 水位线增量发现（USER_NEW 与「date_d + 结束页 = -1」的 SEARCH）：
            // 扫到上一轮水位线即停翻页，崩溃后水位线不更新 → 重跑靠去重补齐，不丢作品。
            completed = runWatermarkMode(task, novel, source, cookie, filters, download, run);
        } else if (isDownloadedBoundarySearchMode(task.type(), source)) {
            // 非 date_d 的「结束页 = -1」搜索没有可靠 ID 水位线，按用户语义逐页处理到命中已下载作品为止。
            completed = runDownloadedBoundarySearch(task, novel, source, cookie, filters, download, run);
        } else {
            List<String> ids = discoverIds(task.type(), novel, source, cookie);
            // 先把本轮发现到的全部作品登记为「等待处理」，再逐个推进状态。
            ids.forEach(run::discovered);
            completed = 0;
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
                try {
                    if (novel ? dispatchNovel(id, workId, cookie, filters, download, run)
                              : dispatchArtwork(id, workId, cookie, filters, download, run)) {
                        completed++;
                    }
                } catch (PixivFetchService.PixivFetchException e) {
                    throw e; // 鉴权失效：让整轮停下
                } catch (Exception e) {
                    // 单作品失败隔离：仅记日志，继续后续作品
                    String safeMessage = summarizeError(e);
                    run.mark(id, ScheduleRunQueue.STATUS_FAILED, safeMessage);
                    log.warn("Scheduled task {} skip work {} [{}]: {}",
                            task.id(), id, e.getClass().getSimpleName(), safeMessage);
                }
                politeDelay();
            }
        }

        // 小说系列合订：计划下载已同步完成并落库，因此本轮有新章节时可立即合订。
        // best-effort、幂等（按当前已落库章节重导），失败仅记日志。
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

    /**
     * 判定任务是否走「水位线增量发现」：仅适用于「最新在前 + 只会追加」的两类来源——
     * <ul>
     *   <li>{@code USER_NEW}：{@code discoverUser*Ids} 一次性返回全部 ID（按 ID 降序 = 最新在前）；</li>
     *   <li>增量 {@code SEARCH}：{@code maxPages == -1} 且 {@code order == date_d}（逐页发现、最新在前）。</li>
     * </ul>
     * SERIES / 非 date_d 的 SEARCH / 固定页数 SEARCH 不适用（作者可重排、order 因删除重排、workId 不对应顺序 → 锚点不可靠）。
     * 非 date_d 且 {@code maxPages == -1} 的 SEARCH 走「命中已下载即停」的逐页模式。
     */
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
     * 水位线增量发现：把按页发现、按 ID 去重判定、单作品派发的各依赖装配成函数后交给纯函数
     * {@link #runWatermarkScan}。USER_NEW 把全量 ID 当单页（{@code p==1} 返回全部、其余空）；
     * 增量 SEARCH 用逐页 supplier。一轮<b>正常跑完</b>（无异常）后才把水位线更新为本轮发现的最新 ID。
     */
    private int runWatermarkMode(ScheduledTask task, boolean novel, JsonNode source, String cookie,
                                 Filters filters, Download download, ScheduleRunQueue.Run run) throws Exception {
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
        WorkDispatcher dispatcher = novel
                ? (id, workId) -> dispatchNovel(id, workId, cookie, filters, download, run)
                : (id, workId) -> dispatchArtwork(id, workId, cookie, filters, download, run);
        long watermark = task.watermarkId() == null ? 0L : task.watermarkId();
        Runnable pageDelay = task.type() == ScheduledTaskType.SEARCH ? this::watermarkPageDelay : () -> {};
        WatermarkScanResult result = runWatermarkScan(
                task.id(), pages, watermark, already, dispatcher, this::politeDelay,
                pageDelay, run);
        // 只有完整追到边界且无单作品失败时才推进水位线；失败轮次保留旧水位，靠下轮去重重试补齐。
        if (result.complete() && result.newestSeen() > 0) {
            database.mapper().updateWatermark(task.id(), result.newestSeen());
        }
        return result.dispatched();
    }

    private int runDownloadedBoundarySearch(ScheduledTask task, boolean novel, JsonNode source, String cookie,
                                            Filters filters, Download download, ScheduleRunQueue.Run run)
            throws Exception {
        PageSupplier pages = searchPages(novel, source, cookie);
        java.util.function.LongPredicate already = novel ? novelDatabase::hasNovel : pixivDatabase::hasArtwork;
        WorkDispatcher dispatcher = novel
                ? (id, workId) -> dispatchNovel(id, workId, cookie, filters, download, run)
                : (id, workId) -> dispatchArtwork(id, workId, cookie, filters, download, run);
        return runDownloadedBoundaryScan(
                task.id(), pages, already, dispatcher, this::politeDelay, this::watermarkPageDelay, run);
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

    /** 处理单个未下载作品（取 meta → 筛选 → 派发），返回是否派发；鉴权失效时上抛 {@link PixivFetchService.PixivFetchException}。 */
    @FunctionalInterface
    interface WorkDispatcher {
        boolean dispatch(String id, long workId) throws Exception;
    }

    /** 水位线 SEARCH 翻到下一页前的强制礼貌延迟，避免「直到命中历史」模式连续打 Pixiv 搜索页。 */
    static final long WATERMARK_PAGE_DELAY_MS = 10_000L;

    /** 水位线扫描结果：派发数、发现到的最新 ID，以及本轮是否完整追到停止边界且无单作品失败。 */
    record WatermarkScanResult(int dispatched, long newestSeen, boolean complete) {
    }

    /**
     * 水位线增量扫描的纯逻辑（package-private + static，便于单测）：逐页取 ID（最新在前），
     * 累积 {@code newestSeen = max(所有发现到的 ID)}；命中 {@code watermark > 0 && id <= watermark}
     * 即停止整轮翻页（上一轮已处理到这里，其后皆旧作）；已下载（去重命中）的跳过、不计 politeDelay；
     * 连续<b>一整页全部已下载</b>则兜底停止（应对水位线作品被删 / 404 命中不到的情况）；
     * 某页为空 / null 视为无更多结果而停止；不设页数上限。单作品失败隔离（仅记日志、继续），
     * 但会把结果标记为 incomplete，避免推进水位线后漏掉失败作品；鉴权失效上抛。
     */
    static WatermarkScanResult runWatermarkScan(long taskId, PageSupplier pages, long watermark,
                                                java.util.function.LongPredicate alreadyDownloaded,
                                                WorkDispatcher dispatcher, Runnable politeDelay,
                                                Runnable pageDelay,
                                                ScheduleRunQueue.Run run) throws Exception {
        int dispatched = 0;
        long newestSeen = 0L;
        boolean complete = true;
        for (int p = 1; ; p++) {
            List<String> ids = pages.get(p);
            if (ids == null || ids.isEmpty()) break; // 无更多结果
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
                    // 命中上一轮水位线 → 其后皆旧作，停止整轮翻页（不登记进队列：本轮不再发现它）
                    return new WatermarkScanResult(dispatched, newestSeen, complete);
                }
                run.discovered(id);
                if (alreadyDownloaded.test(workId)) {
                    run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
                    continue; // 去重命中：跳过（不计 politeDelay），但本页仍可能有更新的新作
                }
                wholePageAlreadyDownloaded = false;
                try {
                    if (dispatcher.dispatch(id, workId)) {
                        dispatched++;
                    } else {
                        // 下载器吞异常并返回 false：dispatch 内部已把状态标为 STATUS_FAILED，
                        // 这里同步把整轮标为 incomplete，避免水位线越过这件未成功的作品。
                        complete = false;
                    }
                } catch (PixivFetchService.PixivFetchException e) {
                    throw e; // 鉴权失效：让整轮停下
                } catch (Exception e) {
                    complete = false;
                    String safeMessage = summarizeError(e);
                    run.mark(id, ScheduleRunQueue.STATUS_FAILED, safeMessage);
                    log.warn("Scheduled task {} skip work {} [{}]: {}",
                            taskId, id, e.getClass().getSimpleName(), safeMessage);
                }
                politeDelay.run();
            }
            if (wholePageAlreadyDownloaded) break; // 兜底：整页全已下载 → 停
            pageDelay.run();
        }
        return new WatermarkScanResult(dispatched, newestSeen, complete);
    }

    /**
     * 非 date_d 增量搜索的纯逻辑：排序不保证 ID 单调，不能使用 watermark，只按用户语义逐页处理，
     * 命中第一个已下载作品即停止；页间同样执行 10 秒强制延迟。
     */
    static int runDownloadedBoundaryScan(long taskId, PageSupplier pages,
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
                try {
                    if (dispatcher.dispatch(id, workId)) {
                        dispatched++;
                    }
                } catch (PixivFetchService.PixivFetchException e) {
                    throw e;
                } catch (Exception e) {
                    String safeMessage = summarizeError(e);
                    run.mark(id, ScheduleRunQueue.STATUS_FAILED, safeMessage);
                    log.warn("Scheduled task {} skip work {} [{}]: {}",
                            taskId, id, e.getClass().getSimpleName(), safeMessage);
                }
                politeDelay.run();
            }
            pageDelay.run();
        }
        return dispatched;
    }

    /** 抓取插画元数据、应用筛选，命中则派发下载。返回是否派发。 */
    private boolean dispatchArtwork(String id, long artworkId, String cookie,
                                    Filters filters, Download download, ScheduleRunQueue.Run run) throws Exception {
        PixivFetchService.ArtworkMeta meta = pixivFetchService.fetchArtworkMeta(id, cookie);
        run.setMeta(id, meta.title(), meta.xRestrict(), meta.ai());
        if (!artworkMatches(meta, filters)) {
            run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            return false;
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
        // downloadImagesBlocking 仅在成功跑完整条管线时返回 true；false 来自顶层异常或取消，
        // 异常已在 DownloadService 内被吞掉并记日志，此处只能给出泛化失败说明（前端按 i18n 回退）。
        run.mark(id, downloaded ? ScheduleRunQueue.STATUS_DOWNLOADED
                : ScheduleRunQueue.STATUS_FAILED, null);
        return downloaded;
    }

    /** 抓取小说详情、应用筛选，命中则按快照设置组装并派发小说下载。返回是否派发。 */
    private boolean dispatchNovel(String id, long novelId, String cookie,
                                  Filters filters, Download download, ScheduleRunQueue.Run run) throws Exception {
        PixivFetchService.NovelDetail d = pixivFetchService.fetchNovelDetail(id, cookie);
        run.setMeta(id, d.title(), d.xRestrict(), d.ai());
        if (!novelMatches(d, filters)) {
            run.mark(id, ScheduleRunQueue.STATUS_SKIPPED_FILTER, null);
            return false;
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
        // downloadBlocking 仅在成功跑完整条管线时返回 true；false 来自顶层异常或取消，
        // 异常已在 NovelDownloadService 内被吞掉并记日志，此处只能给出泛化失败说明（前端按 i18n 回退）。
        run.mark(id, downloaded ? ScheduleRunQueue.STATUS_DOWNLOADED
                : ScheduleRunQueue.STATUS_FAILED, null);
        return downloaded;
    }

    // ── 服务端筛选 ────────────────────────────────────────────────────────────────

    // package-private + static：纯函数，便于单元测试直接调用（见 ScheduleExecutorFilterTest）
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

    /** 内容分级筛选：all=不限 / safe=仅全年龄 / r18plus=R-18+R-18G / r18=仅 R-18 / r18g=仅 R-18G。 */
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

    /** {@code required} 中的每个关键词都必须命中 {@code tokens}（精确=相等 / 模糊=子串）；空筛选恒通过。 */
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
