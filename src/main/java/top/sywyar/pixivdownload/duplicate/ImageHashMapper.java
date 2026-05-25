package top.sywyar.pixivdownload.duplicate;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ImageHashMapper {

    @Insert("INSERT INTO artwork_image_hashes(artwork_id, page, ext, dhash, ahash, created_time)"
            + " VALUES(#{artworkId}, #{page}, #{ext}, #{dHash}, #{aHash}, #{createdTime})"
            + " ON CONFLICT(artwork_id, page) DO UPDATE SET"
            + " ext = excluded.ext,"
            + " dhash = excluded.dhash,"
            + " ahash = excluded.ahash,"
            + " created_time = excluded.created_time")
    void upsert(@Param("artworkId") long artworkId,
                @Param("page") int page,
                @Param("ext") String ext,
                @Param("dHash") long dHash,
                @Param("aHash") Long aHash,
                @Param("createdTime") long createdTime);

    /**
     * 「已尝试但无结果」标记：对解码失败 / 文件缺失 / 无可哈希页的作品写入哨兵行（page = -1），
     * 使其在 {@link #artworkIdsMissingHashes(int)} 中不再被视为缺哈希、避免每次维护窗口反复重试。
     * 哨兵行的 dhash 固定为 0，{@link #findAll()} 已用 {@code page >= 0} 将其排除在分组之外。
     */
    @Insert("INSERT INTO artwork_image_hashes(artwork_id, page, ext, dhash, ahash, created_time)"
            + " VALUES(#{artworkId}, -1, '', 0, NULL, #{createdTime})"
            + " ON CONFLICT(artwork_id, page) DO UPDATE SET created_time = excluded.created_time")
    void markNoHash(@Param("artworkId") long artworkId, @Param("createdTime") long createdTime);

    @Select("SELECT h.artwork_id AS artworkId, h.page AS page, h.ext AS ext,"
            + " h.dhash AS dHash, h.ahash AS aHash, h.created_time AS createdTime,"
            + " a.title AS title, a.author_id AS authorId, au.name AS authorName,"
            + " a.\"R18\" AS xRestrict"
            + " FROM artwork_image_hashes h"
            + " JOIN artworks a ON a.artwork_id = h.artwork_id"
            + " LEFT JOIN authors au ON au.author_id = a.author_id"
            + " WHERE h.page >= 0"
            + " ORDER BY h.artwork_id, h.page")
    List<ImageHashRow> findAll();

    @Select("SELECT COUNT(*) FROM artwork_image_hashes")
    long countAllHashRows();

    @Select("SELECT MAX(created_time) FROM artwork_image_hashes")
    Long maxCreatedTime();

    @Select("SELECT COUNT(*) FROM artworks a"
            + " LEFT JOIN (SELECT DISTINCT artwork_id FROM artwork_image_hashes) h ON h.artwork_id = a.artwork_id"
            + " WHERE h.artwork_id IS NULL")
    int countArtworksMissingHashes();

    @Select("SELECT a.artwork_id FROM artworks a"
            + " LEFT JOIN (SELECT DISTINCT artwork_id FROM artwork_image_hashes) h ON h.artwork_id = a.artwork_id"
            + " WHERE h.artwork_id IS NULL"
            + " ORDER BY a.time DESC"
            + " LIMIT #{limit}")
    List<Long> artworkIdsMissingHashes(@Param("limit") int limit);

    @Delete("DELETE FROM artwork_image_hashes WHERE artwork_id = #{artworkId}")
    void deleteByArtwork(@Param("artworkId") long artworkId);

    @Delete("DELETE FROM artwork_image_hashes")
    void deleteAll();
}
