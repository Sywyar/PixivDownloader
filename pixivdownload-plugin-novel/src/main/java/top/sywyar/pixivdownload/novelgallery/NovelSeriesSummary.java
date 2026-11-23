package top.sywyar.pixivdownload.novelgallery;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import top.sywyar.pixivdownload.core.work.model.WorkTag;

import java.util.List;

/** 小说画廊系列目录行；系列装饰字段由小说插件自行补全。 */
public record NovelSeriesSummary(
        long seriesId,
        String title,
        @HttpApiNullable Long authorId,
        @HttpApiNullable String authorName,
        long novelCount,
        @HttpApiNullable String coverExt,
        List<WorkTag> tags
) {
    public NovelSeriesSummary {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
