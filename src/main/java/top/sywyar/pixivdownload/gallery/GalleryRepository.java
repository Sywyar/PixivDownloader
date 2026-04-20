package top.sywyar.pixivdownload.gallery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;


/**
 * 画廊查询的动态 SQL 仓库。使用 {@link NamedParameterJdbcTemplate} 拼装可选筛选条件。
 * 仅返回作品 ID 列表 + 总数，调用方再走 {@code PixivDatabase}/{@code toDownloadedResponse}
 * 组装完整响应，保持和监控页分页流程一致。
 */
@Slf4j
@Repository
public class GalleryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public GalleryRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public QueryResult findArtworkIds(GalleryQuery q) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (q.getSearch() != null) {
            where.append(" AND (a.title LIKE :search OR au.name LIKE :search)");
            params.addValue("search", "%" + q.getSearch() + "%");
        }

        if ("r18g".equals(q.getR18())) {
            where.append(" AND a.\"R18\" = 2");
        } else if ("r18".equals(q.getR18()) || "yes".equals(q.getR18())) {
            where.append(" AND a.\"R18\" = 1");
        } else if ("r18plus".equals(q.getR18())) {
            where.append(" AND a.\"R18\" >= 1");
        } else if ("no".equals(q.getR18())) {
            where.append(" AND (a.\"R18\" = 0 OR a.\"R18\" IS NULL)");
        }

        if ("yes".equals(q.getAi())) {
            where.append(" AND a.is_ai = 1");
        } else if ("no".equals(q.getAi())) {
            where.append(" AND (a.is_ai = 0 OR a.is_ai IS NULL)");
        }

        List<String> formats = q.getFormats();
        if (formats != null && !formats.isEmpty()) {
            List<String> clauses = new ArrayList<>();
            for (int i = 0; i < formats.size(); i++) {
                String key = "fmt" + i;
                clauses.add("a.extensions LIKE :" + key);
                params.addValue(key, "%" + formats.get(i) + "%");
            }
            where.append(" AND (").append(String.join(" OR ", clauses)).append(")");
        }

        List<Long> collectionIds = q.getCollectionIds();
        if (collectionIds != null && !collectionIds.isEmpty()) {
            where.append(" AND a.artwork_id IN (SELECT DISTINCT artwork_id FROM artwork_collections"
                    + " WHERE collection_id IN (:collectionIds))");
            params.addValue("collectionIds", collectionIds);
        }

        List<Long> tagIds = q.getTagIds();
        if (tagIds != null && !tagIds.isEmpty()) {
            // AND 语义：作品需同时命中所有选中的标签
            where.append(" AND a.artwork_id IN (SELECT artwork_id FROM artwork_tags"
                    + " WHERE tag_id IN (:tagIds)"
                    + " GROUP BY artwork_id HAVING COUNT(DISTINCT tag_id) = :tagIdCount)");
            params.addValue("tagIds", tagIds);
            params.addValue("tagIdCount", tagIds.size());
        }

        String countSql = "SELECT COUNT(*) FROM artworks a"
                + " LEFT JOIN authors au ON au.author_id = a.author_id"
                + where;
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        long totalElements = total == null ? 0 : total;

        if (totalElements == 0) {
            return new QueryResult(List.of(), 0);
        }

        String orderBy = buildOrderBy(q);
        String pageSql = "SELECT a.artwork_id FROM artworks a"
                + " LEFT JOIN authors au ON au.author_id = a.author_id"
                + where
                + " ORDER BY " + orderBy
                + " LIMIT :limit OFFSET :offset";
        params.addValue("limit", q.getSize());
        params.addValue("offset", q.getPage() * q.getSize());

        List<Long> ids = jdbc.query(pageSql, params,
                (rs, rowNum) -> rs.getLong("artwork_id"));
        return new QueryResult(ids, totalElements);
    }

    /**
     * 同作者的其他作品 ID，按 time 倒序，排除自身。
     */
    public List<Long> findByAuthor(long authorId, long excludeArtworkId, int limit) {
        String sql = "SELECT artwork_id FROM artworks"
                + " WHERE author_id = :authorId AND artwork_id <> :excludeId"
                + " ORDER BY time DESC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("authorId", authorId)
                .addValue("excludeId", excludeArtworkId)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> rs.getLong("artwork_id"));
    }

    /**
     * 相关作品：与给定作品共享至少一个 tag，按共享 tag 数降序、时间倒序。
     */
    public List<Long> findRelatedByTags(long artworkId, int limit) {
        String sql = "SELECT a.artwork_id FROM artworks a"
                + " JOIN artwork_tags at ON at.artwork_id = a.artwork_id"
                + " WHERE at.tag_id IN (SELECT tag_id FROM artwork_tags WHERE artwork_id = :id)"
                + " AND a.artwork_id <> :id"
                + " GROUP BY a.artwork_id"
                + " ORDER BY COUNT(at.tag_id) DESC, a.time DESC"
                + " LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", artworkId)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> rs.getLong("artwork_id"));
    }

    private String buildOrderBy(GalleryQuery q) {
        String dir = "asc".equals(q.getOrder()) ? "ASC" : "DESC";
        return switch (q.getSort()) {
            case "artworkId" -> "a.artwork_id " + dir;
            case "imgs" -> "a.count " + dir + ", a.time DESC";
            case "status" -> "a.moved " + dir + ", a.time DESC";
            case "authorId" -> "COALESCE(a.author_id, 9223372036854775807) " + dir + ", a.time DESC";
            case "tags" -> "(SELECT COUNT(*) FROM artwork_tags WHERE artwork_id = a.artwork_id) " + dir
                    + ", a.time DESC";
            default -> "a.time " + dir;
        };
    }

    public record QueryResult(List<Long> ids, long totalElements) {}

    /**
     * 查询所有标签并统计每个标签被多少作品使用，按使用量降序、标签 ID 升序返回。
     * 支持按 name 或 translated_name 进行模糊匹配（大小写不敏感）。
     */
    public List<TagOption> findTagsWithCounts(String search, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.tag_id AS tag_id, t.name AS name, t.translated_name AS translated_name,"
                        + " COUNT(at.artwork_id) AS artwork_count"
                        + " FROM tags t"
                        + " LEFT JOIN artwork_tags at ON at.tag_id = t.tag_id");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (search != null && !search.isBlank()) {
            sql.append(" WHERE LOWER(t.name) LIKE :kw OR LOWER(COALESCE(t.translated_name, '')) LIKE :kw");
            params.addValue("kw", "%" + search.trim().toLowerCase() + "%");
        }
        sql.append(" GROUP BY t.tag_id, t.name, t.translated_name")
                .append(" HAVING artwork_count > 0")
                .append(" ORDER BY artwork_count DESC, t.tag_id ASC");
        if (limit > 0) {
            sql.append(" LIMIT :limit");
            params.addValue("limit", limit);
        }
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new TagOption(
                rs.getLong("tag_id"),
                rs.getString("name"),
                rs.getString("translated_name"),
                rs.getInt("artwork_count")));
    }

    public record TagOption(long tagId, String name, String translatedName, int artworkCount) {}
}
