package top.sywyar.pixivdownload.novel.db;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
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

    @Update("CREATE INDEX IF NOT EXISTS idx_novels_author_id ON novels(author_id)")
    void createNovelsAuthorIndex();

    @Update("CREATE INDEX IF NOT EXISTS idx_novels_series_order ON novels(series_id, series_order)")
    void createNovelsSeriesOrderIndex();

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

    /** 幂等迁移：旧库 novel_translations 表补 title 列；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE novel_translations ADD COLUMN title TEXT DEFAULT NULL")
    void addNovelTranslationsTitleColumn();

    /** 幂等迁移：旧库 novel_translations 表补 description 列；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE novel_translations ADD COLUMN description TEXT DEFAULT NULL")
    void addNovelTranslationsDescriptionColumn();

    /** 幂等迁移：旧库 novel_series_title_translations 表补 description 列；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE novel_series_title_translations ADD COLUMN description TEXT DEFAULT NULL")
    void addNovelSeriesTitleTranslationsDescriptionColumn();

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

    // ── AI translations ───────────────────────────────────────────────────────────
    // 每本小说每种语言一行的 AI 译文：raw_content 保留翻译后的原始 Pixiv markup，
    // 供详情页按语言渲染、系列合订生成语言变体，避免重复请求 AI。

    @Update("CREATE TABLE IF NOT EXISTS novel_translations ("
            + "novel_id INTEGER NOT NULL,"
            + "lang_code TEXT NOT NULL,"
            + "raw_content TEXT NOT NULL,"
            + "title TEXT DEFAULT NULL,"
            + "description TEXT DEFAULT NULL,"
            + "created_time INTEGER NOT NULL,"
            + "PRIMARY KEY (novel_id, lang_code))")
    void createNovelTranslationsTable();

    @Insert("INSERT INTO novel_translations(novel_id, lang_code, raw_content, title, description, created_time)"
            + " VALUES(#{novelId}, #{langCode}, #{rawContent}, #{title}, #{description}, #{createdTime})"
            + " ON CONFLICT(novel_id, lang_code) DO UPDATE SET"
            + " raw_content = excluded.raw_content,"
            + " title = excluded.title,"
            + " description = excluded.description,"
            + " created_time = excluded.created_time")
    void insertOrReplaceTranslation(@Param("novelId") long novelId,
                                    @Param("langCode") String langCode,
                                    @Param("rawContent") String rawContent,
                                    @Param("title") String title,
                                    @Param("description") String description,
                                    @Param("createdTime") long createdTime);

    /** 单独覆盖译文标题（不动正文），用于内容已译但标题首次成功翻译的回填。 */
    @Update("UPDATE novel_translations SET title = #{title}"
            + " WHERE novel_id = #{novelId} AND lang_code = #{langCode}")
    void updateTranslationTitle(@Param("novelId") long novelId,
                                @Param("langCode") String langCode,
                                @Param("title") String title);

    @Select("SELECT raw_content FROM novel_translations"
            + " WHERE novel_id = #{novelId} AND lang_code = #{langCode}")
    String findTranslationContent(@Param("novelId") long novelId, @Param("langCode") String langCode);

    @Select("SELECT title FROM novel_translations"
            + " WHERE novel_id = #{novelId} AND lang_code = #{langCode}")
    String findTranslationTitle(@Param("novelId") long novelId, @Param("langCode") String langCode);

    @Select("SELECT description FROM novel_translations"
            + " WHERE novel_id = #{novelId} AND lang_code = #{langCode}")
    String findTranslationDescription(@Param("novelId") long novelId, @Param("langCode") String langCode);

    @Select("SELECT COUNT(*) FROM novel_translations"
            + " WHERE novel_id = #{novelId} AND lang_code = #{langCode}")
    int countTranslation(@Param("novelId") long novelId, @Param("langCode") String langCode);

    @Select("SELECT lang_code FROM novel_translations WHERE novel_id = #{novelId} ORDER BY lang_code")
    List<String> findTranslationLangs(@Param("novelId") long novelId);

    @Select("SELECT DISTINCT t.lang_code FROM novel_translations t"
            + " JOIN novels n ON n.novel_id = t.novel_id"
            + " WHERE n.series_id = #{seriesId} AND n.series_id > 0"
            + " ORDER BY t.lang_code")
    List<String> findSeriesTranslatedLangs(@Param("seriesId") long seriesId);

    @Delete("DELETE FROM novel_translations WHERE novel_id = #{novelId}")
    void deleteTranslations(@Param("novelId") long novelId);

    // ── AI series title translations ─────────────────────────────────────────────
    // 系列名按语言独立存储；与 novel_translations 平行，但 series_id 不一定对应已下载小说的 series_id
    // （某一系列尚未下载任何章节时也可只是把系列名翻译出来用于 UI 显示）。

    @Update("CREATE TABLE IF NOT EXISTS novel_series_title_translations ("
            + "series_id INTEGER NOT NULL,"
            + "lang_code TEXT NOT NULL,"
            + "title TEXT NOT NULL,"
            + "description TEXT DEFAULT NULL,"
            + "created_time INTEGER NOT NULL,"
            + "PRIMARY KEY (series_id, lang_code))")
    void createNovelSeriesTitleTranslationsTable();

    @Insert("INSERT INTO novel_series_title_translations(series_id, lang_code, title, description, created_time)"
            + " VALUES(#{seriesId}, #{langCode}, #{title}, #{description}, #{createdTime})"
            + " ON CONFLICT(series_id, lang_code) DO UPDATE SET"
            + " title = excluded.title,"
            + " description = excluded.description,"
            + " created_time = excluded.created_time")
    void insertOrReplaceSeriesTitleTranslation(@Param("seriesId") long seriesId,
                                               @Param("langCode") String langCode,
                                               @Param("title") String title,
                                               @Param("description") String description,
                                               @Param("createdTime") long createdTime);

    @Select("SELECT title FROM novel_series_title_translations"
            + " WHERE series_id = #{seriesId} AND lang_code = #{langCode}")
    String findSeriesTitleTranslation(@Param("seriesId") long seriesId, @Param("langCode") String langCode);

    @Select("SELECT description FROM novel_series_title_translations"
            + " WHERE series_id = #{seriesId} AND lang_code = #{langCode}")
    String findSeriesDescriptionTranslation(@Param("seriesId") long seriesId, @Param("langCode") String langCode);

    // ── AI glossaries（名词映射表）────────────────────────────────────────────────
    // 一张映射表（novel_glossaries）默认绑定到某个系列或某本单独小说（series_id / novel_id 二选一），
    // 也可被任意作品复用；条目（novel_glossary_entries）按 (glossary_id, source, lang_code) 一行，
    // 一表内同一原文可对多种目标语言各有译名。翻译时把条目发给 AI 统一专有名词，AI 返回的新名词自动并入。

    @Update("CREATE TABLE IF NOT EXISTS novel_glossaries ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "name TEXT NOT NULL,"
            + "series_id INTEGER DEFAULT NULL,"
            + "novel_id INTEGER DEFAULT NULL,"
            + "created_time INTEGER NOT NULL,"
            + "updated_time INTEGER NOT NULL)")
    void createNovelGlossariesTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_novel_glossaries_series ON novel_glossaries(series_id)")
    void createNovelGlossariesSeriesIndex();

    @Update("CREATE INDEX IF NOT EXISTS idx_novel_glossaries_novel ON novel_glossaries(novel_id)")
    void createNovelGlossariesNovelIndex();

    @Update("CREATE TABLE IF NOT EXISTS novel_glossary_entries ("
            + "glossary_id INTEGER NOT NULL,"
            + "source TEXT NOT NULL,"
            + "lang_code TEXT NOT NULL,"
            + "target TEXT NOT NULL,"
            + "created_time INTEGER NOT NULL,"
            + "PRIMARY KEY (glossary_id, source, lang_code))")
    void createNovelGlossaryEntriesTable();

    String SELECT_GLOSSARY = "SELECT g.id, g.name,"
            + " g.series_id AS seriesId, g.novel_id AS novelId,"
            + " g.created_time AS createdTime, g.updated_time AS updatedTime,"
            + " COALESCE((SELECT COUNT(*) FROM novel_glossary_entries e WHERE e.glossary_id = g.id), 0) AS entryCount"
            + " FROM novel_glossaries g";

    @Insert("INSERT INTO novel_glossaries(name, series_id, novel_id, created_time, updated_time)"
            + " VALUES(#{name}, #{seriesId}, #{novelId}, #{createdTime}, #{updatedTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertGlossary(NovelGlossaryInsert glossary);

    @Select(SELECT_GLOSSARY + " WHERE g.id = #{id}")
    NovelGlossary findGlossaryById(@Param("id") long id);

    @Select(SELECT_GLOSSARY + " ORDER BY g.updated_time DESC, g.id DESC")
    List<NovelGlossary> findAllGlossaries();

    @Select(SELECT_GLOSSARY + " WHERE g.series_id = #{seriesId} ORDER BY g.id LIMIT 1")
    NovelGlossary findGlossaryBySeriesId(@Param("seriesId") long seriesId);

    @Select(SELECT_GLOSSARY + " WHERE g.novel_id = #{novelId} ORDER BY g.id LIMIT 1")
    NovelGlossary findGlossaryByNovelId(@Param("novelId") long novelId);

    @Update("UPDATE novel_glossaries SET name = #{name}, updated_time = #{updatedTime} WHERE id = #{id}")
    int updateGlossaryName(@Param("id") long id, @Param("name") String name,
                           @Param("updatedTime") long updatedTime);

    @Update("UPDATE novel_glossaries SET updated_time = #{updatedTime} WHERE id = #{id}")
    int touchGlossary(@Param("id") long id, @Param("updatedTime") long updatedTime);

    @Select("SELECT COUNT(*) FROM novel_glossaries WHERE id = #{id}")
    int countGlossaryById(@Param("id") long id);

    @Delete("DELETE FROM novel_glossaries WHERE id = #{id}")
    void deleteGlossary(@Param("id") long id);

    @Select("SELECT source, lang_code AS langCode, target FROM novel_glossary_entries"
            + " WHERE glossary_id = #{glossaryId} ORDER BY lang_code, source")
    List<NovelGlossaryEntry> findGlossaryEntries(@Param("glossaryId") long glossaryId);

    /** 手动编辑：整表替换前先清空旧条目。 */
    @Delete("DELETE FROM novel_glossary_entries WHERE glossary_id = #{glossaryId}")
    void deleteGlossaryEntries(@Param("glossaryId") long glossaryId);

    /** 手动编辑写入：同 (原文, 语言) 直接覆盖译名。 */
    @Insert("INSERT OR REPLACE INTO novel_glossary_entries(glossary_id, source, lang_code, target, created_time)"
            + " VALUES(#{glossaryId}, #{source}, #{langCode}, #{target}, #{createdTime})")
    void upsertGlossaryEntry(@Param("glossaryId") long glossaryId,
                             @Param("source") String source,
                             @Param("langCode") String langCode,
                             @Param("target") String target,
                             @Param("createdTime") long createdTime);

    /** AI 自动并入：已存在的 (原文, 语言) 以已有译名为准、不覆盖。 */
    @Insert("INSERT OR IGNORE INTO novel_glossary_entries(glossary_id, source, lang_code, target, created_time)"
            + " VALUES(#{glossaryId}, #{source}, #{langCode}, #{target}, #{createdTime})")
    void insertGlossaryEntryIfAbsent(@Param("glossaryId") long glossaryId,
                                     @Param("source") String source,
                                     @Param("langCode") String langCode,
                                     @Param("target") String target,
                                     @Param("createdTime") long createdTime);

    // ── 朗读花名册（narration cast）──────────────────────────────────────────────
    // 一份花名册（novel_narration_casts）默认绑定到某个系列或某本单独小说（series_id / novel_id 二选一），
    // 也可被任意作品复用；角色（novel_narration_voices）按 (cast_id, character_id) 一行，character_id 0 恒为旁白。
    // 选角时把已有角色名发给 AI 复用，AI 发现的新角色自动并入（INSERT OR IGNORE，不覆盖已有 / 用户改过的音色）。

    @Update("CREATE TABLE IF NOT EXISTS novel_narration_casts ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "name TEXT NOT NULL,"
            + "series_id INTEGER DEFAULT NULL,"
            + "novel_id INTEGER DEFAULT NULL,"
            + "created_time INTEGER NOT NULL,"
            + "updated_time INTEGER NOT NULL)")
    void createNovelNarrationCastsTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_novel_narration_casts_series ON novel_narration_casts(series_id)")
    void createNovelNarrationCastsSeriesIndex();

    @Update("CREATE INDEX IF NOT EXISTS idx_novel_narration_casts_novel ON novel_narration_casts(novel_id)")
    void createNovelNarrationCastsNovelIndex();

    @Update("CREATE TABLE IF NOT EXISTS novel_narration_voices ("
            + "cast_id INTEGER NOT NULL,"
            + "character_id INTEGER NOT NULL,"
            + "name TEXT NOT NULL,"
            + "gender TEXT,"
            + "age TEXT,"
            + "control_instruction TEXT NOT NULL,"
            + "edited_by_user INTEGER NOT NULL DEFAULT 0,"
            + "ref_audio_ext TEXT DEFAULT NULL,"
            + "ref_audio_text TEXT DEFAULT NULL,"
            + "ref_audio_source TEXT DEFAULT NULL,"
            + "ref_audio_time INTEGER DEFAULT NULL,"
            + "created_time INTEGER NOT NULL,"
            + "PRIMARY KEY (cast_id, character_id))")
    void createNovelNarrationVoicesTable();

    /** 幂等迁移：旧库 novel_narration_voices 表补 edited_by_user 列（0=AI 生成 / 1=用户手改锁定）；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE novel_narration_voices ADD COLUMN edited_by_user INTEGER NOT NULL DEFAULT 0")
    void addNarrationVoiceEditedByUserColumn();

    /** 幂等迁移：旧库 novel_narration_voices 表补参考音列（扩展名 / 转录 / 来源 / 时间）；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE novel_narration_voices ADD COLUMN ref_audio_ext TEXT DEFAULT NULL")
    void addNarrationVoiceRefAudioExtColumn();

    @Update("ALTER TABLE novel_narration_voices ADD COLUMN ref_audio_text TEXT DEFAULT NULL")
    void addNarrationVoiceRefAudioTextColumn();

    @Update("ALTER TABLE novel_narration_voices ADD COLUMN ref_audio_source TEXT DEFAULT NULL")
    void addNarrationVoiceRefAudioSourceColumn();

    @Update("ALTER TABLE novel_narration_voices ADD COLUMN ref_audio_time INTEGER DEFAULT NULL")
    void addNarrationVoiceRefAudioTimeColumn();

    String SELECT_NARRATION_CAST = "SELECT c.id, c.name,"
            + " c.series_id AS seriesId, c.novel_id AS novelId,"
            + " c.created_time AS createdTime, c.updated_time AS updatedTime,"
            + " COALESCE((SELECT COUNT(*) FROM novel_narration_voices v WHERE v.cast_id = c.id), 0) AS voiceCount"
            + " FROM novel_narration_casts c";

    @Insert("INSERT INTO novel_narration_casts(name, series_id, novel_id, created_time, updated_time)"
            + " VALUES(#{name}, #{seriesId}, #{novelId}, #{createdTime}, #{updatedTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertNarrationCast(NovelNarrationCastInsert cast);

    @Select(SELECT_NARRATION_CAST + " WHERE c.id = #{id}")
    NovelNarrationCast findNarrationCastById(@Param("id") long id);

    @Select(SELECT_NARRATION_CAST + " ORDER BY c.updated_time DESC, c.id DESC")
    List<NovelNarrationCast> findAllNarrationCasts();

    @Select(SELECT_NARRATION_CAST + " WHERE c.series_id = #{seriesId} ORDER BY c.id LIMIT 1")
    NovelNarrationCast findNarrationCastBySeriesId(@Param("seriesId") long seriesId);

    @Select(SELECT_NARRATION_CAST + " WHERE c.novel_id = #{novelId} ORDER BY c.id LIMIT 1")
    NovelNarrationCast findNarrationCastByNovelId(@Param("novelId") long novelId);

    @Update("UPDATE novel_narration_casts SET name = #{name}, updated_time = #{updatedTime} WHERE id = #{id}")
    int updateNarrationCastName(@Param("id") long id, @Param("name") String name,
                                @Param("updatedTime") long updatedTime);

    @Update("UPDATE novel_narration_casts SET updated_time = #{updatedTime} WHERE id = #{id}")
    int touchNarrationCast(@Param("id") long id, @Param("updatedTime") long updatedTime);

    @Select("SELECT COUNT(*) FROM novel_narration_casts WHERE id = #{id}")
    int countNarrationCastById(@Param("id") long id);

    @Delete("DELETE FROM novel_narration_casts WHERE id = #{id}")
    void deleteNarrationCast(@Param("id") long id);

    @Select("SELECT character_id AS id, name, gender, age,"
            + " control_instruction AS controlInstruction, (character_id = 0) AS narrator,"
            + " edited_by_user AS editedByUser"
            + " FROM novel_narration_voices WHERE cast_id = #{castId} ORDER BY character_id")
    List<top.sywyar.pixivdownload.ai.narration.NarrationCharacter> findNarrationVoices(@Param("castId") long castId);

    @Select("SELECT MAX(character_id) FROM novel_narration_voices WHERE cast_id = #{castId}")
    Integer maxNarrationVoiceId(@Param("castId") long castId);

    /** AI 自动并入：已存在的 (cast_id, character_id) 不覆盖。{@code editedByUser} 应为 {@code false}（AI 生成）。 */
    @Insert("INSERT OR IGNORE INTO novel_narration_voices"
            + "(cast_id, character_id, name, gender, age, control_instruction, edited_by_user, created_time)"
            + " VALUES(#{castId}, #{characterId}, #{name}, #{gender}, #{age}, #{controlInstruction}, #{editedByUser}, #{createdTime})")
    void insertNarrationVoiceIfAbsent(@Param("castId") long castId, @Param("characterId") int characterId,
                                      @Param("name") String name, @Param("gender") String gender,
                                      @Param("age") String age,
                                      @Param("controlInstruction") String controlInstruction,
                                      @Param("editedByUser") boolean editedByUser,
                                      @Param("createdTime") long createdTime);

    /**
     * 手动编辑写入：插入新角色或覆盖已有角色的<b>音色画像列</b>（name/gender/age/control_instruction/edited_by_user）。
     * 用 {@code ON CONFLICT DO UPDATE} 而非 {@code INSERT OR REPLACE}，因此覆盖时<b>保留</b>该角色既有的参考音元数据
     * （{@code ref_audio_*}）与 {@code created_time}——避免整册替换时把已配参考音的绑定连同行一起抹掉。
     * {@code editedByUser=true} 标记用户锁定。
     */
    @Insert("INSERT INTO novel_narration_voices"
            + "(cast_id, character_id, name, gender, age, control_instruction, edited_by_user, created_time)"
            + " VALUES(#{castId}, #{characterId}, #{name}, #{gender}, #{age}, #{controlInstruction}, #{editedByUser}, #{createdTime})"
            + " ON CONFLICT(cast_id, character_id) DO UPDATE SET"
            + " name = excluded.name, gender = excluded.gender, age = excluded.age,"
            + " control_instruction = excluded.control_instruction, edited_by_user = excluded.edited_by_user")
    void upsertNarrationVoice(@Param("castId") long castId, @Param("characterId") int characterId,
                              @Param("name") String name, @Param("gender") String gender,
                              @Param("age") String age,
                              @Param("controlInstruction") String controlInstruction,
                              @Param("editedByUser") boolean editedByUser,
                              @Param("createdTime") long createdTime);

    /**
     * 刷新某角色的音色画像与编辑来源标记，不动 name/gender/age/created_time。用于：AI 兼容性补充、
     * 对 AI 生成角色自动应用冲突建议（均传 {@code editedByUser=false}），以及用户解决冲突后写回（{@code true}）。
     */
    @Update("UPDATE novel_narration_voices SET control_instruction = #{controlInstruction},"
            + " edited_by_user = #{editedByUser}"
            + " WHERE cast_id = #{castId} AND character_id = #{characterId}")
    int updateNarrationVoiceInstruction(@Param("castId") long castId, @Param("characterId") int characterId,
                                        @Param("controlInstruction") String controlInstruction,
                                        @Param("editedByUser") boolean editedByUser);

    @Delete("DELETE FROM novel_narration_voices WHERE cast_id = #{castId}")
    void deleteNarrationVoices(@Param("castId") long castId);

    /** 删除花名册中单个角色行（整册替换时移除不再保留的角色，连同其参考音元数据）。 */
    @Delete("DELETE FROM novel_narration_voices WHERE cast_id = #{castId} AND character_id = #{characterId}")
    void deleteNarrationVoice(@Param("castId") long castId, @Param("characterId") int characterId);

    /** 设置 / 覆盖某角色的参考音元数据（不动音色画像等其它列）；行不存在则影响 0 行。 */
    @Update("UPDATE novel_narration_voices SET ref_audio_ext = #{ext}, ref_audio_text = #{text},"
            + " ref_audio_source = #{source}, ref_audio_time = #{time}"
            + " WHERE cast_id = #{castId} AND character_id = #{characterId}")
    int updateNarrationVoiceReference(@Param("castId") long castId, @Param("characterId") int characterId,
                                      @Param("ext") String ext, @Param("text") String text,
                                      @Param("source") String source, @Param("time") Long time);

    /** 清空某角色的参考音元数据（删除参考音时）。 */
    @Update("UPDATE novel_narration_voices SET ref_audio_ext = NULL, ref_audio_text = NULL,"
            + " ref_audio_source = NULL, ref_audio_time = NULL"
            + " WHERE cast_id = #{castId} AND character_id = #{characterId}")
    int clearNarrationVoiceReference(@Param("castId") long castId, @Param("characterId") int characterId);

    /** 取某角色的参考音元数据（无参考音时各列为 null）。 */
    @Select("SELECT cast_id AS castId, character_id AS characterId,"
            + " ref_audio_ext AS ext, ref_audio_text AS text,"
            + " ref_audio_source AS source, ref_audio_time AS time"
            + " FROM novel_narration_voices WHERE cast_id = #{castId} AND character_id = #{characterId}")
    NovelNarrationVoiceRef findNarrationVoiceRef(@Param("castId") long castId, @Param("characterId") int characterId);

    /** 取某花名册全部已配参考音的角色元数据（供前端展示每个角色的参考音状态）。 */
    @Select("SELECT cast_id AS castId, character_id AS characterId,"
            + " ref_audio_ext AS ext, ref_audio_text AS text,"
            + " ref_audio_source AS source, ref_audio_time AS time"
            + " FROM novel_narration_voices WHERE cast_id = #{castId} AND ref_audio_ext IS NOT NULL")
    List<NovelNarrationVoiceRef> findNarrationVoiceRefs(@Param("castId") long castId);

    // ── 朗读脚本持久化（novel_narration_scripts）───────────────────────────────────
    // 一本小说每种语言一行的整章逐句朗读脚本（lang ''=原文）。LLM 分析昂贵，逐句归属持久化、重播不重算，
    // 只在用户主动「重新分析」（force）时重算。script_json 不存 controlInstruction —— 合成时按 speaker
    // 从活花名册取基底再合并 delivery，使音色编辑 / 冲突解决即时生效。

    @Update("CREATE TABLE IF NOT EXISTS novel_narration_scripts ("
            + "novel_id INTEGER NOT NULL,"
            + "lang TEXT NOT NULL,"
            + "cast_id INTEGER NOT NULL,"
            + "segment_size INTEGER NOT NULL,"
            + "analyzed_time INTEGER NOT NULL,"
            + "script_json TEXT NOT NULL,"
            + "PRIMARY KEY (novel_id, lang))")
    void createNovelNarrationScriptsTable();

    @Insert("INSERT INTO novel_narration_scripts(novel_id, lang, cast_id, segment_size, analyzed_time, script_json)"
            + " VALUES(#{novelId}, #{lang}, #{castId}, #{segmentSize}, #{analyzedTime}, #{scriptJson})"
            + " ON CONFLICT(novel_id, lang) DO UPDATE SET"
            + " cast_id = excluded.cast_id,"
            + " segment_size = excluded.segment_size,"
            + " analyzed_time = excluded.analyzed_time,"
            + " script_json = excluded.script_json")
    void upsertNarrationScript(@Param("novelId") long novelId, @Param("lang") String lang,
                               @Param("castId") long castId, @Param("segmentSize") int segmentSize,
                               @Param("analyzedTime") long analyzedTime, @Param("scriptJson") String scriptJson);

    @Select("SELECT novel_id AS novelId, lang, cast_id AS castId, segment_size AS segmentSize,"
            + " analyzed_time AS analyzedTime, script_json AS scriptJson"
            + " FROM novel_narration_scripts WHERE novel_id = #{novelId} AND lang = #{lang}")
    NovelNarrationScriptRow findNarrationScript(@Param("novelId") long novelId, @Param("lang") String lang);

    @Delete("DELETE FROM novel_narration_scripts WHERE novel_id = #{novelId}")
    void deleteNarrationScripts(@Param("novelId") long novelId);

    @Delete("DELETE FROM novel_narration_scripts WHERE novel_id = #{novelId} AND lang = #{lang}")
    void deleteNarrationScript(@Param("novelId") long novelId, @Param("lang") String lang);

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
