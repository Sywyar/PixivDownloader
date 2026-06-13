package top.sywyar.pixivdownload.novel.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.plugin.api.PluginManagedBean;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 访客小说画廊的 SQL 仓库。复用 {@link GuestRestriction} 的 OR 语义，对应 artworks
 * 侧的 {@code GalleryRepository.appendVisibilityClauses}，避免在 Service 层做 N+1 过滤。
 *
 * <p>所有 SQL 都在 {@code novels} 表别名 {@code n} 上拼装，并使用 {@code novel_tags} 子表。
 * <p>
 * Bean 被 {@code @PluginManagedBean} 排除出根包扫描，由 {@code NovelPluginConfiguration}
 * 提供；除小说画廊侧外仍被核心查询服务注入使用。
 */
@Slf4j
@PluginManagedBean
public class NovelGalleryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NovelGalleryRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** 对该访客可见的所有 novelId，按 time 倒序。 */
    public List<Long> findVisibleNovelIds(GuestRestriction r) {
        if (r == null) return Collections.emptyList();
        StringBuilder sql = new StringBuilder("SELECT n.novel_id FROM novels n WHERE n.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Main");
        sql.append(" ORDER BY n.time DESC");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> rs.getLong("novel_id"));
    }

    /** 该访客可见的所有 novelId 的数量。 */
    public long countVisibleNovels(GuestRestriction r) {
        if (r == null) return 0;
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM novels n WHERE n.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Count");
        Long total = jdbc.queryForObject(sql.toString(), params, Long.class);
        return total == null ? 0 : total;
    }

    /**
     * 作者 ID 与对应可见小说数。{@code r == null} 表示无访客限制（管理员 / 非访客），
     * 统计全部未删除行——「无限制」只豁免可见性投影，{@code deleted = 1} 仍然过滤。
     */
    public List<NovelAuthorSummary> findVisibleNovelAuthorCounts(GuestRestriction r) {
        StringBuilder sql = new StringBuilder(
                "SELECT n.author_id AS author_id, COUNT(*) AS cnt FROM novels n"
                        + " WHERE n.author_id IS NOT NULL AND n.author_id > 0 AND n.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Author");
        sql.append(" GROUP BY n.author_id");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new NovelAuthorSummary(
                rs.getLong("author_id"),
                null,
                rs.getLong("cnt")));
    }

    /** 系列 ID 与对应可见小说数；{@code r == null} 语义同 {@link #findVisibleNovelAuthorCounts}。 */
    public List<NovelSeriesSummary> findVisibleNovelSeriesCounts(GuestRestriction r) {
        StringBuilder sql = new StringBuilder(
                "SELECT n.series_id AS series_id, COUNT(*) AS cnt FROM novels n"
                        + " WHERE n.series_id IS NOT NULL AND n.series_id > 0 AND n.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Series");
        sql.append(" GROUP BY n.series_id");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new NovelSeriesSummary(
                rs.getLong("series_id"),
                null,
                null,
                null,
                rs.getLong("cnt")));
    }

    /** 标签 ID 与对应可见小说数；{@code r == null} 语义同 {@link #findVisibleNovelAuthorCounts}。 */
    public List<NovelTagOption> findVisibleNovelTagCounts(GuestRestriction r, String search, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.tag_id AS tag_id, t.name AS name, t.translated_name AS translated_name,"
                        + " COUNT(*) AS cnt"
                        + " FROM novel_tags nt"
                        + " JOIN novels n ON n.novel_id = nt.novel_id"
                        + " JOIN tags t ON t.tag_id = nt.tag_id"
                        + " WHERE n.deleted = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Tag");
        if (search != null && !search.isBlank()) {
            sql.append(" AND (LOWER(t.name) LIKE :tagSearch"
                    + " OR LOWER(COALESCE(t.translated_name, '')) LIKE :tagSearch)");
            params.addValue("tagSearch", "%" + search.trim().toLowerCase() + "%");
        }
        sql.append(" GROUP BY t.tag_id, t.name, t.translated_name");
        sql.append(" ORDER BY cnt DESC, LOWER(t.name) ASC");
        sql.append(" LIMIT :limit");
        params.addValue("limit", Math.min(Math.max(1, limit), 5000));
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new NovelTagOption(
                rs.getLong("tag_id"),
                rs.getString("name"),
                rs.getString("translated_name"),
                rs.getLong("cnt")));
    }

    /**
     * 在 {@code novels} 别名 {@code n} 上拼装"对该访客可见"的 SQL 片段。
     * 语义与 {@code GalleryRepository.appendVisibilityClauses} 完全一致。
     */
    public void appendVisibilityClauses(StringBuilder sql, MapSqlParameterSource params,
                                        GuestRestriction r, String paramSuffix) {
        if (r == null) return;
        Set<Integer> allowed = r.allowedXRestricts();
        if (allowed == null || allowed.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        List<String> ratingClauses = new ArrayList<>();
        if (allowed.contains(0)) ratingClauses.add("n.\"R18\" = 0 OR n.\"R18\" IS NULL");
        if (allowed.contains(1)) ratingClauses.add("n.\"R18\" = 1");
        if (allowed.contains(2)) ratingClauses.add("n.\"R18\" = 2");
        sql.append(" AND (").append(String.join(" OR ", ratingClauses)).append(")");

        // (1) 不可见维度排除（跨维度优先于 OR 匹配）
        if (!r.tagUnrestricted()) {
            if (r.tagIds() != null && !r.tagIds().isEmpty()) {
                String key = "vNovelTagIds" + paramSuffix;
                sql.append(" AND NOT EXISTS (SELECT 1 FROM novel_tags nt_h"
                        + " WHERE nt_h.novel_id = n.novel_id"
                        + " AND nt_h.tag_id NOT IN (:" + key + "))");
                params.addValue(key, r.tagIds());
            } else {
                sql.append(" AND NOT EXISTS (SELECT 1 FROM novel_tags nt_h"
                        + " WHERE nt_h.novel_id = n.novel_id)");
            }
        }
        if (!r.authorUnrestricted()) {
            if (r.authorIds() != null && !r.authorIds().isEmpty()) {
                String key = "vNovelAuthorIds" + paramSuffix;
                sql.append(" AND (n.author_id IS NULL OR n.author_id IN (:" + key + "))");
                params.addValue(key, r.authorIds());
            } else {
                sql.append(" AND n.author_id IS NULL");
            }
        }

        // (2) OR 正向匹配
        if (r.tagUnrestricted() && r.authorUnrestricted()) return;
        List<String> orClauses = new ArrayList<>();
        if (r.tagUnrestricted()) {
            orClauses.add("1 = 1");
        } else if (r.tagIds() != null && !r.tagIds().isEmpty()) {
            String key = "pNovelTagIds" + paramSuffix;
            orClauses.add("EXISTS (SELECT 1 FROM novel_tags nt_p"
                    + " WHERE nt_p.novel_id = n.novel_id"
                    + " AND nt_p.tag_id IN (:" + key + "))");
            params.addValue(key, r.tagIds());
        }
        if (r.authorUnrestricted()) {
            orClauses.add("1 = 1");
        } else if (r.authorIds() != null && !r.authorIds().isEmpty()) {
            String key = "pNovelAuthorIds" + paramSuffix;
            orClauses.add("n.author_id IN (:" + key + ")");
            params.addValue(key, r.authorIds());
        }
        if (orClauses.isEmpty()) {
            sql.append(" AND 1 = 0");
        } else {
            sql.append(" AND (").append(String.join(" OR ", orClauses)).append(")");
        }
    }

    /** 把对该访客可见的 novelId 限制条件附加到 SQL 中（带 LinkedHashSet 兼容）。 */
    public Set<Long> findVisibleNovelIdSet(GuestRestriction r) {
        return new LinkedHashSet<>(findVisibleNovelIds(r));
    }

    /** 同作者其他小说 ID，按 time 倒序，排除指定小说；软删除行过滤。 */
    public List<Long> findNovelIdsByAuthor(long authorId, long excludeNovelId, int limit) {
        String sql = "SELECT novel_id FROM novels"
                + " WHERE author_id = :authorId AND novel_id <> :excludeId AND deleted = 0"
                + " ORDER BY time DESC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("authorId", authorId)
                .addValue("excludeId", excludeNovelId)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> rs.getLong("novel_id"));
    }

    /**
     * 同系列其他小说 ID，排除指定小说；排序与 {@code NovelDatabase.getNovelsBySeriesId}
     * 一致（{@code series_order ASC, time ASC}），软删除行过滤。
     */
    public List<Long> findNovelIdsBySeries(long seriesId, long excludeNovelId, int limit) {
        String sql = "SELECT novel_id FROM novels"
                + " WHERE series_id = :seriesId AND series_id > 0 AND novel_id <> :excludeId AND deleted = 0"
                + " ORDER BY series_order ASC, time ASC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("seriesId", seriesId)
                .addValue("excludeId", excludeNovelId)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> rs.getLong("novel_id"));
    }

    /**
     * 与给定小说共享至少一个标签的其他小说 ID，按共享标签数降序、时间倒序；
     * 语义镜像插画侧 {@code GalleryRepository.findRelatedByTags}。
     */
    public List<Long> findRelatedByTags(long novelId, int limit) {
        String sql = "SELECT n.novel_id FROM novels n"
                + " JOIN novel_tags nt ON nt.novel_id = n.novel_id"
                + " WHERE nt.tag_id IN (SELECT tag_id FROM novel_tags WHERE novel_id = :id)"
                + " AND n.novel_id <> :id AND n.deleted = 0"
                + " GROUP BY n.novel_id"
                + " ORDER BY COUNT(nt.tag_id) DESC, n.time DESC"
                + " LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", novelId)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> rs.getLong("novel_id"));
    }

    /**
     * 按名称 / 翻译名精确查找小说标签（大小写不敏感，原名命中优先）；
     * 语义镜像插画侧 {@code GalleryRepository.findTagByExactName}，计数为使用数。
     */
    public NovelTagOption findTagByExactName(String name, String translatedName) {
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
                + " COUNT(nt.novel_id) AS novel_count"
                + " FROM tags t"
                + " LEFT JOIN novel_tags nt ON nt.tag_id = t.tag_id"
                + " WHERE " + String.join(" OR ", clauses)
                + " GROUP BY t.tag_id, t.name, t.translated_name"
                + " HAVING novel_count > 0"
                + " ORDER BY " + String.join(", ", orderBy) + ", novel_count DESC, t.tag_id ASC"
                + " LIMIT 1";
        List<NovelTagOption> items = jdbc.query(sql, params, (rs, rowNum) -> new NovelTagOption(
                rs.getLong("tag_id"),
                rs.getString("name"),
                rs.getString("translated_name"),
                rs.getLong("novel_count")));
        return items.isEmpty() ? null : items.get(0);
    }

    private static String normalizeLookup(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
    }
}
