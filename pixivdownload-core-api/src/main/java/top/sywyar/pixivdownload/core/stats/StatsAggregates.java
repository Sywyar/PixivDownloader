package top.sywyar.pixivdownload.core.stats;

/**
 * {@link StatsQueryStore} 的核心 owned 聚合查询结果模型（纯 JDK record）。
 *
 * <p>与 stats 插件的 {@code StatsDto} 刻意分离：核心语义接口不反向依赖插件 DTO，
 * 由 {@code StatsService} 在插件侧把这些核心 record 映射为对外的 {@code StatsDto}
 * （并叠加作者名回退等展示逻辑）。
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
