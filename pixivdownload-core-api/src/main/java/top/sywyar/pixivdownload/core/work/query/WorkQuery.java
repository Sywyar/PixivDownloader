package top.sywyar.pixivdownload.core.work.query;

import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.util.List;

/**
 * 作品列表查询条件。字段语义与取值词汇沿用画廊查询的既有约定（排序维度互斥、
 * 筛选维度可组合），调用方负责传入已归一化的值：
 *
 * <ul>
 *   <li>{@code sort}：插画 {@code date / artworkId / imgs / status / authorId / tags / series}；
 *       小说核心元数据 {@code date / novelId / series}。来源私有排序不得交给核心查询服务，
 *       由所属插件截获并在核心中性筛选结果上完成</li>
 *   <li>{@code order}：{@code asc / desc}</li>
 *   <li>{@code searchType}：{@code all / title / author / id / authorId / desc / tag / tagExact}；
 *       来源私有字段与正文检索词不得进入本查询模型</li>
 *   <li>{@code r18}：{@code any / yes / no / r18 / r18g / r18plus}</li>
 *   <li>{@code ai}：{@code any / yes / no}</li>
 *   <li>{@code formats}：仅插画侧使用（按扩展名过滤），小说侧忽略</li>
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

    /**
     * 创建 {@code WorkQuery} 实例。
     *
     * @param workType 工作类型
     * @param page 页码
     * @param size 大小
     * @param sort 排序
     * @param order 排序值
     * @param search 搜索条件
     * @param searchType 搜索条件类型
     * @param r18 R18 标记
     * @param ai AI
     * @param formats 格式列表
     * @param collectionIds 合集标识集合
     * @param tagIds 标签标识集合
     * @param excludedTagIds 已排除项标签标识集合
     * @param optionalTagIds 可选项标签标识集合
     * @param authorIds 作者标识集合
     * @param excludedAuthorIds 已排除项作者标识集合
     * @param optionalAuthorIds 可选项作者标识集合
     * @param seriesIds 系列标识集合
     * @param excludedSeriesIds 已排除项系列标识集合
     * @param restriction 访问限制
     */
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

    /**
     * 创建并返回 {@code WorkQuery.Builder} 实例。
     *
     * @param workType 工作类型
     * @return 方法返回的 {@code WorkQuery.Builder} 实例
     */
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

        /**
         * 执行页码并返回结果。
         *
         * @param page 页码
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder page(int page) { this.page = page; return this; }
        /**
         * 执行大小并返回结果。
         *
         * @param size 大小
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder size(int size) { this.size = size; return this; }
        /**
         * 执行排序并返回结果。
         *
         * @param sort 排序
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder sort(String sort) { this.sort = sort; return this; }
        /**
         * 执行顺序并返回结果。
         *
         * @param order 排序值
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder order(String order) { this.order = order; return this; }
        /**
         * 查询并返回搜索条件。
         *
         * @param search 搜索条件
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder search(String search) { this.search = search; return this; }
        /**
         * 查询并返回搜索条件类型。
         *
         * @param searchType 搜索条件类型
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder searchType(String searchType) { this.searchType = searchType; return this; }
        /**
         * 执行R18 标记并返回结果。
         *
         * @param r18 R18 标记
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder r18(String r18) { this.r18 = r18; return this; }
        /**
         * 执行AI并返回结果。
         *
         * @param ai AI
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder ai(String ai) { this.ai = ai; return this; }
        /**
         * 查询并返回格式列表。
         *
         * @param formats 格式列表
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder formats(List<String> formats) { this.formats = formats; return this; }
        /**
         * 执行合集标识集合并返回结果。
         *
         * @param collectionIds 合集标识集合
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder collectionIds(List<Long> collectionIds) { this.collectionIds = collectionIds; return this; }
        /**
         * 执行标签标识集合并返回结果。
         *
         * @param tagIds 标签标识集合
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder tagIds(List<Long> tagIds) { this.tagIds = tagIds; return this; }
        /**
         * 执行已排除项标签标识集合并返回结果。
         *
         * @param excludedTagIds 已排除项标签标识集合
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder excludedTagIds(List<Long> excludedTagIds) { this.excludedTagIds = excludedTagIds; return this; }
        /**
         * 执行可选项标签标识集合并返回结果。
         *
         * @param optionalTagIds 可选项标签标识集合
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder optionalTagIds(List<Long> optionalTagIds) { this.optionalTagIds = optionalTagIds; return this; }
        /**
         * 执行作者标识集合并返回结果。
         *
         * @param authorIds 作者标识集合
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder authorIds(List<Long> authorIds) { this.authorIds = authorIds; return this; }
        /**
         * 执行已排除项作者标识集合并返回结果。
         *
         * @param excludedAuthorIds 已排除项作者标识集合
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder excludedAuthorIds(List<Long> excludedAuthorIds) { this.excludedAuthorIds = excludedAuthorIds; return this; }
        /**
         * 执行可选项作者标识集合并返回结果。
         *
         * @param optionalAuthorIds 可选项作者标识集合
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder optionalAuthorIds(List<Long> optionalAuthorIds) { this.optionalAuthorIds = optionalAuthorIds; return this; }
        /**
         * 执行系列标识集合并返回结果。
         *
         * @param seriesIds 系列标识集合
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder seriesIds(List<Long> seriesIds) { this.seriesIds = seriesIds; return this; }
        /**
         * 执行已排除项系列标识集合并返回结果。
         *
         * @param excludedSeriesIds 已排除项系列标识集合
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder excludedSeriesIds(List<Long> excludedSeriesIds) { this.excludedSeriesIds = excludedSeriesIds; return this; }
        /**
         * 执行访问限制并返回结果。
         *
         * @param restriction 访问限制
         * @return 方法返回的 {@code WorkQuery.Builder} 实例
         */
        public Builder restriction(WorkRestriction restriction) { this.restriction = restriction; return this; }

        /**
         * 返回对应值。
         *
         * @return 方法返回的 {@code WorkQuery} 实例
         */
        public WorkQuery build() {
            return new WorkQuery(workType, page, size, sort, order, search, searchType, r18, ai,
                    formats, collectionIds, tagIds, excludedTagIds, optionalTagIds,
                    authorIds, excludedAuthorIds, optionalAuthorIds, seriesIds, excludedSeriesIds,
                    restriction);
        }
    }
}
