package top.sywyar.pixivdownload.download.db;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PixivMapper {

    String SELECT_ARTWORK = "SELECT artwork_id, title, folder, count, extensions, time, moved,"
            + " move_folder, move_time, \"R18\" AS is_r18, is_ai, author_id, description FROM artworks";

    // ── DDL ────────────────────────────────────────────────────────────────────

    @Update("CREATE TABLE IF NOT EXISTS artworks ("
            + "artwork_id INTEGER PRIMARY KEY,"
            + "title TEXT NOT NULL,"
            + "folder TEXT NOT NULL,"
            + "count INTEGER NOT NULL,"
            + "extensions TEXT NOT NULL,"
            + "time INTEGER NOT NULL UNIQUE,"
            + "\"R18\" INTEGER DEFAULT NULL,"
            + "is_ai INTEGER DEFAULT NULL,"
            + "author_id INTEGER DEFAULT NULL,"
            + "description TEXT DEFAULT NULL,"
            + "moved INTEGER DEFAULT 0,"
            + "move_folder TEXT,"
            + "move_time INTEGER)")
    void createArtworksTable();

    @Update("CREATE TABLE IF NOT EXISTS statistics ("
            + "id INTEGER PRIMARY KEY CHECK (id = 1),"
            + "total_artworks INTEGER DEFAULT 0,"
            + "total_images INTEGER DEFAULT 0,"
            + "total_moved INTEGER DEFAULT 0)")
    void createStatisticsTable();

    @Insert("INSERT OR IGNORE INTO statistics (id, total_artworks, total_images, total_moved)"
            + " VALUES (1, 0, 0, 0)")
    void initStatistics();

    @Update("CREATE TABLE IF NOT EXISTS tags ("
            + "tag_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "name TEXT NOT NULL UNIQUE,"
            + "translated_name TEXT)")
    void createTagsTable();

    @Update("CREATE TABLE IF NOT EXISTS artwork_tags ("
            + "artwork_id INTEGER NOT NULL,"
            + "tag_id INTEGER NOT NULL,"
            + "PRIMARY KEY (artwork_id, tag_id))")
    void createArtworkTagsTable();

    @Update("CREATE INDEX IF NOT EXISTS idx_artwork_tags_tag_id ON artwork_tags(tag_id)")
    void createArtworkTagsTagIndex();

    /** 幂等迁移：为旧库补 R18 列，列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE artworks ADD COLUMN \"R18\" INTEGER DEFAULT NULL")
    void addR18Column();

    @Update("ALTER TABLE artworks ADD COLUMN is_ai INTEGER DEFAULT NULL")
    void addIsAiColumn();

    @Update("ALTER TABLE artworks ADD COLUMN author_id INTEGER DEFAULT NULL")
    void addAuthorIdColumn();

    @Update("ALTER TABLE artworks ADD COLUMN description TEXT DEFAULT NULL")
    void addDescriptionColumn();

    // ── Artworks ────────────────────────────────────────────────────────────────

    @Select(SELECT_ARTWORK + " WHERE artwork_id = #{artworkId}")
    ArtworkRecord findById(long artworkId);

    @Insert("INSERT OR IGNORE INTO artworks"
            + " (artwork_id, title, folder, count, extensions, time, \"R18\", is_ai, author_id, description)"
            + " VALUES (#{artworkId}, #{title}, #{folder}, #{count}, #{extensions}, #{time}, #{isR18}, #{isAi}, #{authorId}, #{description})")
    void insertOrIgnore(@Param("artworkId") long artworkId,
                        @Param("title") String title,
                        @Param("folder") String folder,
                        @Param("count") int count,
                        @Param("extensions") String extensions,
                        @Param("time") long time,
                        @Param("isR18") Boolean isR18,
                        @Param("isAi") Boolean isAi,
                        @Param("authorId") Long authorId,
                        @Param("description") String description);

    @Select(SELECT_ARTWORK + " WHERE RTRIM(RTRIM(move_folder, '/'), '\\') = #{moveFolder}")
    ArtworkRecord findByNormalizedMoveFolder(String moveFolder);

    @Update("UPDATE artworks SET moved = 1, move_folder = #{movePath}, move_time = #{moveTime}"
            + " WHERE artwork_id = #{artworkId}")
    void updateMove(@Param("artworkId") long artworkId,
                    @Param("movePath") String movePath,
                    @Param("moveTime") long moveTime);

    @Delete("DELETE FROM artworks WHERE artwork_id = #{artworkId}")
    void deleteById(long artworkId);

    @Select("SELECT COUNT(*) FROM artworks WHERE artwork_id = #{artworkId}")
    int countById(long artworkId);

    @Select("SELECT COUNT(*) FROM artworks WHERE time = #{time}")
    int countByTime(long time);

    @Select("SELECT COUNT(*) FROM artworks")
    long countAll();

    @Select("SELECT artwork_id FROM artworks")
    List<Long> findAllIds();

    @Select("SELECT artwork_id FROM artworks ORDER BY time DESC")
    List<Long> findAllIdsSortedByTimeDesc();

    @Select("SELECT artwork_id FROM artworks ORDER BY time DESC LIMIT #{size} OFFSET #{offset}")
    List<Long> findIdsSortedByTimeDescPaged(@Param("size") int size, @Param("offset") int offset);

    @Select("SELECT artwork_id FROM artworks"
            + " ORDER BY COALESCE(author_id, 9223372036854775807), time DESC"
            + " LIMIT #{size} OFFSET #{offset}")
    List<Long> findIdsSortedByAuthorIdAscPaged(@Param("size") int size, @Param("offset") int offset);

    @Select(SELECT_ARTWORK + " WHERE time < #{beforeTime}")
    List<ArtworkRecord> findByTimeBefore(long beforeTime);

    @Update("UPDATE artworks SET author_id = #{authorId} WHERE artwork_id = #{artworkId}")
    void updateAuthorId(@Param("artworkId") long artworkId, @Param("authorId") long authorId);

    @Select("SELECT artwork_id FROM artworks WHERE author_id IS NULL")
    List<Long> findIdsMissingAuthor();

    // ── Tags ────────────────────────────────────────────────────────────────────

    /**
     * 写入或更新标签。已存在同名标签时仅补齐缺失的 {@code translated_name}，不覆盖已有翻译。
     */
    @Insert("INSERT INTO tags(name, translated_name) VALUES(#{name}, #{translatedName})"
            + " ON CONFLICT(name) DO UPDATE SET"
            + " translated_name = COALESCE(tags.translated_name, excluded.translated_name)")
    void upsertTag(@Param("name") String name,
                   @Param("translatedName") String translatedName);

    @Select("SELECT tag_id FROM tags WHERE name = #{name}")
    Long findTagIdByName(@Param("name") String name);

    @Insert("INSERT OR IGNORE INTO artwork_tags(artwork_id, tag_id) VALUES(#{artworkId}, #{tagId})")
    void insertArtworkTag(@Param("artworkId") long artworkId,
                          @Param("tagId") long tagId);

    @Delete("DELETE FROM artwork_tags WHERE artwork_id = #{artworkId}")
    void deleteArtworkTags(@Param("artworkId") long artworkId);

    @Select("SELECT t.name AS name, t.translated_name AS translatedName"
            + " FROM artwork_tags at JOIN tags t ON t.tag_id = at.tag_id"
            + " WHERE at.artwork_id = #{artworkId}"
            + " ORDER BY t.tag_id")
    List<TagDto> findTagsByArtworkId(@Param("artworkId") long artworkId);

    @Select("SELECT 1 FROM artwork_tags WHERE artwork_id = #{artworkId} LIMIT 1")
    Integer existsTagsForArtwork(@Param("artworkId") long artworkId);

    // ── Statistics ───────────────────────────────────────────────────────────────

    @Select("SELECT total_artworks, total_images, total_moved FROM statistics WHERE id = 1")
    StatisticsData getStats();

    @Update("UPDATE statistics SET total_artworks = total_artworks + 1,"
            + " total_images = total_images + #{imageCount} WHERE id = 1")
    void incrementStats(int imageCount);

    @Update("UPDATE statistics SET total_moved = total_moved + 1 WHERE id = 1")
    void incrementMoved();

    @Update("UPDATE statistics SET total_artworks = #{totalArtworks},"
            + " total_images = #{totalImages}, total_moved = #{totalMoved} WHERE id = 1")
    void setStats(@Param("totalArtworks") int totalArtworks,
                  @Param("totalImages") int totalImages,
                  @Param("totalMoved") int totalMoved);

}
