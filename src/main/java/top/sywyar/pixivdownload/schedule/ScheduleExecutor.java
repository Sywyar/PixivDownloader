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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *   "filters":  {"r18Only","aiFilter","tagsExact":[],"tagsFuzzy":[],"typeFilter",
 *                "pagesMin","pagesMax","wordsMin","wordsMax","bookmarksMin","bookmarksMax"},
 *   "download": {"fileNameTemplate","bookmark","collectionId",
 *                "novelFormat","novelMerge","novelMergeFormat"} }
 * }</pre>
 * 客户端的「作品间隔 / 图片间隔 / 最大并发」是浏览器队列调度概念，服务端计划串行 + {@code schedule.fetch-delay-ms}，
 * 不在快照范围内；「跳过已下载」在计划侧恒为开（{@code hasArtwork} / {@code hasNovel}）。
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
    private final ObjectMapper objectMapper;

    /**
     * 小说系列「待合订」标记（按任务 id）。本轮有新派发→置位；下一轮无新增时（上一轮的异步下载
     * 已有整个间隔落库）合订一次并清标记，避免每轮重复合订空转。纯内存、best-effort：进程重启
     * 至多少触发一次自动合订，用户仍可在系列页手动合订。
     */
    private final Set<Long> pendingSeriesMerge = ConcurrentHashMap.newKeySet();

    /** 后台异步运行一次（供「立即运行」端点用，避免阻塞 HTTP 请求线程）。 */
    @Async
    public void runTaskAsync(long taskId) {
        ScheduledTask task = database.mapper().findById(taskId);
        if (task != null) {
            runTaskAndRecord(task);
        }
    }

    /**
     * 同步运行一个任务并把结果写回（last_run_time / last_status / next_run_time）。
     * 调度 tick 串行调用本方法。
     */
    public void runTaskAndRecord(ScheduledTask task) {
        long now = System.currentTimeMillis();
        String status;
        try {
            int dispatched = runTask(task);
            status = STATUS_OK;
            log.info("Scheduled task {} ({}) dispatched {} new download(s)", task.id(), task.name(), dispatched);
        } catch (PixivFetchService.PixivFetchException e) {
            // 鉴权失效：不写 cookie 到日志，仅记任务标识与提示
            status = STATUS_AUTH_EXPIRED;
            log.warn("Scheduled task {} ({}) auth expired, awaiting re-authorization", task.id(), task.name());
        } catch (Exception e) {
            status = STATUS_ERROR;
            log.error("Scheduled task {} ({}) failed: {}", task.id(), task.name(), e.getMessage(), e);
        }
        Long nextRun = ScheduleTiming.computeNextRun(
                task.triggerKind(), task.intervalMinutes(), task.cronExpr(), now);
        database.mapper().updateRunResult(task.id(), now, status, nextRun);
    }

    /**
     * 发现 + 过滤 + 下载，返回派发的新下载数。
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

        int dispatched;
        if (task.type() == ScheduledTaskType.SEARCH && source.path("maxPages").asInt(3) == -1) {
            // 结束页 = -1（仅管理员，且计划任务本就 admin-only）：增量模式——逐页发现、逐作品处理，
            // 命中第一个已下载作品即停（搜索按 date_d 最新在前 → 命中即代表后面都是旧作）。
            dispatched = runIncrementalSearch(task, novel, source, cookie, filters, download);
        } else {
            List<String> ids = discoverIds(task.type(), novel, source, cookie);
            dispatched = 0;
            for (String id : ids) {
                long workId;
                try {
                    workId = Long.parseLong(id);
                } catch (NumberFormatException e) {
                    continue;
                }
                boolean already = novel ? novelDatabase.hasNovel(workId) : pixivDatabase.hasArtwork(workId);
                if (already) {
                    continue;
                }
                try {
                    if (novel ? dispatchNovel(id, workId, cookie, filters, download)
                              : dispatchArtwork(id, workId, cookie, filters, download)) {
                        dispatched++;
                    }
                } catch (PixivFetchService.PixivFetchException e) {
                    throw e; // 鉴权失效：让整轮停下
                } catch (Exception e) {
                    // 单作品失败隔离：仅记日志，继续后续作品
                    log.warn("Scheduled task {} skip work {}: {}", task.id(), id, e.getMessage());
                }
                politeDelay();
            }
        }

        // 小说系列合订：best-effort、幂等（按当前已落库章节重导），失败仅记日志。
        // 新派发的下载为异步、未必已落库：本轮有新增 → 仅置「待合订」标记、不立即合订；
        // 下一轮无新增时（上一轮下载已有整个间隔落库）合订一次并清标记，避免每轮空合订。
        if (novel && task.type() == ScheduledTaskType.SERIES && download.novelMerge()) {
            long seriesId = source.path("seriesId").asLong(0);
            if (seriesId > 0) {
                if (dispatched > 0) {
                    pendingSeriesMerge.add(task.id());
                } else if (pendingSeriesMerge.remove(task.id())) {
                    try {
                        novelMergeService.merge(seriesId,
                                NovelDownloadService.NovelFormat.parse(download.novelMergeFormat()));
                    } catch (Exception e) {
                        log.warn("Scheduled task {} series merge failed: {}", task.id(), e.getMessage());
                    }
                }
            }
        }
        return dispatched;
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
     * SEARCH + {@code maxPages == -1} 的增量发现：把按页发现、按 ID 去重判定、单作品派发的
     * 各依赖装配成函数后交给纯函数 {@link #runIncrementalSearch(long, PageSupplier, java.util.function.LongPredicate, WorkDispatcher, Runnable)}。
     */
    private int runIncrementalSearch(ScheduledTask task, boolean novel, JsonNode source, String cookie,
                                     Filters filters, Download download) throws Exception {
        String word = source.path("word").asText("");
        String order = source.path("order").asText("date_d");
        String mode = source.path("mode").asText("all");
        String sMode = source.path("sMode").asText("s_tag");
        PageSupplier pages = novel
                ? p -> pixivFetchService.discoverSearchNovelIdsPage(word, order, mode, sMode, p, cookie)
                : p -> pixivFetchService.discoverSearchArtworkIdsPage(word, order, mode, sMode, p, cookie);
        java.util.function.LongPredicate already = novel ? novelDatabase::hasNovel : pixivDatabase::hasArtwork;
        WorkDispatcher dispatcher = novel
                ? (id, workId) -> dispatchNovel(id, workId, cookie, filters, download)
                : (id, workId) -> dispatchArtwork(id, workId, cookie, filters, download);
        return runIncrementalSearch(task.id(), pages, already, dispatcher, this::politeDelay);
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

    /** 增量搜索的安全翻页上限，防止结果异常时无限翻页（与系列发现的 200 页上限一致）。 */
    static final int SEARCH_INCREMENTAL_MAX_PAGES = 200;

    /**
     * 增量搜索的纯逻辑（package-private + static，便于单测）：逐页取 ID，命中第一个「已下载」即停止整轮翻页
     * （搜索按 date_d 最新在前 → 命中即代表其后皆旧作）；某页为空 / null 视为无更多结果而停止；
     * 单作品失败隔离（仅记日志、继续），鉴权失效上抛。返回派发的新下载数。
     */
    static int runIncrementalSearch(long taskId, PageSupplier pages,
                                    java.util.function.LongPredicate alreadyDownloaded,
                                    WorkDispatcher dispatcher, Runnable politeDelay) throws Exception {
        int dispatched = 0;
        for (int p = 1; p <= SEARCH_INCREMENTAL_MAX_PAGES; p++) {
            List<String> ids = pages.get(p);
            if (ids == null || ids.isEmpty()) break; // 无更多结果
            for (String id : ids) {
                long workId;
                try {
                    workId = Long.parseLong(id);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (alreadyDownloaded.test(workId)) {
                    return dispatched; // 命中已下载 → 后面都是旧作，停止整轮
                }
                try {
                    if (dispatcher.dispatch(id, workId)) {
                        dispatched++;
                    }
                } catch (PixivFetchService.PixivFetchException e) {
                    throw e; // 鉴权失效：让整轮停下
                } catch (Exception e) {
                    log.warn("Scheduled task {} skip work {}: {}", taskId, id, e.getMessage());
                }
                politeDelay.run();
            }
        }
        return dispatched;
    }

    /** 抓取插画元数据、应用筛选，命中则派发下载。返回是否派发。 */
    private boolean dispatchArtwork(String id, long artworkId, String cookie,
                                    Filters filters, Download download) throws Exception {
        PixivFetchService.ArtworkMeta meta = pixivFetchService.fetchArtworkMeta(id, cookie);
        if (!artworkMatches(meta, filters)) {
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

        artworkDownloader.downloadImages(
                artworkId, meta.title(), imageUrls,
                PIXIV_REFERER + "artworks/" + id, other, cookie, null);
        return true;
    }

    /** 抓取小说详情、应用筛选，命中则按快照设置组装并派发小说下载。返回是否派发。 */
    private boolean dispatchNovel(String id, long novelId, String cookie,
                                  Filters filters, Download download) throws Exception {
        PixivFetchService.NovelDetail d = pixivFetchService.fetchNovelDetail(id, cookie);
        if (!novelMatches(d, filters)) {
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

        novelDownloader.download(req, null);
        return true;
    }

    // ── 服务端筛选 ────────────────────────────────────────────────────────────────

    // package-private + static：纯函数，便于单元测试直接调用（见 ScheduleExecutorFilterTest）
    static boolean artworkMatches(PixivFetchService.ArtworkMeta m, Filters f) {
        if (f.r18Only() && m.xRestrict() < 1) return false;
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
        if (f.r18Only() && d.xRestrict() < 1) return false;
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
                f.path("r18Only").asBoolean(false),
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

    /** 任务快照的筛选条件（来自 params_json 的 {@code filters} 段）。 */
    record Filters(boolean r18Only, String aiFilter, List<String> tagsExact, List<String> tagsFuzzy,
                   String typeFilter, Integer pagesMin, Integer pagesMax,
                   Integer wordsMin, Integer wordsMax, Integer bookmarksMin, Integer bookmarksMax) {
    }

    /** 任务快照的下载设置（来自 params_json 的 {@code download} 段）。 */
    record Download(String fileNameTemplate, boolean bookmark, Long collectionId,
                    String novelFormat, boolean novelMerge, String novelMergeFormat) {
    }
}
