package top.sywyar.pixivdownload.core.artwork.download;

/**
 * 下载完成后提交的插画系列观察命令。
 *
 * <p>系列不存在时是否联网补齐由 {@code lookupWhenMissing} 表达；具体异步策略和无系列哨兵由宿主实现隐藏。</p>
 *
 * @param artworkId        插画 id
 * @param lookupWhenMissing 没有系列事实时是否允许联网补齐
 * @param seriesId         已知系列 id，可为 {@code null}
 * @param title            系列标题，可为 {@code null}
 * @param authorId         作者 id，可为 {@code null}
 * @param description      系列简介，可为 {@code null}
 * @param coverUrl         系列封面 URL，可为 {@code null}
 */
public record ArtworkSeriesObservation(
        long artworkId,
        boolean lookupWhenMissing,
        Long seriesId,
        String title,
        Long authorId,
        String description,
        String coverUrl) {
}
