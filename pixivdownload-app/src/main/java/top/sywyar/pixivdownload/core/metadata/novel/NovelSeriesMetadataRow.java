package top.sywyar.pixivdownload.core.metadata.novel;

/** 宿主系列目录与跨类型元数据装配所需的最小小说系列投影。 */
public record NovelSeriesMetadataRow(
        long seriesId,
        String title,
        Long authorId,
        String coverExt
) {
}
