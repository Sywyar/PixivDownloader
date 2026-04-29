package top.sywyar.pixivdownload.gallery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 画廊查询的动态 SQL 仓库。使用 {@link NamedParameterJdbcTemplate} 拼装可选筛选条件。
 * 仅返回作品 ID 列表和总数，再由上层转换成完整响应。
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

        appendTagAuthorFilterClauses(where, params, q);

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
     * 相关作品：与给定作品共享至少一个 tag，按共享 tag 数量降序、时间倒序。
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

    private void appendOrGroup(StringBuilder where, List<String> clauses) {
        if (clauses == null || clauses.isEmpty()) {
            return;
        }
        if (clauses.size() == 1) {
            where.append(" AND ").append(clauses.get(0));
            return;
        }
        where.append(" AND (").append(String.join(" OR ", clauses)).append(")");
    }

    private void appendTagAuthorFilterClauses(StringBuilder where, MapSqlParameterSource params, GalleryQuery q) {
        List<Long> tagIds = q.getTagIds();
        List<Long> excludedTagIds = q.getExcludedTagIds();
        List<Long> optionalTagIds = q.getOptionalTagIds();
        List<Long> authorIds = q.getAuthorIds();
        List<Long> excludedAuthorIds = q.getExcludedAuthorIds();
        List<Long> optionalAuthorIds = q.getOptionalAuthorIds();

        appendExcludedTagClause(where, params, excludedTagIds);
        appendExcludedAuthorClause(where, params, excludedAuthorIds);

        List<String> positiveClauses = new ArrayList<>();
        if (hasAny(tagIds) && hasAny(authorIds)) {
            List<String> mustCore = new ArrayList<>();
            appendRequiredTagClause(mustCore, params, tagIds);
            appendRequiredAuthorClause(mustCore, params, authorIds);
            positiveClauses.add("(" + String.join(" AND ", mustCore) + ")");
        } else {
            appendRequiredTagClause(positiveClauses, params, tagIds);
            appendRequiredAuthorClause(positiveClauses, params, authorIds);
        }
        appendOptionalTagClause(positiveClauses, params, optionalTagIds);
        appendOptionalAuthorClause(positiveClauses, params, optionalAuthorIds);
        appendOrGroup(where, positiveClauses);
    }

    @SafeVarargs
    private boolean hasAny(List<Long>... idLists) {
        for (List<Long> ids : idLists) {
            if (ids != null && !ids.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void appendRequiredTagClause(List<String> clauses, MapSqlParameterSource params, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        clauses.add("a.artwork_id IN (SELECT artwork_id FROM artwork_tags"
                + " WHERE tag_id IN (:tagIds)"
                + " GROUP BY artwork_id HAVING COUNT(DISTINCT tag_id) = :tagIdCount)");
        params.addValue("tagIds", tagIds);
        params.addValue("tagIdCount", tagIds.size());
    }

    private void appendOptionalTagClause(List<String> clauses, MapSqlParameterSource params, List<Long> optionalTagIds) {
        if (optionalTagIds == null || optionalTagIds.isEmpty()) {
            return;
        }
        clauses.add("a.artwork_id IN (SELECT DISTINCT artwork_id FROM artwork_tags"
                + " WHERE tag_id IN (:optionalTagIds))");
        params.addValue("optionalTagIds", optionalTagIds);
    }

    private void appendRequiredAuthorClause(List<String> clauses, MapSqlParameterSource params, List<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return;
        }
        clauses.add("a.author_id IN (:authorIds)");
        params.addValue("authorIds", authorIds);
    }

    private void appendOptionalAuthorClause(List<String> clauses, MapSqlParameterSource params, List<Long> optionalAuthorIds) {
        if (optionalAuthorIds == null || optionalAuthorIds.isEmpty()) {
            return;
        }
        clauses.add("a.author_id IN (:optionalAuthorIds)");
        params.addValue("optionalAuthorIds", optionalAuthorIds);
    }

    private void appendExcludedAuthorClause(StringBuilder where, MapSqlParameterSource params, List<Long> excludedAuthorIds) {
        if (excludedAuthorIds == null || excludedAuthorIds.isEmpty()) {
            return;
        }
        where.append(" AND (a.author_id IS NULL OR a.author_id NOT IN (:excludedAuthorIds))");
        params.addValue("excludedAuthorIds", excludedAuthorIds);
    }

    private void appendExcludedTagClause(StringBuilder where, MapSqlParameterSource params, List<Long> excludedTagIds) {
        if (excludedTagIds == null || excludedTagIds.isEmpty()) {
            return;
        }
        where.append(" AND a.artwork_id NOT IN (SELECT DISTINCT artwork_id FROM artwork_tags"
                + " WHERE tag_id IN (:excludedTagIds))");
        params.addValue("excludedTagIds", excludedTagIds);
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
            params.addValue("kw", "%" + search.trim().toLowerCase(Locale.ROOT) + "%");
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

    public TagOption findTagByExactName(String name, String translatedName) {
        String normalizedName = normalizeLookup(name);
        String normalizedTranslatedName = normalizeLookup(translatedName);
        if (normalizedName == null && normalizedTranslatedName == null) {
            return null;
        }

        List<String> clauses = new ArrayList<>();
        List<String> orderBy = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (normalizedName != null) {
            clauses.add("LOWER(t.name) = :name");
            orderBy.add("CASE WHEN LOWER(t.name) = :name THEN 0 ELSE 1 END");
            params.addValue("name", normalizedName);
        }
        if (normalizedTranslatedName != null) {
            clauses.add("LOWER(COALESCE(t.translated_name, '')) = :translatedName");
            orderBy.add("CASE WHEN LOWER(COALESCE(t.translated_name, '')) = :translatedName THEN 0 ELSE 1 END");
            params.addValue("translatedName", normalizedTranslatedName);
        }

        String sql = "SELECT t.tag_id AS tag_id, t.name AS name, t.translated_name AS translated_name,"
                + " COUNT(at.artwork_id) AS artwork_count"
                + " FROM tags t"
                + " LEFT JOIN artwork_tags at ON at.tag_id = t.tag_id"
                + " WHERE " + String.join(" OR ", clauses)
                + " GROUP BY t.tag_id, t.name, t.translated_name"
                + " HAVING artwork_count > 0"
                + " ORDER BY " + String.join(", ", orderBy) + ", artwork_count DESC, t.tag_id ASC"
                + " LIMIT 1";
        List<TagOption> items = jdbc.query(sql, params, (rs, rowNum) -> new TagOption(
                rs.getLong("tag_id"),
                rs.getString("name"),
                rs.getString("translated_name"),
                rs.getInt("artwork_count")));
        return items.isEmpty() ? null : items.get(0);
    }

    private String normalizeLookup(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    public record TagOption(long tagId, String name, String translatedName, int artworkCount) {}
}
