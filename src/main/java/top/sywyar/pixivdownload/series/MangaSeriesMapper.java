package top.sywyar.pixivdownload.series;

import org.apache.ibatis.annotations.*;

import java.util.Collection;
import java.util.List;

@Mapper
public interface MangaSeriesMapper {

    @Update("CREATE TABLE IF NOT EXISTS manga_series ("
            + "series_id INTEGER PRIMARY KEY,"
            + "title TEXT NOT NULL,"
            + "author_id INTEGER,"
            + "updated_time INTEGER NOT NULL,"
            + "description TEXT DEFAULT NULL,"
            + "cover_ext TEXT DEFAULT NULL,"
            + "cover_folder TEXT DEFAULT NULL)")
    void createMangaSeriesTable();

    /** 幂等迁移：旧库 manga_series 表补 description 列；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE manga_series ADD COLUMN description TEXT DEFAULT NULL")
    void addDescriptionColumn();

    /** 幂等迁移：旧库 manga_series 表补 cover_ext 列；列已存在时调用方需吞掉异常 */
    @Update("ALTER TABLE manga_series ADD COLUMN cover_ext TEXT DEFAULT NULL")
    void addCoverExtColumn();

    /** 幂等迁移：旧库 manga_series 表补 cover_folder 列（落盘封面的绝对目录）；列已存在抛异常吞掉 */
    @Update("ALTER TABLE manga_series ADD COLUMN cover_folder TEXT DEFAULT NULL")
    void addCoverFolderColumn();

    @Update("UPDATE manga_series SET updated_time = updated_time * 1000"
            + " WHERE updated_time > 0 AND updated_time < 1000000000000")
    int migrateSeriesTimestampsToMillis();

    @Insert("INSERT OR IGNORE INTO manga_series(series_id, title, author_id, updated_time)"
            + " VALUES(#{id}, #{title}, #{authorId}, #{updatedTime})")
    int insertIfAbsent(@Param("id") long id,
                       @Param("title") String title,
                       @Param("authorId") Long authorId,
                       @Param("updatedTime") long updatedTime);

    @Update("UPDATE manga_series SET title = #{title}, author_id = #{authorId},"
            + " updated_time = #{updatedTime} WHERE series_id = #{id}")
    int updateInfo(@Param("id") long id,
                   @Param("title") String title,
                   @Param("authorId") Long authorId,
                   @Param("updatedTime") long updatedTime);

    @Update("UPDATE manga_series SET description = #{description},"
            + " cover_ext = #{coverExt}, cover_folder = #{coverFolder}"
            + " WHERE series_id = #{id}")
    int updateMetadata(@Param("id") long id,
                       @Param("description") String description,
                       @Param("coverExt") String coverExt,
                       @Param("coverFolder") String coverFolder);

    @Select("SELECT series_id, title, author_id, updated_time, description, cover_ext, cover_folder"
            + " FROM manga_series WHERE series_id = #{id}")
    MangaSeries findById(long id);

    @Select("SELECT series_id, title, author_id, updated_time, description, cover_ext, cover_folder"
            + " FROM manga_series ORDER BY LOWER(title), series_id")
    List<MangaSeries> findAll();

    @Select({
            "<script>",
            "SELECT series_id, title, author_id, updated_time, description, cover_ext, cover_folder",
            "FROM manga_series",
            "WHERE series_id IN",
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    List<MangaSeries> findByIds(@Param("ids") Collection<Long> ids);

    @Select("SELECT COUNT(*) FROM ("
            + " SELECT a.series_id FROM artworks a"
            + " LEFT JOIN manga_series ms ON ms.series_id = a.series_id"
            + " LEFT JOIN authors au ON au.author_id = ms.author_id"
            + " WHERE a.series_id IS NOT NULL AND a.series_id > 0 AND a.deleted = 0"
            + " AND (ms.title LIKE #{search} OR au.name LIKE #{search} OR CAST(a.series_id AS TEXT) LIKE #{search})"
            + " GROUP BY a.series_id)")
    long countSeriesWithArtworks(@Param("search") String search);

    @Select("SELECT a.series_id AS seriesId,"
            + " COALESCE(ms.title, CAST(a.series_id AS TEXT)) AS title,"
            + " ms.author_id AS authorId,"
            + " au.name AS authorName,"
            + " COUNT(*) AS artworkCount"
            + " FROM artworks a"
            + " LEFT JOIN manga_series ms ON ms.series_id = a.series_id"
            + " LEFT JOIN authors au ON au.author_id = ms.author_id"
            + " WHERE a.series_id IS NOT NULL AND a.series_id > 0 AND a.deleted = 0"
            + " AND (ms.title LIKE #{search} OR au.name LIKE #{search} OR CAST(a.series_id AS TEXT) LIKE #{search})"
            + " GROUP BY a.series_id, ms.title, ms.author_id, au.name"
            + " ORDER BY"
            + " CASE WHEN #{sort} = 'artworks' THEN -COUNT(*) END,"
            + " CASE WHEN #{sort} = 'seriesId' THEN a.series_id END,"
            + " CASE WHEN #{sort} NOT IN ('artworks','seriesId')"
            + "      THEN LOWER(COALESCE(ms.title, CAST(a.series_id AS TEXT))) END,"
            + " a.series_id"
            + " LIMIT #{limit} OFFSET #{offset}")
    List<MangaSeriesSummary> findSeriesWithArtworks(@Param("search") String search,
                                                     @Param("sort") String sort,
                                                     @Param("limit") int limit,
                                                     @Param("offset") int offset);

    @Select("SELECT a.series_id AS seriesId,"
            + " COALESCE(ms.title, CAST(a.series_id AS TEXT)) AS title,"
            + " ms.author_id AS authorId,"
            + " au.name AS authorName,"
            + " COUNT(*) AS artworkCount,"
            + " ms.updated_time AS updatedTime,"
            + " ms.description AS description,"
            + " ms.cover_ext AS coverExt,"
            + " ms.cover_folder AS coverFolder"
            + " FROM artworks a"
            + " LEFT JOIN manga_series ms ON ms.series_id = a.series_id"
            + " LEFT JOIN authors au ON au.author_id = ms.author_id"
            + " WHERE a.series_id = #{seriesId} AND a.series_id > 0 AND a.deleted = 0"
            + " GROUP BY a.series_id, ms.title, ms.author_id, au.name,"
            + " ms.updated_time, ms.description, ms.cover_ext, ms.cover_folder")
    MangaSeriesDetail findSeriesDetailById(@Param("seriesId") long seriesId);
}
