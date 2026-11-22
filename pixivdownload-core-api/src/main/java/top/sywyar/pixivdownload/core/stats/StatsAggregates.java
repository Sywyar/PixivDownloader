package top.sywyar.pixivdownload.core.stats;

/**
 * {@link StatsQueryStore} 的核心 owned 聚合查询结果模型（纯 JDK record）。
 *
 * <p>与消费者的对外 DTO 刻意分离：核心语义接口不反向依赖调用方模型，
 * 由调用方把这些 record 投影为自己的响应形态。
 */
public final class StatsAggregates {

    private StatsAggregates() {}

    /**
     * 创建 {@code StatsAggregates.Overview} 实例。
     *
     * @param totalArtworks 累计成功下载的插画数
     * @param totalImages 累计成功下载的图片页数
     * @param totalMoved 总数移动数量
     * @param totalNovels {@code totalNovels} 对应的值
     * @param totalAuthors {@code totalAuthors} 对应的值
     * @param totalTags 总数标签集合
     * @param libraryArtworks 当前登记且未删除的插画数，包含本地导入
     * @param libraryImages 当前未删除插画登记的总页数
     * @param totalSeries 总数系列
     */
    public record Overview(long totalArtworks,
                           long totalImages,
                           long totalMoved,
                           long totalNovels,
                           long totalAuthors,
                           long totalTags,
                           long totalSeries,
                           long libraryArtworks,
                           long libraryImages) {
        /**
         * 根据累计统计创建概览，当前作品库计数初始为零。
         * @param totalArtworks 累计下载的插画数
         * @param totalImages 累计下载的图片页数
         * @param totalMoved 已移动的作品数
         * @param totalNovels 小说数
         * @param totalAuthors 作者数
         * @param totalTags 标签数
         * @param totalSeries 系列数
         */
        public Overview(long totalArtworks, long totalImages, long totalMoved, long totalNovels,
                        long totalAuthors, long totalTags, long totalSeries) {
            this(totalArtworks, totalImages, totalMoved, totalNovels, totalAuthors, totalTags, totalSeries, 0, 0);
        }
    }

    /**
     * 创建 {@code StatsAggregates.AuthorStat} 实例。
     *
     * @param authorId 作者标识
     * @param name 名称
     * @param count 数量
     */
    public record AuthorStat(long authorId, String name, long count) {}

    /**
     * 创建 {@code StatsAggregates.TagStat} 实例。
     *
     * @param tagId 标签标识
     * @param name 名称
     * @param translatedName 翻译后值名称
     * @param count 数量
     */
    public record TagStat(long tagId, String name, String translatedName, long count) {}

    /**
     * {@code month} 形如 {@code "YYYY-MM"}（本地时区）。
     *
     * @param month 本地时区的月份，格式为 {@code YYYY-MM}
     * @param count 该月的统计数量
     */
    public record MonthlyStat(String month, long count) {}
}
