package top.sywyar.pixivdownload.core.metadata.novel;

import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.core.metadata.CoreWorkQueryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 宿主小说窄投影的内存过滤实现：只处理标题、作者、描述、标签、分级、AI、系列等跨作品
 * 元数据，不读取正文或 FTS；未知或来源私有搜索类型 fail-closed。非 Spring Bean，由
 * {@link CoreWorkQueryService} 自行组装。
 */
@Slf4j
@RequiredArgsConstructor
public class NovelWorkSearch {

    private static final int AUTHOR_NAME_BATCH_SIZE = 500;

    private final NovelMetadataRepository novelMetadataRepository;
    private final NovelGalleryRepository novelGalleryRepository;
    private final AuthorService authorService;

    /**
     * 按查询条件返回命中的全部 novelId（已按 {@code sort} / {@code order} 排好序，未分页，
     * 软删除行已过滤）；分页数学由调用方完成。
     */
    public List<Long> filteredIds(WorkQuery q, GuestRestriction restriction) {
        Set<Long> idCandidates;
        if (q.collectionIds() != null && !q.collectionIds().isEmpty()) {
            // 多收藏夹取并集（与 pixiv-gallery 收藏夹筛选语义一致：勾选任一即可见）
            idCandidates = new HashSet<>();
            for (Long cid : q.collectionIds()) {
                if (cid == null) continue;
                idCandidates.addAll(novelMetadataRepository.getNovelIdsInCollection(cid));
            }
            if (idCandidates.isEmpty()) {
                return List.of();
            }
        } else {
            idCandidates = null;
        }
        // 访客可见性下沉到 SQL：把可见 id 集合与 collection 过滤求交集
        if (restriction != null) {
            Set<Long> visible = novelGalleryRepository.findVisibleNovelIdSet(restriction);
            if (idCandidates == null) {
                idCandidates = visible;
            } else {
                idCandidates.retainAll(visible);
            }
            if (idCandidates.isEmpty()) {
                return List.of();
            }
        }
        String searchType = q.searchType() == null ? "all" : q.searchType();
        String searchRaw = q.search() == null ? "" : q.search().trim();
        String search = searchRaw.toLowerCase(Locale.ROOT);
        Long searchId = parseLongOrNull(searchRaw);
        boolean searchUsesAuthorNames = !searchRaw.isEmpty() && usesAuthorNameSearch(searchType);
        Set<Long> searchAuthorIds = searchUsesAuthorNames ? new LinkedHashSet<>() : Set.of();
        List<Long> allIds = novelMetadataRepository.getAllNovelIdsSortedByTimeDesc();
        List<NovelMetadataRow> candidateRecords = new ArrayList<>();
        for (Long id : allIds) {
            if (idCandidates != null && !idCandidates.contains(id)) continue;
            NovelMetadataRow r = novelMetadataRepository.getNovel(id);
            if (r == null) continue;
            candidateRecords.add(r);
            if (searchUsesAuthorNames && r.authorId() != null && r.authorId() > 0) {
                searchAuthorIds.add(r.authorId());
            }
        }
        Map<Long, String> authorNameCache = searchUsesAuthorNames
                ? resolveAuthorNameCache(searchAuthorIds)
                : Map.of();
        List<NovelMetadataRow> filtered = new ArrayList<>();
        Set<Long> mustTags = nullSafe(q.tagIds());
        Set<Long> notTags = nullSafe(q.excludedTagIds());
        Set<Long> orTags = nullSafe(q.optionalTagIds());
        Set<Long> mustAuthors = nullSafe(q.authorIds());
        Set<Long> notAuthors = nullSafe(q.excludedAuthorIds());
        Set<Long> orAuthors = nullSafe(q.optionalAuthorIds());
        Set<Long> mustSeries = nullSafe(q.seriesIds());
        Set<Long> notSeries = nullSafe(q.excludedSeriesIds());
        for (NovelMetadataRow r : candidateRecords) {
            if (!matchAgeFilter(r.xRestrict(), q.r18())) continue;
            if (!matchAiFilter(r.isAi(), q.ai())) continue;
            if (!searchRaw.isEmpty()
                    && !matchNovelSearch(r, searchType, search, searchId, authorNameCache)) {
                continue;
            }
            if (!matchAuthorFilter(r.authorId(), mustAuthors, notAuthors, orAuthors)) continue;
            if (!matchSeriesFilter(r.seriesId(), mustSeries, notSeries)) continue;
            if (!matchTagFilter(r.novelId(), mustTags, notTags, orTags)) continue;
            filtered.add(r);
        }
        // 排序
        Comparator<NovelMetadataRow> cmp = switch (q.sort() == null ? "date" : q.sort()) {
            case "novelId" -> Comparator.comparingLong(NovelMetadataRow::novelId);
            case "series" -> Comparator
                    .comparingLong((NovelMetadataRow r) -> r.seriesId() == null ? Long.MAX_VALUE : r.seriesId())
                    .thenComparingLong(r -> r.seriesOrder() == null ? 0 : r.seriesOrder());
            default -> Comparator.comparingLong(NovelMetadataRow::time);
        };
        if (!"asc".equalsIgnoreCase(q.order())) {
            cmp = cmp.reversed();
        }
        filtered.sort(cmp);
        List<Long> out = new ArrayList<>(filtered.size());
        for (NovelMetadataRow r : filtered) {
            out.add(r.novelId());
        }
        return out;
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

    private boolean matchNovelSearch(NovelMetadataRow r, String searchType, String searchLower,
                                     Long searchId, Map<Long, String> authorNameCache) {
        return switch (searchType) {
            case "all" -> (r.title() != null && r.title().toLowerCase(Locale.ROOT).contains(searchLower))
                    || resolveAuthorNameLower(r.authorId(), authorNameCache).contains(searchLower);
            case "title" -> r.title() != null && r.title().toLowerCase(Locale.ROOT).contains(searchLower);
            case "author" -> resolveAuthorNameLower(r.authorId(), authorNameCache).contains(searchLower);
            case "desc" -> r.description() != null
                    && r.description().toLowerCase(Locale.ROOT).contains(searchLower);
            case "id" -> searchId != null && searchId == r.novelId();
            case "authorId" -> searchId != null && r.authorId() != null
                    && searchId.equals(r.authorId());
            case "tag" -> matchNovelTag(r.novelId(), searchLower, false);
            case "tagExact" -> matchNovelTag(r.novelId(), searchLower, true);
            default -> false;
        };
    }

    private boolean matchNovelTag(long novelId, String searchLower, boolean exact) {
        for (TagDto tag : novelMetadataRepository.getNovelTags(novelId)) {
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

    private static Set<Long> nullSafe(List<Long> ids) {
        return ids == null ? Set.of() : new HashSet<>(ids);
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
        for (var t : novelMetadataRepository.getNovelTags(novelId)) {
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
}
