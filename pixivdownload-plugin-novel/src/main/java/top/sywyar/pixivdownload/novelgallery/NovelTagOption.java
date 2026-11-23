package top.sywyar.pixivdownload.novelgallery;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

/**
 * 小说画廊标签筛选项的本地响应投影。
 */
public record NovelTagOption(long tagId, String name, @HttpApiNullable String translatedName, long novelCount) {
}
