package top.sywyar.pixivdownload.novel.db;

/** 小说插件 Mapper 的标签查询投影。 */
public record NovelTagRow(
        Long tagId,
        String name,
        String translatedName
) {
}
