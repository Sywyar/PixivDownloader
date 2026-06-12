package top.sywyar.pixivdownload.plugin.api;

import java.util.List;

/**
 * 作品列表查询条件。字段语义与取值词汇沿用画廊查询的既有约定（排序维度互斥、
 * 筛选维度可组合），调用方负责传入已归一化的值：
 *
 * <ul>
 *   <li>{@code sort}：{@code date / artworkId / imgs / status / authorId / tags / series}</li>
 *   <li>{@code order}：{@code asc / desc}</li>
 *   <li>{@code searchType}：{@code all / title / author / id / authorId / desc / tag / tagExact}</li>
 *   <li>{@code r18}：{@code any / yes / no / r18 / r18g / r18plus}</li>
 *   <li>{@code ai}：{@code any / yes / no}</li>
 *   <li>{@code size} 必须 ≥ 1（分页数学在查询侧完成）</li>
 * </ul>
 *
 * <p>各 id 列表为 {@code null} 表示该维度不限；列表语义（必须 AND / 可选 OR / 排除）
 * 与画廊查询一致。{@code restriction} 为访客限制投影，{@code null} 表示无限制。
 */
public record WorkQuery(
        WorkType workType,
        int page,
        int size,
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

    public WorkQuery {
        formats = copyOrNull(formats);
        collectionIds = copyOrNull(collectionIds);
        tagIds = copyOrNull(tagIds);
        excludedTagIds = copyOrNull(excludedTagIds);
        optionalTagIds = copyOrNull(optionalTagIds);
        authorIds = copyOrNull(authorIds);
        excludedAuthorIds = copyOrNull(excludedAuthorIds);
        optionalAuthorIds = copyOrNull(optionalAuthorIds);
        seriesIds = copyOrNull(seriesIds);
        excludedSeriesIds = copyOrNull(excludedSeriesIds);
    }

    private static <T> List<T> copyOrNull(List<T> list) {
        return list == null ? null : List.copyOf(list);
    }

    public static Builder builder(WorkType workType) {
        return new Builder(workType);
    }

    /** 构造助手：默认值与画廊查询归一化结果一致（首页 24 条、time 倒序、不限分级 / AI）。 */
    public static final class Builder {
        private final WorkType workType;
        private int page = 0;
        private int size = 24;
        private String sort = "date";
        private String order = "desc";
        private String search;
        private String searchType = "all";
        private String r18 = "any";
        private String ai = "any";
        private List<String> formats;
        private List<Long> collectionIds;
        private List<Long> tagIds;
        private List<Long> excludedTagIds;
        private List<Long> optionalTagIds;
        private List<Long> authorIds;
        private List<Long> excludedAuthorIds;
        private List<Long> optionalAuthorIds;
        private List<Long> seriesIds;
        private List<Long> excludedSeriesIds;
        private WorkRestriction restriction;

        private Builder(WorkType workType) {
            this.workType = workType;
        }

        public Builder page(int page) { this.page = page; return this; }
        public Builder size(int size) { this.size = size; return this; }
        public Builder sort(String sort) { this.sort = sort; return this; }
        public Builder order(String order) { this.order = order; return this; }
        public Builder search(String search) { this.search = search; return this; }
        public Builder searchType(String searchType) { this.searchType = searchType; return this; }
        public Builder r18(String r18) { this.r18 = r18; return this; }
        public Builder ai(String ai) { this.ai = ai; return this; }
        public Builder formats(List<String> formats) { this.formats = formats; return this; }
        public Builder collectionIds(List<Long> collectionIds) { this.collectionIds = collectionIds; return this; }
        public Builder tagIds(List<Long> tagIds) { this.tagIds = tagIds; return this; }
        public Builder excludedTagIds(List<Long> excludedTagIds) { this.excludedTagIds = excludedTagIds; return this; }
        public Builder optionalTagIds(List<Long> optionalTagIds) { this.optionalTagIds = optionalTagIds; return this; }
        public Builder authorIds(List<Long> authorIds) { this.authorIds = authorIds; return this; }
        public Builder excludedAuthorIds(List<Long> excludedAuthorIds) { this.excludedAuthorIds = excludedAuthorIds; return this; }
        public Builder optionalAuthorIds(List<Long> optionalAuthorIds) { this.optionalAuthorIds = optionalAuthorIds; return this; }
        public Builder seriesIds(List<Long> seriesIds) { this.seriesIds = seriesIds; return this; }
        public Builder excludedSeriesIds(List<Long> excludedSeriesIds) { this.excludedSeriesIds = excludedSeriesIds; return this; }
        public Builder restriction(WorkRestriction restriction) { this.restriction = restriction; return this; }

        public WorkQuery build() {
            return new WorkQuery(workType, page, size, sort, order, search, searchType, r18, ai,
                    formats, collectionIds, tagIds, excludedTagIds, optionalTagIds,
                    authorIds, excludedAuthorIds, optionalAuthorIds, seriesIds, excludedSeriesIds,
                    restriction);
        }
    }
}
