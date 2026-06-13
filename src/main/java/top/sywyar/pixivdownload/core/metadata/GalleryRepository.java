package top.sywyar.pixivdownload.core.metadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 插画作品的动态 SQL 仓库。使用 {@link NamedParameterJdbcTemplate} 拼装可选筛选条件。
 * 仅返回作品 ID 列表和总数，再由上层转换成完整响应。
 * <p>
 * 已收编进核心数据层（卸载投影：画廊插件未装时核心查询仍要读 {@code artworks}），
 * 作为根包扫描的核心 Bean；被核心查询服务与作者 / 收藏夹 / 系列 / 下载 controller 注入使用，
 * 画廊插件侧经 plugin.api 核心接口间接消费。
 */
@Slf4j
@Repository
public class GalleryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public GalleryRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public QueryResult findArtworkIds(GalleryQuery q) {
        QueryParts parts = buildFilteredQuery(q);
        Long total = jdbc.queryForObject(parts.countSql(), parts.params(), Long.class);
        long totalElements = total == null ? 0 : total;

        if (totalElements == 0) {
            return new QueryResult(List.of(), 0);
        }

        MapSqlParameterSource params = parts.params();
        params.addValue("limit", q.getSize());
        params.addValue("offset", q.getPage() * q.getSize());

        List<Long> ids = jdbc.query(parts.pageSql(), params,
                (rs, rowNum) -> rs.getLong("artwork_id"));
        return new QueryResult(ids, totalElements);
    }

    public List<Long> findAllArtworkIds(GalleryQuery q) {
        QueryParts parts = buildFilteredQuery(q);
        return jdbc.query(parts.allSql(), parts.params(),
                (rs, rowNum) -> rs.getLong("artwork_id"));
    }

    private QueryParts buildFilteredQuery(GalleryQuery q) {
        StringBuilder where = new StringBuilder(" WHERE a.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (q.getSearch() != null) {
            String type = q.getSearchType() == null ? "all" : q.getSearchType();
            switch (type) {
                case "title" -> {
                    where.append(" AND a.title LIKE :search");
                    params.addValue("search", "%" + q.getSearch() + "%");
                }
                case "author" -> {
                    where.append(" AND au.name LIKE :search");
                    params.addValue("search", "%" + q.getSearch() + "%");
                }
                case "desc" -> {
                    where.append(" AND a.description LIKE :search");
                    params.addValue("search", "%" + q.getSearch() + "%");
                }
                case "id" -> {
                    Long id = parseLongOrNull(q.getSearch());
                    where.append(id == null ? " AND 1=0" : " AND a.artwork_id = :searchId");
                    if (id != null) params.addValue("searchId", id);
                }
                case "authorId" -> {
                    Long id = parseLongOrNull(q.getSearch());
                    where.append(id == null ? " AND 1=0" : " AND a.author_id = :searchId");
                    if (id != null) params.addValue("searchId", id);
                }
                case "tag" -> {
                    where.append(" AND a.artwork_id IN (SELECT at.artwork_id FROM artwork_tags at"
                            + " JOIN tags t ON t.tag_id = at.tag_id"
                            + " WHERE t.name LIKE :search OR COALESCE(t.translated_name, '') LIKE :search)");
                    params.addValue("search", "%" + q.getSearch() + "%");
                }
                case "tagExact" -> {
                    where.append(" AND a.artwork_id IN (SELECT at.artwork_id FROM artwork_tags at"
                            + " JOIN tags t ON t.tag_id = at.tag_id"
                            + " WHERE LOWER(t.name) = :searchExact"
                            + " OR LOWER(COALESCE(t.translated_name, '')) = :searchExact)");
                    params.addValue("searchExact", q.getSearch().toLowerCase(java.util.Locale.ROOT));
                }
                default -> {
                    where.append(" AND (a.title LIKE :search OR au.name LIKE :search)");
                    params.addValue("search", "%" + q.getSearch() + "%");
                }
            }
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
        appendSeriesFilterClauses(where, params, q);
        appendGuestRestrictionClauses(where, params, q.getGuestRestriction());

        String countSql = "SELECT COUNT(*) FROM artworks a"
                + " LEFT JOIN authors au ON au.author_id = a.author_id"
                + where;

        String orderBy = buildOrderBy(q);
        String pageSql = "SELECT a.artwork_id FROM artworks a"
                + " LEFT JOIN authors au ON au.author_id = a.author_id"
                + where
                + " ORDER BY " + orderBy
                + " LIMIT :limit OFFSET :offset";
        String allSql = "SELECT a.artwork_id FROM artworks a"
                + " LEFT JOIN authors au ON au.author_id = a.author_id"
                + where
                + " ORDER BY " + orderBy;
        return new QueryParts(countSql, pageSql, allSql, params);
    }

    private record QueryParts(String countSql, String pageSql, String allSql, MapSqlParameterSource params) {}

    /**
     * 同作者的其他作品 ID，按 time 倒序，排除自身。
     */
    public List<Long> findByAuthor(long authorId, long excludeArtworkId, int limit) {
        String sql = "SELECT artwork_id FROM artworks"
                + " WHERE author_id = :authorId AND artwork_id <> :excludeId AND deleted = 0"
                + " ORDER BY time DESC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("authorId", authorId)
                .addValue("excludeId", excludeArtworkId)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> rs.getLong("artwork_id"));
    }

    /**
     * 同系列其他作品 ID，按 series_order 升序，排除自身。
     */
    public List<Long> findBySeries(long seriesId, long excludeArtworkId, int limit) {
        String sql = "SELECT artwork_id FROM artworks"
                + " WHERE series_id = :seriesId AND series_id > 0 AND artwork_id <> :excludeId AND deleted = 0"
                + " ORDER BY COALESCE(series_order, 0) ASC, artwork_id ASC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("seriesId", seriesId)
                .addValue("excludeId", excludeArtworkId)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> rs.getLong("artwork_id"));
    }

    /**
     * 在同系列已下载作品中，按 series_order 数值找最近的上一章和下一章（不要求严格相邻）。
     * 上一章 = series_order < 当前的最大值；下一章 = series_order > 当前的最小值。
     */
    public SeriesNeighbors findSeriesNeighbors(long artworkId) {
        String sql = "SELECT a.series_id AS series_id, a.series_order AS series_order,"
                + " ms.title AS series_title"
                + " FROM artworks a"
                + " LEFT JOIN manga_series ms ON ms.series_id = a.series_id"
                + " WHERE a.artwork_id = :id AND a.deleted = 0";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", artworkId);
        List<SeriesContext> ctx = jdbc.query(sql, params, (rs, rowNum) -> {
            long sid = rs.getLong("series_id");
            boolean sidNull = rs.wasNull();
            long ord = rs.getLong("series_order");
            boolean ordNull = rs.wasNull();
            return new SeriesContext(
                    sidNull ? null : sid,
                    ordNull ? null : ord,
                    rs.getString("series_title"));
        });
        if (ctx.isEmpty()) return null;
        SeriesContext c = ctx.get(0);
        if (c.seriesId == null || c.seriesId <= 0 || c.seriesOrder == null) {
            return null;
        }
        Neighbor prev = findNeighbor(c.seriesId, c.seriesOrder, true);
        Neighbor next = findNeighbor(c.seriesId, c.seriesOrder, false);
        return new SeriesNeighbors(c.seriesId, c.seriesTitle, c.seriesOrder, prev, next);
    }

    private Neighbor findNeighbor(long seriesId, long currentOrder, boolean prev) {
        String op = prev ? "<" : ">";
        String dir = prev ? "DESC" : "ASC";
        String sql = "SELECT artwork_id, title, COALESCE(series_order, 0) AS series_order FROM artworks"
                + " WHERE series_id = :seriesId AND series_id > 0 AND series_order " + op + " :order"
                + " AND deleted = 0"
                + " ORDER BY series_order " + dir + " LIMIT 1";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("seriesId", seriesId)
                .addValue("order", currentOrder);
        List<Neighbor> rows = jdbc.query(sql, params, (rs, rowNum) -> new Neighbor(
                rs.getLong("artwork_id"),
                rs.getString("title"),
                rs.getLong("series_order")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record SeriesContext(Long seriesId, Long seriesOrder, String seriesTitle) {}

    public record Neighbor(long artworkId, String title, long seriesOrder) {}

    public record SeriesNeighbors(long seriesId, String seriesTitle, long currentOrder,
                                  Neighbor prev, Neighbor next) {}

    /**
     * 相关作品：与给定作品共享至少一个 tag，按共享 tag 数量降序、时间倒序。
     */
    public List<Long> findRelatedByTags(long artworkId, int limit) {
        String sql = "SELECT a.artwork_id FROM artworks a"
                + " JOIN artwork_tags at ON at.artwork_id = a.artwork_id"
                + " WHERE at.tag_id IN (SELECT tag_id FROM artwork_tags WHERE artwork_id = :id)"
                + " AND a.artwork_id <> :id AND a.deleted = 0"
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
            case "series" -> "COALESCE(a.series_id, 0) " + dir + ", COALESCE(a.series_order, 0) ASC, a.time DESC";
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

    private static Long parseLongOrNull(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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

    private void appendGuestRestrictionClauses(StringBuilder where, MapSqlParameterSource params, GuestRestriction r) {
        appendVisibilityClauses(where, params, r, "Main");
    }

    private void appendExcludedTagClause(StringBuilder where, MapSqlParameterSource params, List<Long> excludedTagIds) {
        if (excludedTagIds == null || excludedTagIds.isEmpty()) {
            return;
        }
        where.append(" AND a.artwork_id NOT IN (SELECT DISTINCT artwork_id FROM artwork_tags"
                + " WHERE tag_id IN (:excludedTagIds))");
        params.addValue("excludedTagIds", excludedTagIds);
    }

    private void appendSeriesFilterClauses(StringBuilder where, MapSqlParameterSource params, GalleryQuery q) {
        List<Long> seriesIds = q.getSeriesIds();
        if (seriesIds != null && !seriesIds.isEmpty()) {
            where.append(" AND a.series_id IN (:seriesIds)");
            params.addValue("seriesIds", seriesIds);
        }
        List<Long> excludedSeriesIds = q.getExcludedSeriesIds();
        if (excludedSeriesIds != null && !excludedSeriesIds.isEmpty()) {
            where.append(" AND (a.series_id IS NULL OR a.series_id NOT IN (:excludedSeriesIds))");
            params.addValue("excludedSeriesIds", excludedSeriesIds);
        }
    }

    public record QueryResult(List<Long> ids, long totalElements) {}

    /**
     * 在 {@code artworks} 别名 {@code a} 上拼装"对该访客可见"的 SQL 片段，
     * 并把所需参数注入 {@code params}。{@code r == null} 时不附加任何条件。
     *
     * <p>条件分两部分：
     * <ol>
     *   <li><b>不可见优先级最高</b>：作品任一标签或作者命中"不可见集合"即排除。
     *       某维度 {@code unrestricted=true} 时不可见集合为空（默认全部可见，
     *       picker 中点击不可见会自动翻转为 {@code unrestricted=false}）。
     *       {@code unrestricted=false} 时不可见集合 = 全部 - 可见列表，因此 SQL 表达为
     *       "排除任何 artwork 包含一个不在白名单中的标签/作者"。</li>
     *   <li><b>OR 正向匹配</b>：通过排除后，作品仍需满足 (标签维度命中) 或 (作者维度命中)。</li>
     * </ol>
     */
    void appendVisibilityClauses(StringBuilder sql, MapSqlParameterSource params,
                                 GuestRestriction r, String paramSuffix) {
        if (r == null) return;
        Set<Integer> allowed = r.allowedXRestricts();
        if (allowed == null || allowed.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        List<String> ratingClauses = new ArrayList<>();
        if (allowed.contains(0)) ratingClauses.add("a.\"R18\" = 0 OR a.\"R18\" IS NULL");
        if (allowed.contains(1)) ratingClauses.add("a.\"R18\" = 1");
        if (allowed.contains(2)) ratingClauses.add("a.\"R18\" = 2");
        sql.append(" AND (").append(String.join(" OR ", ratingClauses)).append(")");

        // (1) 不可见维度排除（跨维度优先于 OR 匹配）
        if (!r.tagUnrestricted()) {
            if (r.tagIds() != null && !r.tagIds().isEmpty()) {
                String key = "vTagIds" + paramSuffix;
                // 排除：作品有任意一个标签不在可见白名单中（= 命中"不可见"标签）
                sql.append(" AND NOT EXISTS (SELECT 1 FROM artwork_tags at_h"
                        + " WHERE at_h.artwork_id = a.artwork_id"
                        + " AND at_h.tag_id NOT IN (:" + key + "))");
                params.addValue(key, r.tagIds());
            } else {
                // 可见白名单为空 → 全部不可见 → 排除任何带标签的作品
                sql.append(" AND NOT EXISTS (SELECT 1 FROM artwork_tags at_h"
                        + " WHERE at_h.artwork_id = a.artwork_id)");
            }
        }
        if (!r.authorUnrestricted()) {
            if (r.authorIds() != null && !r.authorIds().isEmpty()) {
                String key = "vAuthorIds" + paramSuffix;
                // 排除：作者非空 且 不在可见白名单中
                sql.append(" AND (a.author_id IS NULL OR a.author_id IN (:" + key + "))");
                params.addValue(key, r.authorIds());
            } else {
                // 可见白名单为空 → 全部不可见 → 排除任何指定了作者的作品
                sql.append(" AND a.author_id IS NULL");
            }
        }

        // (2) OR 正向匹配：至少一个维度产生命中
        if (r.tagUnrestricted() && r.authorUnrestricted()) return;
        List<String> orClauses = new ArrayList<>();
        if (r.tagUnrestricted()) {
            orClauses.add("1 = 1");
        } else if (r.tagIds() != null && !r.tagIds().isEmpty()) {
            String key = "pTagIds" + paramSuffix;
            orClauses.add("EXISTS (SELECT 1 FROM artwork_tags at_p"
                    + " WHERE at_p.artwork_id = a.artwork_id"
                    + " AND at_p.tag_id IN (:" + key + "))");
            params.addValue(key, r.tagIds());
        }
        if (r.authorUnrestricted()) {
            orClauses.add("1 = 1");
        } else if (r.authorIds() != null && !r.authorIds().isEmpty()) {
            String key = "pAuthorIds" + paramSuffix;
            orClauses.add("a.author_id IN (:" + key + ")");
            params.addValue(key, r.authorIds());
        }
        if (orClauses.isEmpty()) {
            sql.append(" AND 1 = 0");
        } else {
            sql.append(" AND (").append(String.join(" OR ", orClauses)).append(")");
        }
    }

    /** 该访客可见的标签 ID 集合（只要有一条可见作品携带该标签即纳入）。 */
    public Set<Long> findVisibleTagIds(GuestRestriction r) {
        if (r == null) return Collections.emptySet();
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT at.tag_id FROM artwork_tags at"
                        + " JOIN artworks a ON a.artwork_id = at.artwork_id WHERE a.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Tag");
        List<Long> ids = jdbc.query(sql.toString(), params, (rs, rowNum) -> rs.getLong(1));
        return new LinkedHashSet<>(ids);
    }

    /** 该访客可见的作者 ID 集合（仅统计有可见作品的作者）。 */
    public Set<Long> findVisibleAuthorIds(GuestRestriction r) {
        if (r == null) return Collections.emptySet();
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT a.author_id FROM artworks a"
                        + " WHERE a.author_id IS NOT NULL AND a.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Author");
        List<Long> ids = jdbc.query(sql.toString(), params, (rs, rowNum) -> rs.getLong(1));
        return new LinkedHashSet<>(ids);
    }

    /** 该访客可见的系列 ID 集合（仅统计有可见作品的系列）。 */
    public Set<Long> findVisibleSeriesIds(GuestRestriction r) {
        if (r == null) return Collections.emptySet();
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT a.series_id FROM artworks a"
                        + " WHERE a.series_id IS NOT NULL AND a.series_id > 0 AND a.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Series");
        List<Long> ids = jdbc.query(sql.toString(), params, (rs, rowNum) -> rs.getLong(1));
        return new LinkedHashSet<>(ids);
    }

    /** 作者 ID 与对应可见作品数。{@code r == null} 时不附加访客条件，统计全部未删除作品。 */
    public List<AuthorCount> findAuthorCounts(GuestRestriction r) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.author_id AS author_id, COUNT(*) AS cnt FROM artworks a"
                        + " WHERE a.author_id IS NOT NULL AND a.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "AuthorCount");
        sql.append(" GROUP BY a.author_id");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new AuthorCount(
                rs.getLong("author_id"),
                rs.getLong("cnt")));
    }

    public record AuthorCount(long authorId, long artworkCount) {}

    /** 系列 ID 与对应可见作品数。{@code r == null} 时不附加访客条件，统计全部未删除作品。 */
    public List<SeriesCount> findSeriesCounts(GuestRestriction r) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.series_id AS series_id, COUNT(*) AS cnt FROM artworks a"
                        + " WHERE a.series_id IS NOT NULL AND a.series_id > 0 AND a.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "SeriesCounts");
        sql.append(" GROUP BY a.series_id");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new SeriesCount(
                rs.getLong("series_id"),
                rs.getLong("cnt")));
    }

    public record SeriesCount(long seriesId, long artworkCount) {}

    /** 系列内对该访客可见的作品数。{@code r == null} 时返回系列总数。 */
    public long countArtworksInSeries(long seriesId, GuestRestriction r) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM artworks a"
                        + " WHERE a.series_id = :seriesId AND a.series_id > 0 AND a.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("seriesId", seriesId);
        if (r != null) {
            appendVisibilityClauses(sql, params, r, "SeriesCount");
        }
        Long total = jdbc.queryForObject(sql.toString(), params, Long.class);
        return total == null ? 0 : total;
    }

    /** 该访客可见的作品聚合统计：作品数 / 图片总数 / 已移动数。 */
    public GuestStatistics findGuestStatistics(GuestRestriction r) {
        if (r == null) return new GuestStatistics(0, 0, 0);
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS artwork_count,"
                        + " COALESCE(SUM(CASE WHEN a.count > 0 THEN a.count ELSE 0 END), 0) AS image_count,"
                        + " SUM(CASE WHEN a.moved = 1 THEN 1 ELSE 0 END) AS moved_count"
                        + " FROM artworks a WHERE a.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Stats");
        return jdbc.query(sql.toString(), params, rs -> {
            if (rs.next()) {
                return new GuestStatistics(
                        rs.getInt("artwork_count"),
                        rs.getInt("image_count"),
                        rs.getInt("moved_count"));
            }
            return new GuestStatistics(0, 0, 0);
        });
    }

    public record GuestStatistics(int artworks, int images, int moved) {}

    /** 该访客可见的"含有可见作品"的收藏夹 ID 集合。 */
    public Set<Long> findVisibleCollectionIds(GuestRestriction r) {
        if (r == null) return Collections.emptySet();
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT ac.collection_id FROM artwork_collections ac"
                        + " JOIN artworks a ON a.artwork_id = ac.artwork_id WHERE a.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Collection");
        List<Long> ids = jdbc.query(sql.toString(), params, (rs, rowNum) -> rs.getLong(1));
        return new LinkedHashSet<>(ids);
    }

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
