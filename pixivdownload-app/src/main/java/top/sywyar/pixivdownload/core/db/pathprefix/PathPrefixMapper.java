package top.sywyar.pixivdownload.core.db.pathprefix;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PathPrefixMapper {

    @Update("CREATE TABLE IF NOT EXISTS path_prefixes ("
            + "id INTEGER PRIMARY KEY,"
            + "path TEXT NOT NULL UNIQUE)")
    void createTable();

    @Insert("INSERT OR IGNORE INTO path_prefixes(path) VALUES(#{path})")
    void insertIfAbsent(@Param("path") String path);

    @Select("SELECT id FROM path_prefixes WHERE path = #{path}")
    Long findIdByPath(@Param("path") String path);

    @Select("SELECT path FROM path_prefixes WHERE id = #{id}")
    String findPathById(@Param("id") long id);

    @Update("UPDATE path_prefixes SET path = #{path} WHERE id = #{id}")
    int updatePath(@Param("id") long id, @Param("path") String path);

    @Select("SELECT id, path FROM path_prefixes ORDER BY LENGTH(path) DESC, id ASC")
    List<PathPrefix> findAll();
}
