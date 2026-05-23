package top.sywyar.pixivdownload.novel.db;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import top.sywyar.pixivdownload.download.db.TagDto;

import java.util.Collection;
import java.util.List;

@Mapper
public interface NovelMapper {

    String SELECT_NOVEL = "SELECT novel_id AS novelId, title, folder, count, extensions, time,"
            + " \"R18\" AS xRestrict, is_ai AS isAi, author_id AS authorId, description,"
            + " file_name AS fileName, file_author_name_id AS fileAuthorNameId,"
            + " series_id AS seriesId, series_order AS seriesOrder,"
            + " word_count AS wordCount, text_length AS textLength,"
            + " reading_time_seconds AS readingTimeSeconds, page_count AS pageCount,"
            + " is_original AS isOriginal, x_language AS xLanguage, raw_content AS rawContent,"
            + " cover_ext AS coverExt"
            + " FROM novels";

    @Update("CREATE TABLE IF NOT EXISTS novels ("
            + "novel_id INTEGER PRIMARY KEY,"
            + "title TEXT NOT NULL,"
            + "folder TEXT NOT NULL,"
            + "count INTEGER NOT NULL,"
            + "extensions TEXT NOT NULL,"
            + "time INTEGER NOT NULL UNIQUE,"
            + "\"R18\" INTEGER DEFAULT NULL,"
            + "is_ai INTEGER DEFAULT NULL,"
            + "author_id INTEGER DEFAULT NULL,"
            + "description TEXT DEFAULT NULL,"
            + "file_name INTEGER NOT NULL DEFAULT 1,"
            + "file_author_name_id INTEGER,"
            + "series_id INTEGER DEFAULT NULL,"
            + "series_order INTEGER DEFAULT NULL,"
            + "word_count INTEGER DEFAULT NULL,"
            + "text_length INTEGER DEFAULT NULL,"
            + "reading_time_seconds INTEGER DEFAULT NULL,"
            + "page_count INTEGER DEFAULT NULL,"
            + "is_original INTEGER DEFAULT NULL,"
            + "x_language TEXT DEFAULT NULL,"
            + "raw_content TEXT DEFAULT NULL,"
            + "cover_ext TEXT DEFAULT NULL)")
    void createNovelsTable();

    /** 幂等迁移：旧库为已存在的 novels 表补 cover_ext 列；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE novels ADD COLUMN cover_ext TEXT DEFAULT NULL")
    void addCoverExtColumn();

    @Update("ALTER TABLE novels ADD COLUMN reading_time_seconds INTEGER DEFAULT NULL")
    void addReadingTimeSecondsColumn();

    @Update("UPDATE novels SET time = time * 1000"
            + " WHERE time > 0 AND time < 1000000000000")
    int migrateNovelTimestampsToMillis();

    @Update("CREATE TABLE IF NOT EXISTS novel_series ("
            + "series_id INTEGER PRIMARY KEY,"
            + "title TEXT NOT NULL,"
            + "author_id INTEGER,"
            + "updated_time INTEGER NOT NULL,"
            + "description TEXT DEFAULT NULL,"
            + "cover_ext TEXT DEFAULT NULL,"
            + "cover_folder TEXT DEFAULT NULL)")
    void createNovelSeriesTable();

    /** 幂等迁移：旧库 novel_series 表补 description 列；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE novel_series ADD COLUMN description TEXT DEFAULT NULL")
    void addNovelSeriesDescriptionColumn();

    /** 幂等迁移：旧库 novel_series 表补 cover_ext 列；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE novel_series ADD COLUMN cover_ext TEXT DEFAULT NULL")
    void addNovelSeriesCoverExtColumn();

    /** 幂等迁移：旧库 novel_series 表补 cover_folder 列（落盘封面的绝对目录）；列已存在抛异常吞掉 */
    @Update("ALTER TABLE novel_series ADD COLUMN cover_folder TEXT DEFAULT NULL")
    void addNovelSeriesCoverFolderColumn();

    @Update("CREATE TABLE IF NOT EXISTS novel_tags ("
            + "novel_id INTEGER NOT NULL,"
            + "tag_id INTEGER NOT NULL,"
            + "PRIMARY KEY (novel_id, tag_id))")
    void createNovelTagsTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_novel_tags_tag_id ON novel_tags(tag_id)")
    void createNovelTagsTagIndex();

    @Update("CREATE TABLE IF NOT EXISTS novel_series_tags ("
            + "series_id INTEGER NOT NULL,"
            + "tag_id INTEGER NOT NULL,"
            + "PRIMARY KEY (series_id, tag_id))")
    void createNovelSeriesTagsTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_novel_series_tags_tag_id ON novel_series_tags(tag_id)")
    void createNovelSeriesTagsTagIndex();

    @Update("CREATE TABLE IF NOT EXISTS novel_collections ("
            + "collection_id INTEGER NOT NULL,"
            + "novel_id INTEGER NOT NULL,"
            + "added_time INTEGER NOT NULL,"
            + "PRIMARY KEY (collection_id, novel_id))")
    void createNovelCollectionsTable();

    @Update("UPDATE novel_collections SET added_time = added_time * 1000"
            + " WHERE added_time > 0 AND added_time < 1000000000000")
    int migrateNovelCollectionTimestampsToMillis();

    @Update("CREATE INDEX IF NOT EXISTS idx_novel_collections_novel ON novel_collections(novel_id)")
    void createNovelCollectionsNovelIndex();

    @Update("CREATE TABLE IF NOT EXISTS novel_images ("
            + "novel_id INTEGER NOT NULL,"
            + "image_id TEXT NOT NULL,"
            + "ext TEXT NOT NULL,"
            + "PRIMARY KEY (novel_id, image_id))")
    void createNovelImagesTable();

    // ── Full-text search (FTS5) ──────────────────────────────────────────────────
    // novels_fts 是对 novels.raw_content 的辅助全文索引（同库虚拟表，不落到 rootFolder）。
    // trigram 分词器对中日英混排正文都按 3-gram 子串匹配，rowid 复用 novel_id。

    @Update("CREATE VIRTUAL TABLE IF NOT EXISTS novels_fts USING fts5(content, tokenize='trigram')")
    void createNovelFtsTable();

    /** 把尚未建立索引的小说正文补进 FTS（首次启用本功能或旧库升级时回填）。 */
    @Update("INSERT INTO novels_fts(rowid, content)"
            + " SELECT novel_id, COALESCE(raw_content, '') FROM novels"
            + " WHERE novel_id NOT IN (SELECT rowid FROM novels_fts)")
    void backfillNovelFts();

    @Insert("INSERT INTO novels_fts(rowid, content) VALUES(#{novelId}, #{content})")
    void insertNovelFts(@Param("novelId") long novelId, @Param("content") String content);

    @Delete("DELETE FROM novels_fts WHERE rowid = #{novelId}")
    void deleteNovelFts(@Param("novelId") long novelId);

    /** 正文全文检索：{@code query} 为已转义的 FTS5 phrase，返回命中的 novel_id（= rowid）。 */
    @Select("SELECT rowid FROM novels_fts WHERE novels_fts MATCH #{query}")
    List<Long> searchNovelFtsIds(@Param("query") String query);

    /** 短关键词（trigram 无法索引）回退：直接对 raw_content 做 LIKE 子串扫描。 */
    @Select("SELECT novel_id FROM novels WHERE raw_content LIKE #{like} ESCAPE '\\'")
    List<Long> findNovelIdsByContentLike(@Param("like") String like);

    @Update("UPDATE novel_series SET updated_time = updated_time * 1000"
            + " WHERE updated_time > 0 AND updated_time < 1000000000000")
    int migrateNovelSeriesTimestampsToMillis();

    @Insert("INSERT OR REPLACE INTO novel_images(novel_id, image_id, ext)"
            + " VALUES(#{novelId}, #{imageId}, #{ext})")
    void insertNovelImage(@Param("novelId") long novelId,
                          @Param("imageId") String imageId,
                          @Param("ext") String ext);

    @Select("SELECT ext FROM novel_images WHERE novel_id = #{novelId} AND image_id = #{imageId}")
    String findNovelImageExt(@Param("novelId") long novelId, @Param("imageId") String imageId);

    @Select("SELECT image_id FROM novel_images WHERE novel_id = #{novelId}")
    List<String> findNovelImageIds(@Param("novelId") long novelId);

    @Delete("DELETE FROM novel_images WHERE novel_id = #{novelId}")
    void deleteNovelImages(@Param("novelId") long novelId);

    // ── Novels CRUD ─────────────────────────────────────────────────────────────

    @Select(SELECT_NOVEL + " WHERE novel_id = #{novelId}")
    NovelRecord findById(@Param("novelId") long novelId);

    @Select("SELECT COUNT(*) FROM novels WHERE novel_id = #{novelId}")
    int countById(@Param("novelId") long novelId);

    @Select("SELECT COUNT(*) FROM novels WHERE time = #{time}")
    int countByTime(@Param("time") long time);

    @Select("SELECT MAX(time) FROM novels")
    Long findMaxTime();

    @Select("SELECT COUNT(*) FROM novels")
    long countAll();

    @Select("SELECT novel_id FROM novels ORDER BY time DESC")
    List<Long> findAllIdsSortedByTimeDesc();

    @Insert("INSERT OR REPLACE INTO novels"
            + " (novel_id, title, folder, count, extensions, time, \"R18\", is_ai, author_id, description,"
            + " file_name, file_author_name_id, series_id, series_order,"
            + " word_count, text_length, reading_time_seconds, page_count, is_original, x_language, raw_content, cover_ext)"
            + " VALUES (#{novelId}, #{title}, #{folder}, #{count}, #{extensions}, #{time},"
            + " #{xRestrict}, #{isAi}, #{authorId}, #{description},"
            + " #{fileName}, #{fileAuthorNameId}, #{seriesId}, #{seriesOrder},"
            + " #{wordCount}, #{textLength}, #{readingTimeSeconds}, #{pageCount}, #{isOriginal}, #{xLanguage}, #{rawContent}, #{coverExt})")
    void insertOrReplace(@Param("novelId") long novelId,
                         @Param("title") String title,
                         @Param("folder") String folder,
                         @Param("count") int count,
                         @Param("extensions") String extensions,
                         @Param("time") long time,
                         @Param("xRestrict") Integer xRestrict,
                         @Param("isAi") Boolean isAi,
                         @Param("authorId") Long authorId,
                         @Param("description") String description,
                         @Param("fileName") long fileName,
                         @Param("fileAuthorNameId") Long fileAuthorNameId,
                         @Param("seriesId") Long seriesId,
                         @Param("seriesOrder") Long seriesOrder,
                         @Param("wordCount") Integer wordCount,
                         @Param("textLength") Integer textLength,
                         @Param("readingTimeSeconds") Integer readingTimeSeconds,
                         @Param("pageCount") Integer pageCount,
                         @Param("isOriginal") Boolean isOriginal,
                         @Param("xLanguage") String xLanguage,
                         @Param("rawContent") String rawContent,
                         @Param("coverExt") String coverExt);

    @Update("UPDATE novels SET cover_ext = #{coverExt} WHERE novel_id = #{novelId}")
    void updateCoverExt(@Param("novelId") long novelId, @Param("coverExt") String coverExt);

    @Delete("DELETE FROM novels WHERE novel_id = #{novelId}")
    void deleteById(@Param("novelId") long novelId);

    @Update("UPDATE novels SET extensions = #{extensions} WHERE novel_id = #{novelId}")
    void updateExtensions(@Param("novelId") long novelId, @Param("extensions") String extensions);

    @Update("UPDATE novels SET series_id = #{seriesId}, series_order = #{seriesOrder}"
            + " WHERE novel_id = #{novelId}")
    void updateSeriesInfo(@Param("novelId") long novelId,
                          @Param("seriesId") Long seriesId,
                          @Param("seriesOrder") Long seriesOrder);

    @Select(SELECT_NOVEL + " WHERE series_id = #{seriesId} AND series_id > 0"
            + " ORDER BY series_order ASC, time ASC")
    List<NovelRecord> findBySeriesId(@Param("seriesId") long seriesId);

    @Select("SELECT novel_id FROM novels WHERE series_id IS NULL")
    List<Long> findIdsMissingSeries();

    @Select("SELECT novel_id FROM novels WHERE author_id IS NULL")
    List<Long> findIdsMissingAuthor();

    @Update("UPDATE novels SET author_id = #{authorId} WHERE novel_id = #{novelId}")
    void updateAuthorId(@Param("novelId") long novelId, @Param("authorId") long authorId);

    // ── Novel series ────────────────────────────────────────────────────────────

    @Insert("INSERT OR IGNORE INTO novel_series(series_id, title, author_id, updated_time)"
            + " VALUES(#{id}, #{title}, #{authorId}, #{updatedTime})")
    int insertSeriesIfAbsent(@Param("id") long id,
                             @Param("title") String title,
                             @Param("authorId") Long authorId,
                             @Param("updatedTime") long updatedTime);

    @Update("UPDATE novel_series SET title = #{title}, author_id = #{authorId},"
            + " updated_time = #{updatedTime} WHERE series_id = #{id}")
    int updateSeries(@Param("id") long id,
                     @Param("title") String title,
                     @Param("authorId") Long authorId,
                     @Param("updatedTime") long updatedTime);

    @Update("UPDATE novel_series SET description = #{description},"
            + " cover_ext = #{coverExt}, cover_folder = #{coverFolder}"
            + " WHERE series_id = #{id}")
    int updateNovelSeriesMetadata(@Param("id") long id,
                                  @Param("description") String description,
                                  @Param("coverExt") String coverExt,
                                  @Param("coverFolder") String coverFolder);

    @Select("SELECT series_id AS seriesId, title, author_id AS authorId,"
            + " updated_time AS updatedTime, description, cover_ext AS coverExt,"
            + " cover_folder AS coverFolder"
            + " FROM novel_series WHERE series_id = #{id}")
    NovelSeries findSeriesById(@Param("id") long id);

    @Select("SELECT series_id AS seriesId, title, author_id AS authorId,"
            + " updated_time AS updatedTime, description, cover_ext AS coverExt,"
            + " cover_folder AS coverFolder"
            + " FROM novel_series ORDER BY LOWER(title), series_id")
    List<NovelSeries> findAllSeries();

    @Select({
            "<script>",
            "SELECT series_id AS seriesId, title, author_id AS authorId,",
            " updated_time AS updatedTime, description, cover_ext AS coverExt,",
            " cover_folder AS coverFolder",
            "FROM novel_series",
            "WHERE series_id IN",
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    List<NovelSeries> findSeriesByIds(@Param("ids") Collection<Long> ids);

    // ── Tags ────────────────────────────────────────────────────────────────────

    @Insert("INSERT OR IGNORE INTO novel_tags(novel_id, tag_id) VALUES(#{novelId}, #{tagId})")
    void insertNovelTag(@Param("novelId") long novelId, @Param("tagId") long tagId);

    @Delete("DELETE FROM novel_tags WHERE novel_id = #{novelId}")
    void deleteNovelTags(@Param("novelId") long novelId);

    @Select("SELECT t.tag_id AS tagId, t.name AS name, t.translated_name AS translatedName"
            + " FROM novel_tags nt JOIN tags t ON t.tag_id = nt.tag_id"
            + " WHERE nt.novel_id = #{novelId}"
            + " ORDER BY t.tag_id")
    List<TagDto> findTagsByNovelId(@Param("novelId") long novelId);

    @Select("SELECT 1 FROM novel_tags WHERE novel_id = #{novelId} LIMIT 1")
    Integer existsTagsForNovel(@Param("novelId") long novelId);

    // ── Novel series tags ───────────────────────────────────────────────────────

    @Insert("INSERT OR IGNORE INTO novel_series_tags(series_id, tag_id) VALUES(#{seriesId}, #{tagId})")
    void insertNovelSeriesTag(@Param("seriesId") long seriesId, @Param("tagId") long tagId);

    @Delete("DELETE FROM novel_series_tags WHERE series_id = #{seriesId}")
    void deleteNovelSeriesTags(@Param("seriesId") long seriesId);

    @Select("SELECT t.tag_id AS tagId, t.name AS name, t.translated_name AS translatedName"
            + " FROM novel_series_tags nst JOIN tags t ON t.tag_id = nst.tag_id"
            + " WHERE nst.series_id = #{seriesId}"
            + " ORDER BY t.tag_id")
    List<TagDto> findTagsByNovelSeriesId(@Param("seriesId") long seriesId);

    @Select({
            "<script>",
            "SELECT nst.series_id AS seriesId, t.tag_id AS tagId, t.name AS name,",
            " t.translated_name AS translatedName",
            "FROM novel_series_tags nst JOIN tags t ON t.tag_id = nst.tag_id",
            "WHERE nst.series_id IN",
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
            "ORDER BY nst.series_id, t.tag_id",
            "</script>"
    })
    List<java.util.Map<String, Object>> findTagsByNovelSeriesIds(@Param("ids") Collection<Long> seriesIds);

    // ── Collections ─────────────────────────────────────────────────────────────

    @Insert("INSERT OR IGNORE INTO novel_collections(collection_id, novel_id, added_time)"
            + " VALUES(#{collectionId}, #{novelId}, #{addedTime})")
    int insertNovelCollection(@Param("collectionId") long collectionId,
                              @Param("novelId") long novelId,
                              @Param("addedTime") long addedTime);

    @Delete("DELETE FROM novel_collections WHERE collection_id = #{collectionId} AND novel_id = #{novelId}")
    int deleteNovelCollection(@Param("collectionId") long collectionId, @Param("novelId") long novelId);

    @Delete("DELETE FROM novel_collections WHERE novel_id = #{novelId}")
    void deleteAllNovelCollections(@Param("novelId") long novelId);

    @Select("SELECT collection_id FROM novel_collections WHERE novel_id = #{novelId}")
    List<Long> findCollectionIdsByNovelId(@Param("novelId") long novelId);

    @Select({
            "<script>",
            "SELECT novel_id AS novelId, collection_id AS collectionId FROM novel_collections",
            "WHERE novel_id IN",
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"
    })
    List<java.util.Map<String, Object>> findCollectionLinksByNovels(@Param("ids") Collection<Long> novelIds);

    @Select("SELECT novel_id FROM novel_collections WHERE collection_id = #{collectionId}")
    List<Long> findNovelIdsByCollectionId(@Param("collectionId") long collectionId);

    @Select({
            "<script>",
            "SELECT novel_id FROM novels WHERE novel_id IN",
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"
    })
    List<Long> findExistingNovelIds(@Param("ids") Collection<Long> ids);

    @Select("SELECT COUNT(*) FROM novel_collections WHERE collection_id = #{collectionId}")
    long countNovelsByCollectionId(@Param("collectionId") long collectionId);

    // ── Aggregations for filter panel & by-author view ─────────────────────────

    @Select("SELECT n.author_id AS authorId,"
            + " COALESCE(au.name, CAST(n.author_id AS TEXT)) AS name,"
            + " COUNT(*) AS novelCount"
            + " FROM novels n"
            + " LEFT JOIN authors au ON au.author_id = n.author_id"
            + " WHERE n.author_id IS NOT NULL"
            + " AND (au.name LIKE #{search} OR CAST(n.author_id AS TEXT) LIKE #{search})"
            + " GROUP BY n.author_id, au.name"
            + " ORDER BY"
            + " CASE WHEN #{sort} = 'novels' THEN -COUNT(*) END,"
            + " CASE WHEN #{sort} = 'authorId' THEN n.author_id END,"
            + " CASE WHEN #{sort} NOT IN ('novels','authorId')"
            + "      THEN LOWER(COALESCE(au.name, CAST(n.author_id AS TEXT))) END,"
            + " n.author_id"
            + " LIMIT #{limit} OFFSET #{offset}")
    List<NovelAuthorSummary> findAuthorsWithNovels(@Param("search") String search,
                                                   @Param("sort") String sort,
                                                   @Param("limit") int limit,
                                                   @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM ("
            + " SELECT n.author_id FROM novels n"
            + " LEFT JOIN authors au ON au.author_id = n.author_id"
            + " WHERE n.author_id IS NOT NULL"
            + " AND (au.name LIKE #{search} OR CAST(n.author_id AS TEXT) LIKE #{search})"
            + " GROUP BY n.author_id)")
    long countAuthorsWithNovels(@Param("search") String search);

    @Select("SELECT n.series_id AS seriesId,"
            + " COALESCE(ns.title, CAST(n.series_id AS TEXT)) AS title,"
            + " ns.author_id AS authorId,"
            + " au.name AS authorName,"
            + " COUNT(*) AS novelCount"
            + " FROM novels n"
            + " LEFT JOIN novel_series ns ON ns.series_id = n.series_id"
            + " LEFT JOIN authors au ON au.author_id = ns.author_id"
            + " WHERE n.series_id IS NOT NULL AND n.series_id > 0"
            + " AND (ns.title LIKE #{search} OR au.name LIKE #{search} OR CAST(n.series_id AS TEXT) LIKE #{search})"
            + " GROUP BY n.series_id, ns.title, ns.author_id, au.name"
            + " ORDER BY"
            + " CASE WHEN #{sort} = 'novels' THEN -COUNT(*) END,"
            + " CASE WHEN #{sort} = 'seriesId' THEN n.series_id END,"
            + " CASE WHEN #{sort} NOT IN ('novels','seriesId')"
            + "      THEN LOWER(COALESCE(ns.title, CAST(n.series_id AS TEXT))) END,"
            + " n.series_id"
            + " LIMIT #{limit} OFFSET #{offset}")
    List<NovelSeriesSummary> findSeriesWithNovels(@Param("search") String search,
                                                  @Param("sort") String sort,
                                                  @Param("limit") int limit,
                                                  @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM ("
            + " SELECT n.series_id FROM novels n"
            + " LEFT JOIN novel_series ns ON ns.series_id = n.series_id"
            + " LEFT JOIN authors au ON au.author_id = ns.author_id"
            + " WHERE n.series_id IS NOT NULL AND n.series_id > 0"
            + " AND (ns.title LIKE #{search} OR au.name LIKE #{search} OR CAST(n.series_id AS TEXT) LIKE #{search})"
            + " GROUP BY n.series_id)")
    long countSeriesWithNovels(@Param("search") String search);

    @Select("SELECT t.tag_id AS tagId, t.name AS name, t.translated_name AS translatedName,"
            + " COUNT(*) AS novelCount"
            + " FROM novel_tags nt"
            + " JOIN tags t ON t.tag_id = nt.tag_id"
            + " WHERE (t.name LIKE #{search} OR COALESCE(t.translated_name, '') LIKE #{search})"
            + " GROUP BY t.tag_id, t.name, t.translated_name"
            + " ORDER BY COUNT(*) DESC, LOWER(t.name)"
            + " LIMIT #{limit}")
    List<NovelTagOption> findTagsForNovels(@Param("search") String search,
                                           @Param("limit") int limit);
}
