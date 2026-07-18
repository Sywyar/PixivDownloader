package top.sywyar.pixivdownload.novel.db.series;

/** 小说插件拥有的系列标签批量投影。 */
public record NovelSeriesTagRow(
        long seriesId,
        Long tagId,
        String name,
        String translatedName
) {
}
