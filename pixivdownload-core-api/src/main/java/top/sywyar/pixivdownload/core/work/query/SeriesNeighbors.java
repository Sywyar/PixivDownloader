package top.sywyar.pixivdownload.core.work.query;

/**
 * 系列内相邻作品导航：按 {@code seriesOrder} 数值找最近的上一章 / 下一章（不要求严格相邻）。
 *
 * @param seriesId     系列 id
 * @param seriesTitle  系列标题，系列池缺行时可为 {@code null}
 * @param currentOrder 当前作品在系列内的序号
 * @param prev         上一章（序号小于当前的最大者），无则为 {@code null}
 * @param next         下一章（序号大于当前的最小者），无则为 {@code null}
 */
public record SeriesNeighbors(
        long seriesId,
        String seriesTitle,
        long currentOrder,
        Neighbor prev,
        Neighbor next) {

    /** 相邻作品行。 */
    public record Neighbor(long workId, String title, long seriesOrder) {
    }
}
