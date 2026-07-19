package top.sywyar.pixivdownload.gallery.web;

import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把 gallery HTTP / 批量筛选参数归一化为稳定的作品查询契约。
 */
public final class GalleryWorkQueryFactory {

    private static final Set<String> ALLOWED_SORTS = Set.of(
            "date", "artworkId", "imgs", "status", "authorId", "tags", "series");
    private static final Set<String> ALLOWED_TRISTATE = Set.of("any", "yes", "no");
    private static final Set<String> ALLOWED_R18 = Set.of(
            "any", "yes", "no", "r18", "r18g", "r18plus");
    private static final Set<String> ALLOWED_SEARCH_TYPES = Set.of(
            "all", "title", "author", "id", "authorId", "desc", "tag", "tagExact");

    private GalleryWorkQueryFactory() {
    }

    public static WorkQuery create(Integer page,
                                   Integer size,
                                   String sort,
                                   String order,
                                   String search,
                                   String searchType,
                                   String r18,
                                   String ai,
                                   List<String> formats,
                                   List<Long> collectionIds,
                                   List<Long> tagIds,
                                   List<Long> excludedTagIds,
                                   List<Long> optionalTagIds,
                                   List<Long> authorIds,
                                   List<Long> excludedAuthorIds,
                                   List<Long> optionalAuthorIds,
                                   List<Long> seriesIds,
                                   List<Long> excludedSeriesIds,
                                   WorkRestriction restriction) {
        return WorkQuery.builder(WorkType.ARTWORK)
                .page(Math.max(0, page == null ? 0 : page))
                .size(clamp(size == null ? 24 : size, 1, 200))
                .sort(normalizeSort(sort))
                .order("asc".equalsIgnoreCase(order) ? "asc" : "desc")
                .search(nullIfBlank(search))
                .searchType(normalizeSearchType(searchType))
                .r18(normalizeToken(r18, ALLOWED_R18))
                .ai(normalizeToken(ai, ALLOWED_TRISTATE))
                .formats(formats)
                .collectionIds(normalizeIds(collectionIds))
                .tagIds(normalizeIds(tagIds))
                .excludedTagIds(normalizeIds(excludedTagIds))
                .optionalTagIds(normalizeIds(optionalTagIds))
                .authorIds(normalizeIds(authorIds))
                .excludedAuthorIds(normalizeIds(excludedAuthorIds))
                .optionalAuthorIds(normalizeIds(optionalAuthorIds))
                .seriesIds(normalizeIds(seriesIds))
                .excludedSeriesIds(normalizeIds(excludedSeriesIds))
                .restriction(restriction)
                .build();
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        Set<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                normalized.add(id);
            }
        }
        return normalized.isEmpty() ? null : new ArrayList<>(normalized);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String normalizeSort(String sort) {
        if (sort == null) {
            return "date";
        }
        String normalized = sort.trim();
        return ALLOWED_SORTS.contains(normalized) ? normalized : "date";
    }

    private static String normalizeSearchType(String searchType) {
        if (searchType == null) {
            return "all";
        }
        String normalized = searchType.trim();
        return ALLOWED_SEARCH_TYPES.contains(normalized) ? normalized : "all";
    }

    private static String normalizeToken(String value, Set<String> allowed) {
        if (value == null) {
            return "any";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "any";
    }

    private static String nullIfBlank(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
