package top.sywyar.pixivdownload.author;

import org.apache.ibatis.annotations.*;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AuthorMapper {

    @Update("CREATE TABLE IF NOT EXISTS authors ("
            + "author_id INTEGER PRIMARY KEY,"
            + "name TEXT NOT NULL,"
            + "updated_time INTEGER NOT NULL)")
    void createAuthorsTable();

    @Update("UPDATE authors SET updated_time = updated_time * 1000"
            + " WHERE updated_time > 0 AND updated_time < 1000000000000")
    int migrateAuthorTimestampsToMillis();

    @Insert("INSERT OR IGNORE INTO authors(author_id, name, updated_time)"
            + " VALUES(#{id}, #{name}, #{updatedTime})")
    int insertIfAbsent(@Param("id") long id,
                       @Param("name") String name,
                       @Param("updatedTime") long updatedTime);

    @Update("UPDATE authors SET name = #{name}, updated_time = #{updatedTime}"
            + " WHERE author_id = #{id}")
    int updateName(@Param("id") long id,
                   @Param("name") String name,
                   @Param("updatedTime") long updatedTime);

    @Select("SELECT author_id, name, updated_time FROM authors WHERE author_id = #{id}")
    Author findById(long id);

    @Select("SELECT author_id, name, updated_time FROM authors ORDER BY LOWER(name), author_id")
    List<Author> findAll();

    @Select({
            "<script>",
            "SELECT author_id, name, updated_time FROM authors",
            "WHERE author_id IN",
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    List<Author> findByIds(@Param("ids") Collection<Long> ids);

    @Select("SELECT COUNT(*) FROM ("
            + " SELECT a.author_id FROM artworks a"
            + " LEFT JOIN authors au ON au.author_id = a.author_id"
            + " WHERE a.author_id IS NOT NULL"
            + " AND (au.name LIKE #{search} OR CAST(a.author_id AS TEXT) LIKE #{search})"
            + " GROUP BY a.author_id)")
    long countAuthorsWithArtworks(@Param("search") String search);

    @Select("SELECT a.author_id AS authorId,"
            + " COALESCE(au.name, CAST(a.author_id AS TEXT)) AS name,"
            + " COUNT(*) AS artworkCount"
            + " FROM artworks a"
            + " LEFT JOIN authors au ON au.author_id = a.author_id"
            + " WHERE a.author_id IS NOT NULL"
            + " AND (au.name LIKE #{search} OR CAST(a.author_id AS TEXT) LIKE #{search})"
            + " GROUP BY a.author_id, au.name"
            + " ORDER BY"
            + " CASE WHEN #{sort} = 'artworks' THEN -COUNT(*) END,"
            + " CASE WHEN #{sort} = 'authorId' THEN a.author_id END,"
            + " CASE WHEN #{sort} NOT IN ('artworks','authorId')"
            + "      THEN LOWER(COALESCE(au.name, CAST(a.author_id AS TEXT))) END,"
            + " a.author_id"
            + " LIMIT #{limit} OFFSET #{offset}")
    List<AuthorSummary> findAuthorsWithArtworks(@Param("search") String search,
                                                @Param("sort") String sort,
                                                @Param("limit") int limit,
                                                @Param("offset") int offset);
}
