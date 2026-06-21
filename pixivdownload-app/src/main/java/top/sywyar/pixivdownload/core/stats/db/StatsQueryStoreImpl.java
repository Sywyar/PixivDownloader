package top.sywyar.pixivdownload.core.stats.db;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.stats.StatsAggregates;
import top.sywyar.pixivdownload.core.stats.StatsQueryStore;

import javax.sql.DataSource;
import java.util.List;

/**
 * {@link StatsQueryStore} 的核心实现层（{@code core.stats.db}）。
 *
 * <p><b>边界职责：</b>把池化 {@code DataSource} + {@link NamedParameterJdbcTemplate} + 聚合 SQL 收拢为内部实现，
 * 只透出 {@link StatsQueryStore} 声明的语义只读聚合方法；统计插件托管的 {@code StatsService} 注入的是接口
 * {@link StatsQueryStore}、永远拿不到 {@code DataSource} / {@code JdbcTemplate} / 自由 SQL。读取的
 * {@code statistics} / {@code artworks} / {@code novels} / {@code authors} / {@code tags} 等均为<b>核心 owned</b> 表。
 *
 * <p>本类为根包扫描的核心 {@code @Repository}（<b>非</b> {@code @PluginManagedBean}），故核心实现层允许直接使用
 * Spring JDBC——「插件托管 Bean 禁直连数据库底层」守卫只约束 {@code @PluginManagedBean}，不波及本类。注入 Spring
 * 提供的池化 {@code DataSource}（不自建连接、不绕过连接池）；仅 SELECT、不改写任何下载流程数据；全库范围、无访客
 * 可见性裁剪——统计仪表盘是管理员专属页面。
 */
@Repository
public class StatsQueryStoreImpl implements StatsQueryStore {

    private final NamedParameterJdbcTemplate jdbc;

    public StatsQueryStoreImpl(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public StatsAggregates.Overview overview() {
        StatsAggregates.Overview fromStatsRow = jdbc.query(
                "SELECT total_artworks, total_images, total_moved FROM statistics WHERE id = 1",
                rs -> rs.next()
                        ? new StatsAggregates.Overview(rs.getLong("total_artworks"), rs.getLong("total_images"),
                                rs.getLong("total_moved"), 0, 0, 0, 0)
                        : new StatsAggregates.Overview(0, 0, 0, 0, 0, 0, 0));
        long totalNovels = queryCount("SELECT COUNT(*) FROM novels WHERE deleted = 0");
        long totalAuthors = queryCount("SELECT COUNT(DISTINCT author_id) FROM ("
                + " SELECT author_id FROM artworks WHERE author_id IS NOT NULL AND deleted = 0"
                + " UNION"
                + " SELECT author_id FROM novels WHERE author_id IS NOT NULL AND deleted = 0"
                + ")");
        long totalTags = queryCount("SELECT COUNT(DISTINCT tag_id) FROM ("
                + " SELECT tag_id FROM artwork_tags"
                + " UNION"
                + " SELECT tag_id FROM novel_tags"
                + ")");
        long totalSeries = queryCount("SELECT COUNT(*) FROM ("
                + " SELECT series_id FROM artworks WHERE series_id IS NOT NULL AND series_id > 0 AND deleted = 0"
                + " GROUP BY series_id"
                + " UNION ALL"
                + " SELECT series_id FROM novels WHERE series_id IS NOT NULL AND series_id > 0 AND deleted = 0"
                + " GROUP BY series_id"
                + ")");
        return new StatsAggregates.Overview(
                fromStatsRow.totalArtworks(), fromStatsRow.totalImages(), fromStatsRow.totalMoved(),
                totalNovels, totalAuthors, totalTags, totalSeries);
    }

    private long queryCount(String sql) {
        Long v = jdbc.getJdbcTemplate().queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    @Override
    public List<StatsAggregates.AuthorStat> topAuthors(int limit) {
        String sql = "SELECT works.author_id AS author_id, au.name AS name, COUNT(*) AS cnt"
                + " FROM ("
                + " SELECT author_id FROM artworks WHERE author_id IS NOT NULL AND deleted = 0"
                + " UNION ALL"
                + " SELECT author_id FROM novels WHERE author_id IS NOT NULL AND deleted = 0"
                + " ) works"
                + " LEFT JOIN authors au ON au.author_id = works.author_id"
                + " GROUP BY works.author_id, au.name"
                + " ORDER BY cnt DESC, works.author_id ASC"
                + " LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> {
            long authorId = rs.getLong("author_id");
            return new StatsAggregates.AuthorStat(authorId, rs.getString("name"), rs.getLong("cnt"));
        });
    }

    @Override
    public List<StatsAggregates.TagStat> topTags(int limit) {
        String sql = "SELECT t.tag_id AS tag_id, t.name AS name, t.translated_name AS translated_name,"
                + " COUNT(*) AS cnt"
                + " FROM tags t"
                + " JOIN ("
                + " SELECT tag_id FROM artwork_tags"
                + " UNION ALL"
                + " SELECT tag_id FROM novel_tags"
                + " ) work_tags ON work_tags.tag_id = t.tag_id"
                + " GROUP BY t.tag_id, t.name, t.translated_name"
                + " ORDER BY cnt DESC, t.tag_id ASC"
                + " LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> new StatsAggregates.TagStat(
                rs.getLong("tag_id"),
                rs.getString("name"),
                rs.getString("translated_name"),
                rs.getLong("cnt")));
    }

    @Override
    public List<StatsAggregates.MonthlyStat> monthlyArtworkCounts() {
        String sql = "SELECT strftime('%Y-%m', time / 1000, 'unixepoch', 'localtime') AS ym, COUNT(*) AS cnt"
                + " FROM ("
                + " SELECT time FROM artworks WHERE time > 0 AND deleted = 0"
                + " UNION ALL"
                + " SELECT time FROM novels WHERE time > 0 AND deleted = 0"
                + " )"
                + " GROUP BY ym ORDER BY ym ASC";
        return jdbc.getJdbcTemplate().query(sql, (rs, rowNum) ->
                new StatsAggregates.MonthlyStat(rs.getString("ym"), rs.getLong("cnt")));
    }
}
