package top.sywyar.pixivdownload.novelgallery;

/**
 * 小说画廊标签筛选项的本地响应投影。
 */
public record NovelTagOption(long tagId, String name, String translatedName, long novelCount) {
}
