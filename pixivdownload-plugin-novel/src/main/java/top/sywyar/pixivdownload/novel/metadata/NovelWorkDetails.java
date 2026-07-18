package top.sywyar.pixivdownload.novel.metadata;

import java.util.List;

/**
 * 小说插件自有的作品详情元数据；正文通过专门的内容读取路径访问，不进入本轻量视图。
 */
public record NovelWorkDetails(
        long novelId,
        Integer wordCount,
        Integer textLength,
        Integer readingTimeSeconds,
        Integer pageCount,
        String xLanguage,
        String coverExt,
        List<String> embeddedImageIds,
        List<String> translatedLanguages) {

    public NovelWorkDetails {
        embeddedImageIds = embeddedImageIds == null ? List.of() : List.copyOf(embeddedImageIds);
        translatedLanguages = translatedLanguages == null ? List.of() : List.copyOf(translatedLanguages);
    }
}
