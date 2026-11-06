package top.sywyar.pixivdownload.translation.api;

import java.util.List;

/** 可选翻译插件提供的操作；引擎选择属于请求，缺省引擎保持为 ai。 */
public interface NovelTranslationOperations {
    record Request(long novelId, String targetLanguage, int segmentSize, boolean overwrite,
                   String langHint, Long glossaryId, boolean translateBody, boolean translateTitle,
                   boolean translateDescription, String engine) {}
    record SeriesRequest(long seriesId, String targetLanguage, String langHint, Long glossaryId,
                         boolean translateTitle, boolean translateDescription, String engine) {}
    /** langCode 用于语言探测与翻译请求，variantKey 用于读取、导出和内容选择。 */
    record Result(String status, String langCode, String message, boolean truncated, String variantKey) {}
    record SeriesResult(String langCode, String variantKey) {}
    record AutoRequest(long novelId, Long seriesId, String targetLanguage, int segmentSize,
                       boolean mergeAfter, String mergeFormat) {}
    record Status(String phase, long elapsedSeconds, int seriesPending, String langCode,
                  boolean done, boolean failed, String failureReason) {}
    record Engine(String id, String nameKey, boolean available, boolean glossary, boolean freeFormLanguage) {}

    List<Engine> engines();
    Result translate(Request request);
    SeriesResult translateSeries(SeriesRequest request);
    String resolveLanguage(String targetLanguage, String engine);
    void submit(AutoRequest request);
    Status status(long novelId);
}
