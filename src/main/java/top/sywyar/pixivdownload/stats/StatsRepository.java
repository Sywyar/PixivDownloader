package top.sywyar.pixivdownload.stats;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;

/**
 * 只读的统计聚合仓库。仅 SELECT，不改写任何下载流程数据。
 * 全库范围（无访客可见性裁剪）——统计仪表盘是管理员专属页面。
 */
@Slf4j
@Repository
public class StatsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StatsRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** 总览卡片数据：作品/图片/已移动来自 statistics 单行，其余为实时聚合计数。 */
    public StatsDto.Overview overview() {
        StatsDto.Overview fromStatsRow = jdbc.query(
                "SELECT total_artworks, total_images, total_moved FROM statistics WHERE id = 1",
                rs -> rs.next()
                        ? new StatsDto.Overview(rs.getLong("total_artworks"), rs.getLong("total_images"),
                                rs.getLong("total_moved"), 0, 0, 0, 0)
                        : new StatsDto.Overview(0, 0, 0, 0, 0, 0, 0));
        long totalNovels = queryCount("SELECT COUNT(*) FROM novels");
        long totalAuthors = queryCount("SELECT COUNT(DISTINCT author_id) FROM artworks WHERE author_id IS NOT NULL");
        long totalTags = queryCount("SELECT COUNT(DISTINCT tag_id) FROM artwork_tags");
        long totalSeries = queryCount("SELECT COUNT(DISTINCT series_id) FROM artworks WHERE series_id IS NOT NULL AND series_id > 0");
        return new StatsDto.Overview(
                fromStatsRow.totalArtworks(), fromStatsRow.totalImages(), fromStatsRow.totalMoved(),
                totalNovels, totalAuthors, totalTags, totalSeries);
    }

    private long queryCount(String sql) {
        Long v = jdbc.getJdbcTemplate().queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    /** 下载量最高的作者，按作品数降序。name 为空时由上层回退展示 author_id。 */
    public List<StatsDto.AuthorStat> topAuthors(int limit) {
        String sql = "SELECT a.author_id AS author_id, au.name AS name, COUNT(*) AS cnt"
                + " FROM artworks a"
                + " LEFT JOIN authors au ON au.author_id = a.author_id"
                + " WHERE a.author_id IS NOT NULL"
                + " GROUP BY a.author_id, au.name"
                + " ORDER BY cnt DESC, a.author_id ASC"
                + " LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> {
            long authorId = rs.getLong("author_id");
            return new StatsDto.AuthorStat(authorId, rs.getString("name"), rs.getLong("cnt"));
        });
    }

    /** 使用最多的标签，按作品数降序（标签词云 / Top 标签）。 */
    public List<StatsDto.TagStat> topTags(int limit) {
        String sql = "SELECT t.tag_id AS tag_id, t.name AS name, t.translated_name AS translated_name,"
                + " COUNT(at.artwork_id) AS cnt"
                + " FROM tags t"
                + " JOIN artwork_tags at ON at.tag_id = t.tag_id"
                + " GROUP BY t.tag_id, t.name, t.translated_name"
                + " ORDER BY cnt DESC, t.tag_id ASC"
                + " LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> new StatsDto.TagStat(
                rs.getLong("tag_id"),
                rs.getString("name"),
                rs.getString("translated_name"),
                rs.getLong("cnt")));
    }

    /**
     * 按月（本地时区）统计下载作品数，时间升序。
     * {@code artworks.time} 为 epoch 毫秒，转换为秒后用 strftime 分组。
     */
    public List<StatsDto.MonthlyStat> monthlyArtworkCounts() {
        String sql = "SELECT strftime('%Y-%m', time / 1000, 'unixepoch', 'localtime') AS ym, COUNT(*) AS cnt"
                + " FROM artworks WHERE time > 0"
                + " GROUP BY ym ORDER BY ym ASC";
        return jdbc.getJdbcTemplate().query(sql, (rs, rowNum) ->
                new StatsDto.MonthlyStat(rs.getString("ym"), rs.getLong("cnt")));
    }
}
