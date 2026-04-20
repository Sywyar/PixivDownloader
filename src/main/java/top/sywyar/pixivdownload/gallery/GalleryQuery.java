package top.sywyar.pixivdownload.gallery;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gallery 查询参数。排序维度互斥（单选），筛选维度可组合。
 */
@Data
@Builder
@AllArgsConstructor
public class GalleryQuery {

    /** 合法排序维度。 */
    public static final Set<String> ALLOWED_SORTS = Set.of(
            "date", "artworkId", "imgs", "status", "authorId", "tags");

    /** 合法三态筛选值（用于 AI 筛选）。 */
    public static final Set<String> ALLOWED_TRISTATE = Set.of("any", "yes", "no");

    /** 合法 R18 筛选值：any=全部，r18plus=R-18 或 R-18G，r18=仅 R-18，r18g=仅 R-18G，no=排除 R-18/R-18G；兼容旧值 yes=r18。 */
    public static final Set<String> ALLOWED_R18 = Set.of("any", "yes", "no", "r18", "r18g", "r18plus");

    private int page;
    private int size;
    private String sort;
    private String order;
    private String search;
    private String r18;
    private String ai;
    /** 图片格式白名单（已归一化小写），空表示不限。 */
    private List<String> formats;
    /** 收藏夹 ID 过滤（OR 语义，命中任一收藏夹即纳入），空表示不限。 */
    private List<Long> collectionIds;
    /** 标签 ID 过滤（AND 语义，需同时命中所有标签），空表示不限。 */
    private List<Long> tagIds;

    public static GalleryQuery normalize(Integer page, Integer size, String sort, String order,
                                         String search, String r18, String ai,
                                         List<String> formats, List<Long> collectionIds,
                                         List<Long> tagIds) {
        return GalleryQuery.builder()
                .page(Math.max(0, page == null ? 0 : page))
                .size(clamp(size == null ? 24 : size, 1, 200))
                .sort(normalizeSort(sort))
                .order("asc".equalsIgnoreCase(order) ? "asc" : "desc")
                .search(nullIfBlank(search))
                .r18(normalizeR18(r18))
                .ai(normalizeTristate(ai))
                .formats(formats)
                .collectionIds(collectionIds)
                .tagIds(tagIds)
                .build();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String normalizeSort(String sort) {
        if (sort == null) return "date";
        String lower = sort.trim();
        return ALLOWED_SORTS.contains(lower) ? lower : "date";
    }

    private static String normalizeTristate(String value) {
        if (value == null) return "any";
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_TRISTATE.contains(lower) ? lower : "any";
    }

    private static String normalizeR18(String value) {
        if (value == null) return "any";
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_R18.contains(lower) ? lower : "any";
    }

    private static String nullIfBlank(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
