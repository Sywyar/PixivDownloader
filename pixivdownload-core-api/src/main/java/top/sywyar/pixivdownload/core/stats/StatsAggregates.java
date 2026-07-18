package top.sywyar.pixivdownload.core.stats;

/**
 * {@link StatsQueryStore} 的核心 owned 聚合查询结果模型（纯 JDK record）。
 *
 * <p>与消费者的对外 DTO 刻意分离：核心语义接口不反向依赖调用方模型，
 * 由调用方把这些 record 投影为自己的响应形态。
 */
public final class StatsAggregates {

    private StatsAggregates() {}

    public record Overview(long totalArtworks,
                           long totalImages,
                           long totalMoved,
                           long totalNovels,
                           long totalAuthors,
                           long totalTags,
                           long totalSeries) {}

    public record AuthorStat(long authorId, String name, long count) {}

    public record TagStat(long tagId, String name, String translatedName, long count) {}

    /** {@code month} 形如 {@code "YYYY-MM"}（本地时区）。 */
    public record MonthlyStat(String month, long count) {}
}
