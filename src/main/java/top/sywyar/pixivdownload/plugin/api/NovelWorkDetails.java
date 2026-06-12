package top.sywyar.pixivdownload.plugin.api;

import java.util.List;

/**
 * 小说作品的专属元数据块：仅当 {@link WorkMetadata#workType()} 为 {@link WorkType#NOVEL}
 * 时由 {@link WorkMetadata#novel()} 携带（不变量见 {@link WorkMetadata} 紧凑构造器）。
 *
 * <p>正文（{@code raw_content} 原始 Pixiv markup）是重量级内容，走专门的内容访问路径，
 * 刻意不入本块。
 *
 * @param wordCount           Pixiv 自报字数，历史数据可为 {@code null}
 * @param textLength          正文字符数，可为 {@code null}
 * @param readingTimeSeconds  预计阅读时长（秒），可为 {@code null}
 * @param pageCount           Pixiv 自报页数（小说自身的分页声明，区别于
 *                            {@link WorkMetadata#pageCount()} 的本地文件计数语义），可为 {@code null}
 * @param isOriginal          原创标记，可为 {@code null}
 * @param xLanguage           正文语言代码，可为 {@code null}
 * @param coverExt            封面扩展名，无封面时为 {@code null}
 * @param embeddedImageIds    正文内嵌图片 id 列表（防御性拷贝，不可变）
 * @param translatedLanguages 已存在 AI 译文的语言代码列表（防御性拷贝，不可变）
 */
public record NovelWorkDetails(
        Integer wordCount,
        Integer textLength,
        Integer readingTimeSeconds,
        Integer pageCount,
        Boolean isOriginal,
        String xLanguage,
        String coverExt,
        List<String> embeddedImageIds,
        List<String> translatedLanguages) {

    public NovelWorkDetails {
        embeddedImageIds = embeddedImageIds == null ? List.of() : List.copyOf(embeddedImageIds);
        translatedLanguages = translatedLanguages == null ? List.of() : List.copyOf(translatedLanguages);
    }
}
