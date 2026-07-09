package top.sywyar.pixivdownload.douyin.db.history;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record DouyinHistoryQuery(
        int offset,
        int limit,
        String sort,
        String order,
        String search,
        List<String> authorIds,
        List<String> requiredMediaTypes
) {

    public DouyinHistoryQuery(int offset, int limit, String sort, String order,
                              String search, List<String> authorIds) {
        this(offset, limit, sort, order, search, authorIds, List.of());
    }

    private static final int DEFAULT_LIMIT = 50;
    private static final Set<String> TITLE_SORTS = Set.of("title");
    private static final Set<String> PUBLISH_TIME_SORTS = Set.of("publishtime", "publish-time", "publish_time");
    private static final Set<String> AUTHOR_NAME_SORTS = Set.of("author", "authorname", "author-name", "author_name");
    private static final Set<String> COLLECTION_ORDER_SORTS = Set.of(
            "collectionorder", "collection-order", "collection_order");

    public DouyinHistoryQuery {
        offset = Math.max(0, offset);
        limit = limit <= 0 ? DEFAULT_LIMIT : limit;
        sort = normalizeSort(sort);
        order = "asc".equals(normalize(order)) ? "asc" : "desc";
        search = blankToNull(search);
        authorIds = normalizeIds(authorIds);
        requiredMediaTypes = normalizeIds(requiredMediaTypes).stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .toList();
    }

    private static String normalizeSort(String value) {
        String normalized = normalize(value);
        if (TITLE_SORTS.contains(normalized)) {
            return "title";
        }
        if (PUBLISH_TIME_SORTS.contains(normalized)) {
            return "publishTime";
        }
        if (AUTHOR_NAME_SORTS.contains(normalized)) {
            return "authorName";
        }
        if (COLLECTION_ORDER_SORTS.contains(normalized)) {
            return "collectionOrder";
        }
        return "time";
    }

    private static List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            String clean = blankToNull(id);
            if (clean != null) {
                out.add(clean);
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(out));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
