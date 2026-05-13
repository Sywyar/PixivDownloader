package top.sywyar.pixivdownload.series;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.common.PixivCoverDownloader;
import top.sywyar.pixivdownload.common.PixivDescriptionHtml;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class MangaSeriesService {

    /**
     * {@code artworks.series_id} 的"无系列"哨兵值。
     * <p>三态约定：
     * <ul>
     *   <li>{@code NULL} —— 还没查询过；ALTER TABLE 迁移后旧数据默认值，回填工具应把这类行扫掉。</li>
     *   <li>{@code 0} —— 查询过但 Pixiv 返回的不是系列作品（永久结论，避免重复请求）。</li>
     *   <li>{@code > 0} —— 实际系列 ID。</li>
     * </ul>
     * 所有读取/筛选 series 的查询都必须额外加 {@code series_id > 0}，否则会把哨兵值误当真实 ID。
     */
    public static final long NO_SERIES_SENTINEL = 0L;

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private final MangaSeriesMapper mangaSeriesMapper;
    private final AuthorService authorService;
    private final PixivDatabase pixivDatabase;
    private final RestTemplate downloadRestTemplate;
    private final TaskScheduler taskScheduler;
    private final AppMessages messages;
    private final DownloadConfig downloadConfig;
    private final PixivCoverDownloader coverDownloader;

    public MangaSeriesService(MangaSeriesMapper mangaSeriesMapper,
                              AuthorService authorService,
                              PixivDatabase pixivDatabase,
                              @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                              @Qualifier("taskScheduler") TaskScheduler taskScheduler,
                              AppMessages messages,
                              DownloadConfig downloadConfig,
                              PixivCoverDownloader coverDownloader) {
        this.mangaSeriesMapper = mangaSeriesMapper;
        this.authorService = authorService;
        this.pixivDatabase = pixivDatabase;
        this.downloadRestTemplate = downloadRestTemplate;
        this.taskScheduler = taskScheduler;
        this.messages = messages;
        this.downloadConfig = downloadConfig;
        this.coverDownloader = coverDownloader;
    }

    @PostConstruct
    public void init() {
        mangaSeriesMapper.createMangaSeriesTable();
        // 幂等迁移：旧库补列；列已存在时 SQLite 会抛 SQLITE_ERROR，吞掉即可。
        try { mangaSeriesMapper.addDescriptionColumn(); } catch (Exception ignored) {}
        try { mangaSeriesMapper.addCoverExtColumn(); } catch (Exception ignored) {}
        try { mangaSeriesMapper.addCoverFolderColumn(); } catch (Exception ignored) {}
        mangaSeriesMapper.migrateSeriesTimestampsToMillis();
    }

    public List<MangaSeries> getAllSeries() {
        return mangaSeriesMapper.findAll();
    }

    public List<MangaSeries> getSeriesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return mangaSeriesMapper.findByIds(ids);
    }

    public List<MangaSeries> getAllSeries(Set<Long> filterIds) {
        if (filterIds == null) return getAllSeries();
        if (filterIds.isEmpty()) return Collections.emptyList();
        return mangaSeriesMapper.findAll().stream()
                .filter(s -> filterIds.contains(s.seriesId()))
                .toList();
    }

    public PagedSeries getPagedSeriesWithArtworks(int page, int size, String search, String sort) {
        return getPagedSeriesWithArtworks(page, size, search, sort, null);
    }

    public PagedSeries getPagedSeriesWithArtworks(int page, int size, String search, String sort,
                                                  Set<Long> filterIds) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        String normalizedSearch = StringUtils.hasText(search) ? "%" + search.trim() + "%" : "%";
        String normalizedSort = "artworks".equals(sort) || "seriesId".equals(sort) ? sort : "title";
        if (filterIds == null) {
            long total = mangaSeriesMapper.countSeriesWithArtworks(normalizedSearch);
            List<MangaSeriesSummary> rows = total == 0
                    ? Collections.emptyList()
                    : mangaSeriesMapper.findSeriesWithArtworks(
                            normalizedSearch, normalizedSort, safeSize, safePage * safeSize);
            int totalPages = (int) Math.ceil((double) total / safeSize);
            return new PagedSeries(rows, total, safePage, safeSize, totalPages);
        }
        if (filterIds.isEmpty()) {
            return new PagedSeries(Collections.emptyList(), 0, safePage, safeSize, 0);
        }
        List<MangaSeriesSummary> all = mangaSeriesMapper.findSeriesWithArtworks(
                normalizedSearch, normalizedSort, Integer.MAX_VALUE, 0);
        List<MangaSeriesSummary> filtered = all.stream()
                .filter(s -> filterIds.contains(s.seriesId()))
                .toList();
        long total = filtered.size();
        int totalPages = (int) Math.ceil((double) total / safeSize);
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new PagedSeries(filtered.subList(from, to), total, safePage, safeSize, totalPages);
    }

    public record PagedSeries(List<MangaSeriesSummary> content, long totalElements,
                              int page, int size, int totalPages) {}

    public MangaSeries getSeries(long seriesId) {
        return mangaSeriesMapper.findById(seriesId);
    }

    public MangaSeriesDetail getSeriesDetail(long seriesId) {
        if (seriesId <= 0) return null;
        return mangaSeriesMapper.findSeriesDetailById(seriesId);
    }

    public void observe(long seriesId, String title, Long authorId) {
        if (seriesId <= 0) return;
        String normalizedTitle = StringUtils.hasText(title) ? title.trim() : null;
        long now = TimestampUtils.nowMillis();

        // 先尝试插入；返回 1 = 新行已建（首次观测），返回 0 = 行已存在（落入合并分支）。
        // 这样并发的两次 observe 不会都看到 existing == null 然后都走 INSERT OR IGNORE 而漏掉 update。
        String initialTitle = normalizedTitle != null ? normalizedTitle : String.valueOf(seriesId);
        int inserted = mangaSeriesMapper.insertIfAbsent(seriesId, initialTitle, authorId, now);
        if (inserted > 0) {
            log.info(messages.getForLog("series.log.observe.first-record", seriesId, initialTitle));
            if (authorId != null && authorId > 0) {
                authorService.observe(authorId, null);
            }
            return;
        }

        MangaSeries existing = mangaSeriesMapper.findById(seriesId);
        if (existing == null) return; // 极端竞态：被并发删除，放弃 update

        // 仅在 title 或 author 真正发生变化时才写库；空 hint 保留原值。
        String desiredTitle = normalizedTitle != null ? normalizedTitle : existing.title();
        Long desiredAuthorId = authorId != null && authorId > 0 ? authorId : existing.authorId();
        if (!desiredTitle.equals(existing.title())
                || (desiredAuthorId != null && !desiredAuthorId.equals(existing.authorId()))) {
            mangaSeriesMapper.updateInfo(seriesId, desiredTitle, desiredAuthorId, now);
            if (authorId != null && authorId > 0
                    && (existing.authorId() == null || !authorId.equals(existing.authorId()))) {
                authorService.observe(authorId, null);
            }
        }
    }

    /**
     * 拉取 Pixiv 漫画系列元数据（标题/简介/封面），落盘封面到
     * {@code {rootFolder}/artwork-series-{seriesId}/cover.{ext}} 并写入 DB。
     * 调用前必须保证 {@code seriesId > 0}。Best-effort：网络失败/封面缺失只清空对应字段。
     * 返回刷新后的 {@link MangaSeries}；series 不存在时返回 {@code null}。
     */
    public MangaSeries refreshFromPixiv(long seriesId, String cookie) {
        if (seriesId <= 0) return null;
        try {
            JsonNode root = fetchJson("https://www.pixiv.net/ajax/series/" + seriesId + "?p=1&lang=zh", cookie);
            if (root == null || root.path("error").asBoolean(false)) {
                log.warn(messages.getForLog("series.log.refresh.failed.response", seriesId, root));
                return mangaSeriesMapper.findById(seriesId);
            }
            JsonNode body = root.path("body");
            JsonNode seriesArr = body.path("illustSeries");
            JsonNode meta = seriesArr.isArray() && !seriesArr.isEmpty() ? seriesArr.get(0) : null;
            if (meta == null) return mangaSeriesMapper.findById(seriesId);

            String title = meta.path("title").asText("").trim();
            String caption = meta.path("caption").asText("");
            Long authorId = parsePositiveLong(meta.path("userId").asText(null));
            String coverUrl = extractCoverUrl(meta);

            // 先持久化 title/author（observe 内部带并发安全的 upsert）
            observe(seriesId, StringUtils.hasText(title) ? title : null, authorId);

            String normalizedDescription = PixivDescriptionHtml.normalizeLinks(caption);
            String coverExt = null;
            String coverFolder = null;
            if (coverUrl != null && !coverUrl.isBlank()) {
                Path coverDir = resolveCoverDir(seriesId);
                coverExt = coverDownloader.download(coverUrl, coverDir, "cover", cookie);
                if (coverExt != null) {
                    coverFolder = coverDir.toString();
                }
            }
            mangaSeriesMapper.updateMetadata(seriesId, normalizedDescription, coverExt, coverFolder);
            return mangaSeriesMapper.findById(seriesId);
        } catch (Exception e) {
            log.warn(messages.getForLog("series.log.refresh.failed.exception", seriesId), e);
            return mangaSeriesMapper.findById(seriesId);
        }
    }

    /**
     * 漫画系列封面磁盘目录：{@code {rootFolder}/artwork-series-{seriesId}}。
     * 始终返回绝对路径，方便落盘后存入 {@code manga_series.cover_folder}。
     */
    public Path resolveCoverDir(long seriesId) {
        return Paths.get(downloadConfig.getRootFolder(), "artwork-series-" + seriesId)
                .toAbsolutePath().normalize();
    }

    private static String extractCoverUrl(JsonNode meta) {
        // illustSeries[0].cover.urls.{original|1200x1200|720x720|240mw}
        JsonNode urls = meta.path("cover").path("urls");
        if (urls.isObject()) {
            for (String key : List.of("original", "1200x1200", "720x720", "480mw", "240mw")) {
                String value = urls.path(key).asText("");
                if (!value.isBlank()) return value;
            }
        }
        // 兜底：少见的扁平字段
        for (String key : List.of("coverImageUrl", "coverImage", "thumbnailUrl")) {
            String value = meta.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return null;
    }

    public void asyncLookupMissingSeries(long artworkId, String cookie) {
        taskScheduler.schedule(() -> lookupMissingSeries(artworkId, cookie), Instant.now());
    }

    void lookupMissingSeries(long artworkId, String cookie) {
        try {
            JsonNode root = fetchJson("https://www.pixiv.net/ajax/illust/" + artworkId, cookie);
            if (root == null || root.path("error").asBoolean(false)) {
                log.warn(messages.getForLog("series.log.lookup.failed.response", artworkId, root));
                return;
            }
            JsonNode body = root.path("body");
            JsonNode nav = body.path("seriesNavData");
            if (nav.isMissingNode() || nav.isNull() || !nav.isObject()) {
                pixivDatabase.updateSeriesInfo(artworkId, NO_SERIES_SENTINEL, NO_SERIES_SENTINEL);
                return;
            }
            long seriesId = nav.path("seriesId").asLong(0);
            long order = nav.path("order").asLong(0);
            String title = nav.path("title").asText("").trim();
            if (seriesId <= 0) {
                pixivDatabase.updateSeriesInfo(artworkId, NO_SERIES_SENTINEL, NO_SERIES_SENTINEL);
                return;
            }
            Long authorId = parsePositiveLong(body.path("userId").asText(null));
            pixivDatabase.updateSeriesInfo(artworkId, seriesId, order);
            observe(seriesId, title, authorId);
        } catch (Exception e) {
            log.warn(messages.getForLog("series.log.lookup.failed.exception", artworkId), e);
        }
    }

    private static Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long n = Long.parseLong(value);
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode fetchJson(String url, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", PIXIV_REFERER);
        headers.set("User-Agent", USER_AGENT);
        if (StringUtils.hasText(cookie)) {
            headers.set("Cookie", cookie);
        }
        ResponseEntity<JsonNode> response = downloadRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        return response.getBody();
    }
}
