package top.sywyar.pixivdownload.gallery;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
            "date", "artworkId", "imgs", "status", "authorId", "tags", "series");

    /** 合法三态筛选值（用于 AI 筛选）。 */
    public static final Set<String> ALLOWED_TRISTATE = Set.of("any", "yes", "no");

    /** 合法 R18 筛选值：any=全部，r18plus=R-18 或 R-18G，r18=仅 R-18，r18g=仅 R-18G，no=排除 R-18/R-18G；兼容旧值 yes=r18。 */
    public static final Set<String> ALLOWED_R18 = Set.of("any", "yes", "no", "r18", "r18g", "r18plus");

    /**
     * 合法搜索范围：all=标题或作者名（默认，旧行为），title=仅标题，author=仅作者名，
     * id=作品 ID（精确），authorId=作者 ID（精确），desc=简介（模糊）。
     */
    public static final Set<String> ALLOWED_SEARCH_TYPES = Set.of(
            "all", "title", "author", "id", "authorId", "desc", "tag", "tagExact");

    private int page;
    private int size;
    private String sort;
    private String order;
    private String search;
    /** 搜索范围，见 {@link #ALLOWED_SEARCH_TYPES}；默认 {@code all}。 */
    @Builder.Default
    private String searchType = "all";
    private String r18;
    private String ai;
    /** 图片格式白名单（已归一化小写），空表示不限。 */
    private List<String> formats;
    /** 收藏夹 ID 过滤（OR 语义，命中任一收藏夹即纳入），空表示不限。 */
    private List<Long> collectionIds;
    /** 必须命中的标签 ID（AND 语义，需要同时命中所有标签）。 */
    private List<Long> tagIds;
    /** 不能命中的标签 ID（命中任一即排除）。 */
    private List<Long> excludedTagIds;
    /** 可选命中的标签 ID（OR 语义，参与“必须作者 OR 可选标签”子句）。 */
    private List<Long> optionalTagIds;
    /** 必须命中的作者 ID（列表内 OR 语义，参与“必须作者 OR 可选标签”子句）。 */
    private List<Long> authorIds;
    /** 不能命中的作者 ID（命中任一即排除）。 */
    private List<Long> excludedAuthorIds;
    /** 可选命中的作者 ID（OR 语义，参与“必须标签 OR 可选作者”子句）。 */
    private List<Long> optionalAuthorIds;
    /** 必须命中的系列 ID（列表内 OR 语义）。 */
    private List<Long> seriesIds;
    /** 不能命中的系列 ID。 */
    private List<Long> excludedSeriesIds;
    /** 访客邀请会话施加的额外限制（年龄分级 + 标签/作者 OR 白名单）；管理员/普通访问为 {@code null}。 */
    private GuestRestriction guestRestriction;

    public static GalleryQuery normalize(Integer page, Integer size, String sort, String order,
                                         String search, String r18, String ai,
                                         List<String> formats, List<Long> collectionIds,
                                         List<Long> tagIds, List<Long> excludedTagIds,
                                         List<Long> optionalTagIds, List<Long> authorIds,
                                         List<Long> excludedAuthorIds, List<Long> optionalAuthorIds,
                                         List<Long> seriesIds, List<Long> excludedSeriesIds) {
        return GalleryQuery.builder()
                .page(Math.max(0, page == null ? 0 : page))
                .size(clamp(size == null ? 24 : size, 1, 200))
                .sort(normalizeSort(sort))
                .order("asc".equalsIgnoreCase(order) ? "asc" : "desc")
                .search(nullIfBlank(search))
                .r18(normalizeR18(r18))
                .ai(normalizeTristate(ai))
                .formats(formats)
                .collectionIds(normalizeIdList(collectionIds))
                .tagIds(normalizeIdList(tagIds))
                .excludedTagIds(normalizeIdList(excludedTagIds))
                .optionalTagIds(normalizeIdList(optionalTagIds))
                .authorIds(normalizeIdList(authorIds))
                .excludedAuthorIds(normalizeIdList(excludedAuthorIds))
                .optionalAuthorIds(normalizeIdList(optionalAuthorIds))
                .seriesIds(normalizeIdList(seriesIds))
                .excludedSeriesIds(normalizeIdList(excludedSeriesIds))
                .build();
    }

    public static GalleryQuery normalize(Integer page, Integer size, String sort, String order,
                                         String search, String r18, String ai,
                                         List<String> formats, List<Long> collectionIds,
                                         List<Long> tagIds, List<Long> excludedTagIds,
                                         List<Long> optionalTagIds, List<Long> authorIds,
                                         List<Long> excludedAuthorIds, List<Long> optionalAuthorIds) {
        return normalize(page, size, sort, order, search, r18, ai, formats, collectionIds,
                tagIds, excludedTagIds, optionalTagIds, authorIds, excludedAuthorIds, optionalAuthorIds,
                null, null);
    }

    private static List<Long> normalizeIdList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        Set<Long> out = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) out.add(id);
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
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

    public static String normalizeSearchType(String value) {
        if (value == null) return "all";
        String trimmed = value.trim();
        return ALLOWED_SEARCH_TYPES.contains(trimmed) ? trimmed : "all";
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
