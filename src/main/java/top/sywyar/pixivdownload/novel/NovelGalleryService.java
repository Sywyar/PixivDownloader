package top.sywyar.pixivdownload.novel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.gallery.GuestRestriction;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.novel.db.NovelAuthorSummary;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGalleryRepository;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.novel.db.NovelSeriesSummary;
import top.sywyar.pixivdownload.novel.db.NovelTagOption;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class NovelGalleryService {

    private static final int AUTHOR_NAME_BATCH_SIZE = 500;

    private final NovelDatabase novelDatabase;
    private final NovelGalleryRepository novelGalleryRepository;
    private final AuthorService authorService;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    public PagedNovels query(NovelGalleryQuery q) {
        // 简单实现：内存过滤。规模按本地下载量计算可接受；后续可下沉到 SQL。
        Set<Long> idCandidates;
        if (q.collectionIds() != null && !q.collectionIds().isEmpty()) {
            // 多收藏夹取并集（与 pixiv-gallery 收藏夹筛选语义一致：勾选任一即可见）
            idCandidates = new HashSet<>();
            for (Long cid : q.collectionIds()) {
                if (cid == null) continue;
                idCandidates.addAll(novelDatabase.getNovelIdsInCollection(cid));
            }
            if (idCandidates.isEmpty()) {
                return new PagedNovels(List.of(), 0, q.page(), q.size(), 0);
            }
        } else {
            idCandidates = null;
        }
        // 访客可见性下沉到 SQL：把可见 id 集合与 collection 过滤求交集
        if (q.guestSession() != null) {
            Set<Long> visible = novelGalleryRepository.findVisibleNovelIdSet(GuestRestriction.forNovel(q.guestSession()));
            if (idCandidates == null) {
                idCandidates = visible;
            } else {
                idCandidates.retainAll(visible);
            }
            if (idCandidates.isEmpty()) {
                return new PagedNovels(List.of(), 0, q.page(), q.size(), 0);
            }
        }
        String searchType = normalizeSearchType(q.searchType());
        String searchRaw = q.search() == null ? "" : q.search().trim();
        String search = searchRaw.toLowerCase(Locale.ROOT);
        Long searchId = parseLongOrNull(searchRaw);
        // 正文全文检索一次性命中 id 集合（FTS5），避免逐行扫描 raw_content
        Set<Long> contentMatchIds = ("content".equals(searchType) && !searchRaw.isEmpty())
                ? novelDatabase.searchNovelContentIds(searchRaw)
                : null;
        boolean searchUsesAuthorNames = !searchRaw.isEmpty() && usesAuthorNameSearch(searchType);
        Set<Long> searchAuthorIds = searchUsesAuthorNames ? new LinkedHashSet<>() : Set.of();
        List<Long> allIds = novelDatabase.getAllNovelIdsSortedByTimeDesc();
        List<NovelRecord> candidateRecords = new ArrayList<>();
        for (Long id : allIds) {
            if (idCandidates != null && !idCandidates.contains(id)) continue;
            NovelRecord r = novelDatabase.getNovel(id);
            if (r == null) continue;
            candidateRecords.add(r);
            if (searchUsesAuthorNames && r.authorId() != null && r.authorId() > 0) {
                searchAuthorIds.add(r.authorId());
            }
        }
        Map<Long, String> authorNameCache = searchUsesAuthorNames
                ? resolveAuthorNameCache(searchAuthorIds)
                : Map.of();
        List<NovelView> filtered = new ArrayList<>();
        Set<Long> mustTags = nullSafe(q.tagIds());
        Set<Long> notTags = nullSafe(q.notTagIds());
        Set<Long> orTags = nullSafe(q.orTagIds());
        Set<Long> mustAuthors = nullSafe(q.authorIds());
        Set<Long> notAuthors = nullSafe(q.notAuthorIds());
        Set<Long> orAuthors = nullSafe(q.orAuthorIds());
        Set<Long> mustSeries = nullSafe(q.seriesIds());
        Set<Long> notSeries = nullSafe(q.notSeriesIds());
        for (NovelRecord r : candidateRecords) {
            if (!matchAgeFilter(r.xRestrict(), q.r18())) continue;
            if (!matchAiFilter(r.isAi(), q.ai())) continue;
            if (!searchRaw.isEmpty()) {
                if ("content".equals(searchType)) {
                    if (contentMatchIds == null || !contentMatchIds.contains(r.novelId())) continue;
                } else if (!matchNovelSearch(r, searchType, search, searchId, authorNameCache)) {
                    continue;
                }
            }
            if (!matchAuthorFilter(r.authorId(), mustAuthors, notAuthors, orAuthors)) continue;
            if (!matchSeriesFilter(r.seriesId(), mustSeries, notSeries)) continue;
            if (!matchTagFilter(r.novelId(), mustTags, notTags, orTags)) continue;
            filtered.add(toView(r));
        }
        // 排序
        Comparator<NovelView> cmp = switch (q.sort() == null ? "date" : q.sort()) {
            case "novelId" -> Comparator.comparingLong(NovelView::novelId);
            case "wordCount" -> Comparator.comparingInt(v -> v.wordCount() == null ? 0 : v.wordCount());
            case "series" -> Comparator
                    .comparingLong((NovelView v) -> v.seriesId() == null ? Long.MAX_VALUE : v.seriesId())
                    .thenComparingLong(v -> v.seriesOrder() == null ? 0 : v.seriesOrder());
            default -> Comparator.comparingLong(NovelView::time);
        };
        if (!"asc".equalsIgnoreCase(q.order())) {
            cmp = cmp.reversed();
        }
        filtered.sort(cmp);

        int total = filtered.size();
        int from = Math.max(0, q.page() * q.size());
        int to = Math.min(from + q.size(), filtered.size());
        List<NovelView> pageContent = from >= filtered.size() ? List.of() : filtered.subList(from, to);
        int totalPages = (int) Math.ceil((double) total / Math.max(1, q.size()));
        return new PagedNovels(pageContent, total, q.page(), q.size(), totalPages);
    }

    public List<Long> findNovelIds(NovelGalleryQuery q) {
        NovelGalleryQuery all = new NovelGalleryQuery(
                0,
                Integer.MAX_VALUE,
                q.sort(),
                q.order(),
                q.search(),
                q.searchType(),
                q.r18(),
                q.ai(),
                q.collectionIds(),
                q.tagIds(),
                q.notTagIds(),
                q.orTagIds(),
                q.authorIds(),
                q.notAuthorIds(),
                q.orAuthorIds(),
                q.seriesIds(),
                q.notSeriesIds(),
                q.guestSession());
        return query(all).content().stream().map(NovelView::novelId).toList();
    }

    public NovelView find(long novelId) {
        NovelRecord r = novelDatabase.getNovel(novelId);
        return r == null || r.deleted() ? null : toView(r);
    }

    /**
     * 删除单本小说：先删磁盘文件（正文 TXT/HTML/EPUB、封面、内嵌图、独占目录），再清理 DB 派生数据并
     * 标记软删除（{@code novel_tags} / {@code novel_collections} / {@code novel_images} / 译文 / 朗读脚本 / FTS
     * 照旧清理，主行保留并置 {@code deleted = 1}，见 {@link NovelDatabase#markNovelDeleted}）——使下载判重
     * 能识别「已下载过，但被删除」、避免被当作未下载重新下载。系列封面与合订文件属于系列、不在此删除。
     * 小说不存在或已被标记删除时返回 {@code false}。磁盘文件删除失败（被锁定 / 权限不足等）会立即抛出，
     * 不再继续动 DB，避免 DB 与磁盘状态不一致。
     */
    public boolean deleteNovel(long novelId) {
        NovelRecord record = novelDatabase.getNovel(novelId);
        if (record == null || record.deleted()) {
            return false;
        }
        if (!deleteNovelFiles(record)) {
            throw new LocalizedException(HttpStatus.CONFLICT,
                    "novel.delete.file-failed",
                    "小说 {0} 的磁盘文件未能全部删除，已中止数据库清理",
                    novelId);
        }
        novelDatabase.markNovelDeleted(novelId);
        log.info(logMessage("novel.gallery.log.deleted", novelId));
        return true;
    }

    /** 批量删除小说，返回实际删除的数量。 */
    public int deleteNovels(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (Long id : new LinkedHashSet<>(novelIds)) {
            if (id == null) continue;
            try {
                if (deleteNovel(id)) deleted++;
            } catch (Exception e) {
                log.warn(logMessage("novel.gallery.log.delete-failed", id, e.getMessage()));
            }
        }
        return deleted;
    }

    /**
     * 删除小说磁盘文件：每本小说独占 {@code {rootFolder}/novel-{novelId}/} 目录（小说无重定位语义），
     * 因此目录名必须匹配 {@code novel-{novelId}} 才会被递归删除。
     *
     * <p>磁盘边界守卫（避免污染的 folder 把递归删除范围扩大到 root 之外、共享目录或 OS 根）：
     * 解析后的目录必须非空、可解析、非 OS / 驱动盘根、且不等于配置的 {@code download.root-folder} 本身；
     * 同时目录名必须等于 {@code novel-{novelId}} 才视为本小说独占目录。任何一条不满足都不会触碰磁盘，
     * 仅记日志后视为"无需处理"（不算失败，DB 清理仍会继续——polluted folder 行可由管理员据此排查）。
     *
     * @return 文件层清理结果：{@code true} 表示所有尝试的删除都成功（或没有可删的文件 / 被边界守卫跳过），
     *         调用方可继续删 DB 行；{@code false} 表示有文件因锁定 / 权限不足等原因删除失败，
     *         调用方必须中止 DB 清理。
     */
    private boolean deleteNovelFiles(NovelRecord record) {
        String folder = record.folder();
        if (folder == null || folder.isBlank()) {
            return true;
        }
        Path dir;
        try {
            dir = Paths.get(folder).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn(logMessage("novel.gallery.log.directory-invalid", record.novelId(), folder));
            return true;
        }
        if (!Files.isDirectory(dir)) {
            return true;
        }
        if (dir.getNameCount() < 1 || dir.equals(dir.getRoot())) {
            log.warn(logMessage("novel.gallery.log.directory-root-refused", record.novelId(), dir));
            return true;
        }
        try {
            Path downloadRoot = Paths.get(downloadConfig.getRootFolder()).toAbsolutePath().normalize();
            if (dir.equals(downloadRoot)) {
                log.warn(logMessage("novel.gallery.log.directory-root-folder-refused", record.novelId(), dir));
                return true;
            }
        } catch (InvalidPathException ignored) {
            // 解析 download.root-folder 失败仅意味着无法做 root 自身比对，目录名守卫仍然生效。
        }
        Path name = dir.getFileName();
        String expectedName = "novel-" + record.novelId();
        if (name == null || !expectedName.equals(name.toString())) {
            log.warn(logMessage("novel.gallery.log.directory-not-exclusive", record.novelId(), dir, expectedName));
            return true;
        }
        boolean[] allDeleted = {true};
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn(logMessage("novel.gallery.log.delete-file-failed", p));
                    allDeleted[0] = false;
                }
            });
        } catch (IOException e) {
            log.warn(logMessage("novel.gallery.log.clean-directory-failed", record.novelId(), folder));
            return false;
        }
        return allDeleted[0];
    }

    public List<NovelView> bySeries(long seriesId, int limit) {
        if (seriesId <= 0) return List.of();
        List<NovelRecord> rows = novelDatabase.getNovelsBySeriesId(seriesId);
        List<NovelView> out = new ArrayList<>();
        for (NovelRecord r : rows) {
            if (out.size() >= limit) break;
            out.add(toView(r));
        }
        return out;
    }

    public SeriesNeighbors seriesNeighbors(long novelId) {
        NovelRecord current = novelDatabase.getNovel(novelId);
        if (current == null || current.seriesId() == null || current.seriesId() <= 0) {
            return null;
        }
        List<NovelRecord> rows = novelDatabase.getNovelsBySeriesId(current.seriesId());
        NovelRecord prev = null;
        NovelRecord next = null;
        long currentOrder = current.seriesOrder() == null ? -1 : current.seriesOrder();
        for (NovelRecord r : rows) {
            long ord = r.seriesOrder() == null ? -1 : r.seriesOrder();
            if (ord < currentOrder && (prev == null
                    || (prev.seriesOrder() != null && ord > prev.seriesOrder()))) {
                prev = r;
            }
            if (ord > currentOrder && (next == null
                    || (next.seriesOrder() != null && ord < next.seriesOrder()))) {
                next = r;
            }
        }
        NovelSeries series = novelDatabase.getSeries(current.seriesId());
        String seriesTitle = series == null ? null : series.title();
        return new SeriesNeighbors(
                current.seriesId(), seriesTitle, current.seriesOrder(),
                prev == null ? null : new NeighborView(prev.novelId(), prev.title(),
                        prev.seriesOrder() == null ? 0 : prev.seriesOrder()),
                next == null ? null : new NeighborView(next.novelId(), next.title(),
                        next.seriesOrder() == null ? 0 : next.seriesOrder())
        );
    }

    public NovelView toView(NovelRecord r) {
        String authorName = null;
        if (r.authorId() != null && r.authorId() > 0) {
            try {
                authorName = authorService.getAuthorNames(List.of(r.authorId())).get(r.authorId());
            } catch (Exception e) { log.debug("Failed to resolve author name for authorId={}", r.authorId(), e); }
        }
        List<TagDto> tags = novelDatabase.getNovelTags(r.novelId());
        return new NovelView(
                r.novelId(),
                r.title(),
                r.folder(),
                r.extensions(),
                r.time(),
                r.xRestrict(),
                r.isAi(),
                r.authorId(),
                authorName,
                r.description(),
                r.seriesId(),
                r.seriesOrder(),
                r.wordCount(),
                r.textLength(),
                r.readingTimeSeconds(),
                r.pageCount(),
                r.isOriginal(),
                r.xLanguage(),
                tags,
                r.coverExt(),
                novelDatabase.getNovelImageIds(r.novelId()),
                novelDatabase.getTranslationLangs(r.novelId())
        );
    }

    private boolean usesAuthorNameSearch(String searchType) {
        return "all".equals(searchType) || "author".equals(searchType);
    }

    private Map<Long, String> resolveAuthorNameCache(Set<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, String> out = new HashMap<>(authorIds.size());
            List<Long> ids = new ArrayList<>(authorIds);
            for (int from = 0; from < ids.size(); from += AUTHOR_NAME_BATCH_SIZE) {
                int to = Math.min(from + AUTHOR_NAME_BATCH_SIZE, ids.size());
                Map<Long, String> names = authorService.getAuthorNames(ids.subList(from, to));
                names.forEach((id, name) -> {
                    if (id != null && name != null) {
                        out.put(id, name.toLowerCase(Locale.ROOT));
                    }
                });
            }
            return out;
        } catch (Exception e) {
            log.debug("Failed to resolve novel author names for search", e);
            return Map.of();
        }
    }

    private boolean matchNovelSearch(NovelRecord r, String searchType, String searchLower,
                                     Long searchId, Map<Long, String> authorNameCache) {
        return switch (searchType) {
            case "title" -> r.title() != null && r.title().toLowerCase(Locale.ROOT).contains(searchLower);
            case "author" -> resolveAuthorNameLower(r.authorId(), authorNameCache).contains(searchLower);
            case "desc" -> r.description() != null
                    && r.description().toLowerCase(Locale.ROOT).contains(searchLower);
            case "id" -> searchId != null && searchId == r.novelId();
            case "authorId" -> searchId != null && r.authorId() != null
                    && searchId.equals(r.authorId());
            case "tag" -> matchNovelTag(r.novelId(), searchLower, false);
            case "tagExact" -> matchNovelTag(r.novelId(), searchLower, true);
            default -> (r.title() != null && r.title().toLowerCase(Locale.ROOT).contains(searchLower))
                    || resolveAuthorNameLower(r.authorId(), authorNameCache).contains(searchLower);
        };
    }

    private boolean matchNovelTag(long novelId, String searchLower, boolean exact) {
        for (TagDto tag : novelDatabase.getNovelTags(novelId)) {
            String name = tag.getName() == null ? "" : tag.getName().toLowerCase(Locale.ROOT);
            String translated = tag.getTranslatedName() == null
                    ? "" : tag.getTranslatedName().toLowerCase(Locale.ROOT);
            if (exact) {
                if (name.equals(searchLower) || (!translated.isEmpty() && translated.equals(searchLower))) {
                    return true;
                }
            } else if (name.contains(searchLower) || translated.contains(searchLower)) {
                return true;
            }
        }
        return false;
    }

    private String resolveAuthorNameLower(Long authorId, Map<Long, String> cache) {
        if (authorId == null || authorId <= 0) return "";
        return cache.getOrDefault(authorId, "");
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean matchAgeFilter(Integer xRestrict, String filter) {
        int x = xRestrict == null ? 0 : xRestrict;
        if (filter == null || "any".equalsIgnoreCase(filter)) return true;
        return switch (filter.toLowerCase(Locale.ROOT)) {
            case "no", "sfw" -> x == 0;
            case "yes", "r18plus" -> x >= 1;
            case "r18" -> x == 1;
            case "r18g" -> x == 2;
            default -> true;
        };
    }

    private static boolean matchAiFilter(Boolean isAi, String filter) {
        boolean ai = Boolean.TRUE.equals(isAi);
        if (filter == null || "any".equalsIgnoreCase(filter)) return true;
        return switch (filter.toLowerCase(Locale.ROOT)) {
            case "yes", "only" -> ai;
            case "no", "exclude" -> !ai;
            default -> true;
        };
    }

    private static Set<Long> nullSafe(Set<Long> s) {
        return s == null ? Set.of() : s;
    }

    private static boolean matchAuthorFilter(Long authorId, Set<Long> must, Set<Long> not, Set<Long> or) {
        long a = authorId == null ? 0L : authorId;
        if (!must.isEmpty() && !must.contains(a)) return false;
        if (!not.isEmpty() && not.contains(a)) return false;
        if (!or.isEmpty() && !or.contains(a)) return false;
        return true;
    }

    private static boolean matchSeriesFilter(Long seriesId, Set<Long> must, Set<Long> not) {
        long s = seriesId == null ? 0L : seriesId;
        if (!must.isEmpty() && !must.contains(s)) return false;
        if (!not.isEmpty() && not.contains(s)) return false;
        return true;
    }

    private boolean matchTagFilter(long novelId, Set<Long> must, Set<Long> not, Set<Long> or) {
        if (must.isEmpty() && not.isEmpty() && or.isEmpty()) return true;
        Set<Long> ownedTagIds = new HashSet<>();
        for (var t : novelDatabase.getNovelTags(novelId)) {
            if (t.getTagId() != null) ownedTagIds.add(t.getTagId());
        }
        for (Long m : must) if (!ownedTagIds.contains(m)) return false;
        for (Long n : not) if (ownedTagIds.contains(n)) return false;
        if (!or.isEmpty()) {
            boolean any = false;
            for (Long o : or) if (ownedTagIds.contains(o)) { any = true; break; }
            if (!any) return false;
        }
        return true;
    }

    public PagedAuthors getPagedAuthorsWithNovels(int page, int size, String search, String sort) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        String s = (search == null || search.isBlank()) ? "%" : "%" + search.trim() + "%";
        String normalizedSort = "novels".equals(sort) || "authorId".equals(sort) ? sort : "name";
        long total = novelDatabase.countAuthorsWithNovels(s);
        List<NovelAuthorSummary> rows = total == 0
                ? List.of()
                : novelDatabase.findAuthorsWithNovels(s, normalizedSort, safeSize, safePage * safeSize);
        int totalPages = (int) Math.ceil((double) total / safeSize);
        return new PagedAuthors(rows, total, safePage, safeSize, totalPages);
    }

    public PagedAuthors getPagedAuthorsWithNovels(int page, int size, String search, String sort,
                                                  GuestInviteSession session) {
        if (session == null) {
            return getPagedAuthorsWithNovels(page, size, search, sort);
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<NovelAuthorSummary> rawCounts = novelGalleryRepository
                .findVisibleNovelAuthorCounts(GuestRestriction.forNovel(session));
        Set<Long> authorIds = new HashSet<>();
        for (NovelAuthorSummary item : rawCounts) {
            authorIds.add(item.authorId());
        }
        Map<Long, String> names = authorService.getAuthorNames(authorIds);
        List<NovelAuthorSummary> rows = rawCounts.stream()
                .map(item -> new NovelAuthorSummary(item.authorId(),
                        names.getOrDefault(item.authorId(), String.valueOf(item.authorId())),
                        item.novelCount()))
                .filter(item -> term.isEmpty()
                        || String.valueOf(item.authorId()).contains(term)
                        || (item.name() != null && item.name().toLowerCase(Locale.ROOT).contains(term)))
                .sorted(authorSummaryComparator(sort))
                .toList();
        return pageAuthors(rows, safePage, safeSize);
    }

    public PagedSeries getPagedSeriesWithNovels(int page, int size, String search, String sort) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        String s = (search == null || search.isBlank()) ? "%" : "%" + search.trim() + "%";
        String normalizedSort = "novels".equals(sort) || "seriesId".equals(sort) ? sort : "title";
        long total = novelDatabase.countSeriesWithNovels(s);
        List<NovelSeriesSummary> rows = total == 0
                ? List.of()
                : novelDatabase.findSeriesWithNovels(s, normalizedSort, safeSize, safePage * safeSize);
        int totalPages = (int) Math.ceil((double) total / safeSize);
        return new PagedSeries(decorateSeriesRows(rows), total, safePage, safeSize, totalPages);
    }

    public PagedSeries getPagedSeriesWithNovels(int page, int size, String search, String sort,
                                                GuestInviteSession session) {
        if (session == null) {
            return getPagedSeriesWithNovels(page, size, search, sort);
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<NovelSeriesSummary> rawCounts = novelGalleryRepository
                .findVisibleNovelSeriesCounts(GuestRestriction.forNovel(session));
        Set<Long> authorIds = new HashSet<>();
        Map<Long, NovelSeries> seriesById = new LinkedHashMap<>();
        for (NovelSeriesSummary item : rawCounts) {
            NovelSeries series = novelDatabase.getSeries(item.seriesId());
            if (series != null) {
                seriesById.put(item.seriesId(), series);
                if (series.authorId() != null && series.authorId() > 0) {
                    authorIds.add(series.authorId());
                }
            }
        }
        Map<Long, String> authorNames = authorService.getAuthorNames(authorIds);
        List<NovelSeriesSummary> rows = rawCounts.stream()
                .map(item -> {
                    NovelSeries series = seriesById.get(item.seriesId());
                    String title = series == null ? String.valueOf(item.seriesId()) : series.title();
                    Long authorId = series == null ? null : series.authorId();
                    String authorName = authorId == null ? null : authorNames.get(authorId);
                    return new NovelSeriesSummary(item.seriesId(), title, authorId, authorName, item.novelCount());
                })
                .filter(item -> term.isEmpty()
                        || String.valueOf(item.seriesId()).contains(term)
                        || (item.title() != null && item.title().toLowerCase(Locale.ROOT).contains(term))
                        || (item.authorName() != null && item.authorName().toLowerCase(Locale.ROOT).contains(term)))
                .sorted(seriesSummaryComparator(sort))
                .toList();
        return pageSeries(decorateSeriesRows(rows), safePage, safeSize);
    }

    /**
     * 给系列列表行附加封面扩展名与系列标签，供前端在按系列查看的横板卡片上直接渲染。
     * 单页规模有限（≤200），按 seriesId 批量补齐，避免 N+1 查询。
     */
    private List<NovelSeriesSummary> decorateSeriesRows(List<NovelSeriesSummary> rows) {
        if (rows == null || rows.isEmpty()) return rows == null ? List.of() : rows;
        Set<Long> ids = new LinkedHashSet<>();
        for (NovelSeriesSummary item : rows) ids.add(item.seriesId());
        Map<Long, NovelSeries> seriesById = new LinkedHashMap<>();
        for (NovelSeries series : novelDatabase.getSeriesByIds(ids)) {
            seriesById.put(series.seriesId(), series);
        }
        Map<Long, List<TagDto>> tagsBySeries = novelDatabase.getNovelSeriesTagsBatch(ids);
        List<NovelSeriesSummary> out = new ArrayList<>(rows.size());
        for (NovelSeriesSummary item : rows) {
            NovelSeries series = seriesById.get(item.seriesId());
            String coverExt = series == null ? null : series.coverExt();
            List<TagDto> tags = tagsBySeries.getOrDefault(item.seriesId(), List.of());
            out.add(new NovelSeriesSummary(
                    item.seriesId(), item.title(), item.authorId(),
                    item.authorName(), item.novelCount(), coverExt, tags));
        }
        return out;
    }

    public List<NovelTagOption> listTags(String search, int limit) {
        String s = (search == null || search.isBlank()) ? "%" : "%" + search.trim() + "%";
        int safeLimit = Math.min(Math.max(1, limit), 5000);
        return novelDatabase.findTagsForNovels(s, safeLimit);
    }

    public List<NovelTagOption> listTags(String search, int limit, GuestInviteSession session) {
        if (session == null) {
            return listTags(search, limit);
        }
        return novelGalleryRepository.findVisibleNovelTagCounts(
                GuestRestriction.forNovel(session), search, limit);
    }

    private Comparator<NovelAuthorSummary> authorSummaryComparator(String sort) {
        Comparator<NovelAuthorSummary> comparator = switch (sort == null ? "name" : sort) {
            case "novels" -> Comparator.comparingLong(NovelAuthorSummary::novelCount).reversed();
            case "authorId" -> Comparator.comparingLong(NovelAuthorSummary::authorId);
            default -> Comparator.comparing(item -> item.name() == null
                    ? "" : item.name().toLowerCase(Locale.ROOT));
        };
        return comparator.thenComparingLong(NovelAuthorSummary::authorId);
    }

    private PagedAuthors pageAuthors(List<NovelAuthorSummary> rows, int page, int size) {
        int total = rows.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        int totalPages = (int) Math.ceil((double) total / size);
        return new PagedAuthors(rows.subList(from, to), total, page, size, totalPages);
    }

    private Comparator<NovelSeriesSummary> seriesSummaryComparator(String sort) {
        Comparator<NovelSeriesSummary> comparator = switch (sort == null ? "title" : sort) {
            case "novels" -> Comparator.comparingLong(NovelSeriesSummary::novelCount).reversed();
            case "seriesId" -> Comparator.comparingLong(NovelSeriesSummary::seriesId);
            default -> Comparator.comparing(item -> item.title() == null
                    ? "" : item.title().toLowerCase(Locale.ROOT));
        };
        return comparator.thenComparingLong(NovelSeriesSummary::seriesId);
    }

    private PagedSeries pageSeries(List<NovelSeriesSummary> rows, int page, int size) {
        int total = rows.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        int totalPages = (int) Math.ceil((double) total / size);
        return new PagedSeries(rows.subList(from, to), total, page, size, totalPages);
    }

    public record PagedAuthors(List<NovelAuthorSummary> content, long totalElements,
                               int page, int size, int totalPages) {}

    public record PagedSeries(List<NovelSeriesSummary> content, long totalElements,
                              int page, int size, int totalPages) {}

    /** 合法搜索范围，与插画画廊一致。 */
    public static final Set<String> ALLOWED_SEARCH_TYPES = Set.of(
            "all", "title", "author", "id", "authorId", "desc", "tag", "tagExact", "content");

    public static String normalizeSearchType(String value) {
        if (value == null) return "all";
        String trimmed = value.trim();
        return ALLOWED_SEARCH_TYPES.contains(trimmed) ? trimmed : "all";
    }

    public record NovelGalleryQuery(int page, int size, String sort, String order,
                                    String search, String searchType, String r18, String ai,
                                    Set<Long> collectionIds,
                                    Set<Long> tagIds, Set<Long> notTagIds, Set<Long> orTagIds,
                                    Set<Long> authorIds, Set<Long> notAuthorIds, Set<Long> orAuthorIds,
                                    Set<Long> seriesIds, Set<Long> notSeriesIds,
                                    GuestInviteSession guestSession) {
        public NovelGalleryQuery(int page, int size, String sort, String order,
                                 String search, String searchType, String r18, String ai,
                                 Set<Long> collectionIds,
                                 Set<Long> tagIds, Set<Long> notTagIds, Set<Long> orTagIds,
                                 Set<Long> authorIds, Set<Long> notAuthorIds, Set<Long> orAuthorIds,
                                 Set<Long> seriesIds, Set<Long> notSeriesIds) {
            this(page, size, sort, order, search, searchType, r18, ai, collectionIds,
                    tagIds, notTagIds, orTagIds, authorIds, notAuthorIds, orAuthorIds,
                    seriesIds, notSeriesIds, null);
        }

        public NovelGalleryQuery(int page, int size, String sort, String order,
                                 String search, String r18, String ai) {
            this(page, size, sort, order, search, "all", r18, ai, null,
                    null, null, null, null, null, null, null, null, null);
        }
        public NovelGalleryQuery(int page, int size, String sort, String order,
                                 String search, String r18, String ai,
                                 Set<Long> collectionIds) {
            this(page, size, sort, order, search, "all", r18, ai, collectionIds,
                    null, null, null, null, null, null, null, null, null);
        }
    }

    public record PagedNovels(List<NovelView> content, int totalElements, int page, int size,
                              int totalPages) {}

    public record NovelView(
            long novelId,
            String title,
            String folder,
            String extensions,
            long time,
            Integer xRestrict,
            Boolean isAi,
            Long authorId,
            String authorName,
            String description,
            Long seriesId,
            Long seriesOrder,
            Integer wordCount,
            Integer textLength,
            Integer readingTimeSeconds,
            Integer pageCount,
            Boolean isOriginal,
            String xLanguage,
            List<TagDto> tags,
            String coverExt,
            List<String> embeddedImageIds,
            List<String> translatedLanguages
    ) {}

    public record SeriesNeighbors(Long seriesId, String seriesTitle, Long currentOrder,
                                  NeighborView prev, NeighborView next) {}

    public record NeighborView(long novelId, String title, long seriesOrder) {}

}
