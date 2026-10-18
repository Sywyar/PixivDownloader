package top.sywyar.pixivdownload.novel.db;

import top.sywyar.pixivdownload.novel.db.series.NovelSeriesCatalogRow;
import top.sywyar.pixivdownload.novel.db.series.NovelSeriesTagRow;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationCharacter;
import java.util.Collection;
import java.util.List;

/** 小说业务操作；实现通过受限主库契约参与宿主事务。 */
public interface NovelMapper {
    record NovelWorkDetailsRow(long novelId, Integer wordCount, Integer textLength,
            Integer readingTimeSeconds, Integer pageCount, String xLanguage, String coverExt) {}
    record NovelWorkDetailValueRow(long novelId, String value) {}
    record NovelWordCountRow(long novelId, Integer wordCount) {}

    void updateFileBaseName(long id, String name);

    void updateFileNameMaxLength(long id, int length);

    int migrateNovelTimestampsToMillis();

    int migrateNovelCollectionTimestampsToMillis();

    void insertOrReplaceTranslation(long novelId, String langCode, String rawContent, String title, String description, long createdTime);

    void updateTranslationTitle(long novelId, String langCode, String title);

    String findTranslationContent(long novelId, String langCode);

    String findTranslationTitle(long novelId, String langCode);

    String findTranslationDescription(long novelId, String langCode);

    int countTranslation(long novelId, String langCode);

    List<String> findTranslationLangs(long novelId);

    List<String> findSeriesTranslatedLangs(long seriesId);

    void deleteTranslations(long novelId);

    void insertOrReplaceSeriesTitleTranslation(long seriesId, String langCode, String title, String description, long createdTime);

    String findSeriesTitleTranslation(long seriesId, String langCode);

    String findSeriesDescriptionTranslation(long seriesId, String langCode);

    void insertGlossary(NovelGlossaryInsert glossary);

    NovelGlossary findGlossaryById(long id);

    List<NovelGlossary> findAllGlossaries();

    NovelGlossary findGlossaryBySeriesId(long seriesId);

    NovelGlossary findGlossaryByNovelId(long novelId);

    int updateGlossaryName(long id, String name, long updatedTime);

    int touchGlossary(long id, long updatedTime);

    int countGlossaryById(long id);

    void deleteGlossary(long id);

    List<NovelGlossaryEntry> findGlossaryEntries(long glossaryId);

    void deleteGlossaryEntries(long glossaryId);

    void upsertGlossaryEntry(long glossaryId, String source, String langCode, String target, long createdTime);

    void insertGlossaryEntryIfAbsent(long glossaryId, String source, String langCode, String target, long createdTime);

    void insertNarrationCast(NovelNarrationCastInsert cast);

    NovelNarrationCast findNarrationCastById(long id);

    List<NovelNarrationCast> findAllNarrationCasts();

    NovelNarrationCast findNarrationCastBySeriesId(long seriesId);

    NovelNarrationCast findNarrationCastByNovelId(long novelId);

    int touchNarrationCast(long id, long updatedTime);

    int countNarrationCastById(long id);

    List<top.sywyar.pixivdownload.novel.narration.analysis.NarrationCharacter> findNarrationVoices(long castId);

    Integer maxNarrationVoiceId(long castId);

    void insertNarrationVoiceIfAbsent(
            long castId,
            int characterId,
            String name,
            String gender,
            String age,
            String controlInstruction,
            boolean editedByUser,
            long createdTime
    );

    void upsertNarrationVoice(
            long castId,
            int characterId,
            String name,
            String gender,
            String age,
            String controlInstruction,
            boolean editedByUser,
            long createdTime
    );

    int updateNarrationVoiceInstruction(long castId, int characterId, String controlInstruction, boolean editedByUser);

    int updateNarrationVoiceName(long castId, int characterId, String name);

    int updateNarrationVoiceReference(long castId, int characterId, String ext, String text, String source, Long time);

    int clearNarrationVoiceReference(long castId, int characterId);

    NovelNarrationVoiceRef findNarrationVoiceRef(long castId, int characterId);

    List<NovelNarrationVoiceRef> findNarrationVoiceRefs(long castId);

    void upsertNarrationScript(long novelId, String lang, long castId, int segmentSize, long analyzedTime, String scriptJson);

    NovelNarrationScriptRow findNarrationScript(long novelId, String lang);

    void deleteNarrationScripts(long novelId);

    void deleteNarrationScript(long novelId, String lang);

    void createNovelSoftDeleteCleanupTrigger();

    void cleanupExistingDeletedNovelState();

    int migrateNovelSeriesTimestampsToMillis();

    void insertNovelImage(long novelId, String imageId, String ext);

    String findNovelImageExt(long novelId, String imageId);

    List<String> findNovelImageIds(long novelId);

    List<NovelWorkDetailValueRow> findNovelImageIdsByIds(Collection<Long> ids);

    void deleteNovelImages(long novelId);

    NovelRecord findById(long novelId);

    List<NovelWorkDetailsRow> findWorkDetailsByIds(Collection<Long> ids);

    List<NovelWordCountRow> findWordCountsByIds(Collection<Long> ids);

    List<NovelWorkDetailValueRow> findTranslationLangsByIds(Collection<Long> ids);

    List<NovelRecord> findBySeriesId(long seriesId);

    int countActiveById(long novelId);

    int countByTime(long time);

    Long findMaxTime();

    long countAll();

    void insertOrReplace(
            long novelId,
            String title,
            String folder,
            int count,
            String extensions,
            long time,
            Integer xRestrict,
            Boolean isAi,
            Long authorId,
            String description,
            long fileName,
            Long fileAuthorNameId,
            Long seriesId,
            Long seriesOrder,
            Integer wordCount,
            Integer textLength,
            Integer readingTimeSeconds,
            Integer pageCount,
            Boolean isOriginal,
            String xLanguage,
            String rawContent,
            String coverExt
    );

    void updateCoverExt(long novelId, String coverExt);

    void updateUploadTime(long novelId, Long uploadTime);

    void updateExtensions(long novelId, String extensions);

    void updateSeriesInfo(long novelId, Long seriesId, Long seriesOrder);

    List<Long> findIdsMissingSeries();

    List<Long> findIdsMissingAuthor();

    void updateAuthorId(long novelId, long authorId);

    int insertSeriesIfAbsent(long id, String title, Long authorId, long updatedTime);

    int updateSeries(long id, String title, Long authorId, long updatedTime);

    int updateNovelSeriesMetadata(long id, String description, String coverExt, String coverFolder);

    NovelSeries findSeriesById(long id);

    List<NovelSeriesCatalogRow> findNovelSeriesCatalog();

    void insertNovelTag(long novelId, long tagId);

    void deleteNovelTags(long novelId);

    Integer existsTagsForNovel(long novelId);

    void insertNovelSeriesTag(long seriesId, long tagId);

    void deleteNovelSeriesTags(long seriesId);

    List<NovelTagRow> findNovelSeriesTags(long seriesId);

    List<NovelSeriesTagRow> findNovelSeriesTagsBySeriesIds(Collection<Long> seriesIds);

    void deleteAllNovelCollections(long novelId);

    List<NovelDownloadedStatusRow> findDownloadedStatuses(Collection<Long> ids);

    long countNovelsByCollectionId(long collectionId);

    void initializeContentSearch();

    void refreshContentSearch(Long novelId);

    List<Long> searchContent(String term);
}
