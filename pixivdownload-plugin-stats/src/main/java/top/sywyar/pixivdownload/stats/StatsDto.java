package top.sywyar.pixivdownload.stats;

import java.util.List;

/** 统计仪表盘的显式响应 DTO。 */
public final class StatsDto {

    private StatsDto() {}

    /** 仪表盘聚合响应：总览卡片 + 作者 Top N + 标签 Top N + 按月下载量。 */
    public record Dashboard(Overview overview,
                            List<AuthorStat> topAuthors,
                            List<TagStat> topTags,
                            List<MonthlyStat> monthly) {}

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
