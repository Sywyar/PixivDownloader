package top.sywyar.pixivdownload.novelgallery;

import top.sywyar.pixivdownload.core.work.model.WorkTag;

import java.util.List;

/** 小说画廊系列目录行；系列装饰字段由小说插件自行补全。 */
public record NovelSeriesSummary(
        long seriesId,
        String title,
        Long authorId,
        String authorName,
        long novelCount,
        String coverExt,
        List<WorkTag> tags
) {
    public NovelSeriesSummary {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
