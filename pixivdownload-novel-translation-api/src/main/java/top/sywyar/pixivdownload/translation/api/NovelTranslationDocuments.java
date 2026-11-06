package top.sywyar.pixivdownload.translation.api;

import java.util.List;
import java.util.UUID;

/** 小说所有者提供的内容快照与原子译文写入；不暴露数据库模型、文件路径或资源对象。 */
public interface NovelTranslationDocuments {
    String CONTRACT_ID = "novel-translation-contract";
    int CHUNK_CHARS = 32 * 1024;

    enum Field { BODY, TITLE, DESCRIPTION }
    enum Kind { NOVEL, SERIES }
    enum PublicationResult { PUBLISHED, SOURCE_CHANGED, TARGET_CHANGED, CONFLICT }

    /** 空快照表示作品不存在；长度 -1 表示该字段为空值，0 表示空字符串。 */
    record Document(UUID transfer, long id, Long seriesId, int bodyLength, int titleLength,
                    int descriptionLength) {}
    record Term(String source, String langCode, String target) {}
    record TitleReference(String sourceTitle, String translatedTitle) {}
    /** 发布身份跨进程保留；摘要分别绑定原文、原有译文和本次完整输出。 */
    record Publication(UUID id, String sourceDigest, String previousDigest, String outputDigest) {}

    /** 创建稳定的原文快照；调用者必须在 finally 中关闭快照。 */
    Document openSource(Kind kind, long id);
    /** 译文访问使用 TranslationVariant.key()；术语表仍使用纯语言代码。 */
    Document openTranslation(Kind kind, long id, String variantKey);
    /** 按 UTF-16 偏移读取片段；长度不得超过 CHUNK_CHARS。 */
    String read(UUID transfer, Field field, int offset, int length);
    /** 释放读取快照，或放弃尚未提交的写入；允许重复关闭。 */
    void close(UUID transfer);

    /** 只检查用户选中的字段，避免只翻译过标题时误跳过正文。 */
    boolean translated(Kind kind, long id, String variantKey, boolean body, boolean title, boolean description);
    /** 负长度表示保留旧字段；新内容全部送达后才允许提交。 */
    UUID beginWrite(Kind kind, long id, String variantKey, int bodyLength, int titleLength, int descriptionLength);
    void append(UUID transfer, Field field, int offset, String text);
    /** 在小说所有者内一次保存完整译文；不发布部分正文。 */
    void commit(UUID transfer);
    /** 完整内容与收据在小说所有者的同一事务内提交；重复身份不重写译文，冲突明确拒绝。 */
    PublicationResult publish(UUID transfer, Publication publication);
    /** 仅查询发布收据；用于调用确认丢失后的恢复，不读取或重发模型请求。 */
    boolean published(UUID publicationId);
    /** 系列章节按原有阅读顺序分页；每页最多 100 项。 */
    List<Long> seriesNovelIds(long seriesId, int offset, int limit);

    List<Term> glossaryEntries(long glossaryId, int offset, int limit);
    Long defaultGlossary(long novelId);
    void mergeTerms(long glossaryId, String language, List<Term> terms);
    List<TitleReference> titleReferences(long seriesId, String variantKey, int offset, int limit);
    void mergeSeries(long seriesId, String format, String variantKey);
}
