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
}
