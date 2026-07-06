package top.sywyar.pixivdownload.douyin.db.history;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DouyinHistoryMapper {

    String SELECT_WORK = "SELECT work_id AS workId, title, folder, count, extensions, time, deleted, kind,"
            + " source_url AS sourceUrl, canonical_url AS canonicalUrl, thumbnail_url AS thumbnailUrl,"
            + " author_id AS authorId, author_name AS authorName, description,"
            + " item_title AS itemTitle, caption, publish_time AS publishTime,"
            + " collection_id AS collectionId, collection_title AS collectionTitle,"
            + " collection_order AS collectionOrder FROM douyin_works";

    String SELECT_FILE = "SELECT work_id AS workId, file_index AS fileIndex,"
            + " media_id AS mediaId, media_type AS mediaType, file_name AS fileName,"
            + " extension, bytes, content_type AS contentType, created_time AS createdTime"
            + " FROM douyin_work_files";

    @Select(SELECT_WORK + " WHERE work_id = #{workId} AND deleted = 0")
    DouyinWorkRecord findActiveById(@Param("workId") String workId);

    @Select(SELECT_WORK + " WHERE work_id = #{workId}")
    DouyinWorkRecord findAnyById(@Param("workId") String workId);

    @Select(SELECT_FILE + " WHERE work_id = #{workId} ORDER BY file_index")
    List<DouyinWorkFileRecord> findFilesByWorkId(@Param("workId") String workId);

    @Insert("INSERT OR IGNORE INTO douyin_works"
            + " (work_id, title, folder, count, extensions, time, deleted, kind,"
            + " source_url, canonical_url, thumbnail_url, author_id, author_name, description,"
            + " item_title, caption, publish_time, collection_id, collection_title, collection_order)"
            + " VALUES (#{workId}, #{title}, #{folder}, #{count}, #{extensions}, #{time}, #{deleted}, #{kind},"
            + " #{sourceUrl}, #{canonicalUrl}, #{thumbnailUrl}, #{authorId}, #{authorName}, #{description},"
            + " #{itemTitle}, #{caption}, #{publishTime}, #{collectionId}, #{collectionTitle}, #{collectionOrder})")
    int insertWork(DouyinWorkRecord record);

    @Insert("INSERT OR REPLACE INTO douyin_work_files"
            + " (work_id, file_index, media_id, media_type, file_name, extension, bytes, content_type, created_time)"
            + " VALUES (#{workId}, #{fileIndex}, #{mediaId}, #{mediaType}, #{fileName},"
            + " #{extension}, #{bytes}, #{contentType}, #{createdTime})")
    int upsertFile(DouyinWorkFileRecord record);

    @Select("SELECT COUNT(*) FROM douyin_works WHERE work_id = #{workId}")
    int countById(@Param("workId") String workId);

    @Select("SELECT COUNT(*) FROM douyin_works WHERE work_id = #{workId} AND deleted = 0")
    int countActiveById(@Param("workId") String workId);

    @Select("SELECT COUNT(*) FROM douyin_works WHERE work_id = #{workId} AND deleted = 1")
    int countDeletedById(@Param("workId") String workId);

    @Select("SELECT COUNT(*) FROM douyin_works WHERE time = #{time}")
    int countByTime(@Param("time") long time);

    @Select("SELECT MAX(time) FROM douyin_works")
    Long findMaxTime();

    @Update("UPDATE douyin_works SET deleted = 1 WHERE work_id = #{workId}")
    int markDeletedById(@Param("workId") String workId);

    @Delete("DELETE FROM douyin_work_files WHERE work_id = #{workId}")
    int deleteFilesByWorkId(@Param("workId") String workId);

    @Delete("DELETE FROM douyin_work_files WHERE work_id IN"
            + " (SELECT work_id FROM douyin_works WHERE work_id = #{workId} AND deleted = 1)")
    int deleteFilesIfWorkMarkedDeleted(@Param("workId") String workId);

    @Delete("DELETE FROM douyin_works WHERE work_id = #{workId} AND deleted = 1")
    int deleteWorkIfMarkedDeleted(@Param("workId") String workId);
}
