package top.sywyar.pixivdownload.novel;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.common.PixivDescriptionHtml;
import top.sywyar.pixivdownload.download.db.TagDto;
import top.sywyar.pixivdownload.novel.db.NovelAuthorSummary;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.novel.db.NovelSeriesSummary;
import top.sywyar.pixivdownload.novel.db.NovelTagOption;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NovelGalleryService {

    private final NovelDatabase novelDatabase;
    private final AuthorService authorService;

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
        List<Long> allIds = novelDatabase.getAllNovelIdsSortedByTimeDesc();
        List<NovelView> filtered = new ArrayList<>();
        String search = q.search() == null ? "" : q.search().toLowerCase(Locale.ROOT);
        Set<Long> mustTags = nullSafe(q.tagIds());
        Set<Long> notTags = nullSafe(q.notTagIds());
        Set<Long> orTags = nullSafe(q.orTagIds());
        Set<Long> mustAuthors = nullSafe(q.authorIds());
        Set<Long> notAuthors = nullSafe(q.notAuthorIds());
        Set<Long> orAuthors = nullSafe(q.orAuthorIds());
        Set<Long> mustSeries = nullSafe(q.seriesIds());
        Set<Long> notSeries = nullSafe(q.notSeriesIds());
        for (Long id : allIds) {
            if (idCandidates != null && !idCandidates.contains(id)) continue;
            NovelRecord r = novelDatabase.getNovel(id);
            if (r == null) continue;
            if (!matchAgeFilter(r.xRestrict(), q.r18())) continue;
            if (!matchAiFilter(r.isAi(), q.ai())) continue;
            if (!search.isEmpty() && !r.title().toLowerCase(Locale.ROOT).contains(search)) continue;
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

    public NovelView find(long novelId) {
        NovelRecord r = novelDatabase.getNovel(novelId);
        return r == null ? null : toView(r);
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
            } catch (Exception ignored) {}
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
                PixivDescriptionHtml.normalizeLinks(r.description()),
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
                novelDatabase.getNovelImageIds(r.novelId())
        );
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
        return new PagedSeries(rows, total, safePage, safeSize, totalPages);
    }

    public List<NovelTagOption> listTags(String search, int limit) {
        String s = (search == null || search.isBlank()) ? "%" : "%" + search.trim() + "%";
        int safeLimit = Math.min(Math.max(1, limit), 5000);
        return novelDatabase.findTagsForNovels(s, safeLimit);
    }

    public record PagedAuthors(List<NovelAuthorSummary> content, long totalElements,
                               int page, int size, int totalPages) {}

    public record PagedSeries(List<NovelSeriesSummary> content, long totalElements,
                              int page, int size, int totalPages) {}

    public record NovelGalleryQuery(int page, int size, String sort, String order,
                                    String search, String r18, String ai,
                                    Set<Long> collectionIds,
                                    Set<Long> tagIds, Set<Long> notTagIds, Set<Long> orTagIds,
                                    Set<Long> authorIds, Set<Long> notAuthorIds, Set<Long> orAuthorIds,
                                    Set<Long> seriesIds, Set<Long> notSeriesIds) {
        public NovelGalleryQuery(int page, int size, String sort, String order,
                                 String search, String r18, String ai) {
            this(page, size, sort, order, search, r18, ai, null,
                    null, null, null, null, null, null, null, null);
        }
        public NovelGalleryQuery(int page, int size, String sort, String order,
                                 String search, String r18, String ai,
                                 Set<Long> collectionIds) {
            this(page, size, sort, order, search, r18, ai, collectionIds,
                    null, null, null, null, null, null, null, null);
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
            List<String> embeddedImageIds
    ) {}

    public record SeriesNeighbors(Long seriesId, String seriesTitle, Long currentOrder,
                                  NeighborView prev, NeighborView next) {}

    public record NeighborView(long novelId, String title, long seriesOrder) {}
}
