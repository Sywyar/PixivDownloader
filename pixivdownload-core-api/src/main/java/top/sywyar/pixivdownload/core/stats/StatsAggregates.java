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
     * @param totalArtworks {@code totalArtworks} 对应的值
     * @param totalImages {@code totalImages} 对应的值
     * @param totalMoved 总数移动数量
     * @param totalNovels {@code totalNovels} 对应的值
     * @param totalAuthors {@code totalAuthors} 对应的值
     * @param totalTags 总数标签集合
     * @param totalSeries 总数系列
     */
    public record Overview(long totalArtworks,
                           long totalImages,
                           long totalMoved,
                           long totalNovels,
                           long totalAuthors,
                           long totalTags,
                           long totalSeries) {}

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

    /** {@code month} 形如 {@code "YYYY-MM"}（本地时区）。 */
    public record MonthlyStat(String month, long count) {}
}
