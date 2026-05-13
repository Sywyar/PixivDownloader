package top.sywyar.pixivdownload.novel.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.gallery.GuestRestriction;

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
 */
@Slf4j
@Repository
public class NovelGalleryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NovelGalleryRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** 对该访客可见的所有 novelId，按 time 倒序。 */
    public List<Long> findVisibleNovelIds(GuestRestriction r) {
        if (r == null) return Collections.emptyList();
        StringBuilder sql = new StringBuilder("SELECT n.novel_id FROM novels n WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Main");
        sql.append(" ORDER BY n.time DESC");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> rs.getLong("novel_id"));
    }

    /** 该访客可见的所有 novelId 的数量。 */
    public long countVisibleNovels(GuestRestriction r) {
        if (r == null) return 0;
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM novels n WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Count");
        Long total = jdbc.queryForObject(sql.toString(), params, Long.class);
        return total == null ? 0 : total;
    }

    /** 对该访客可见的作者 ID 与对应可见小说数。 */
    public List<NovelAuthorSummary> findVisibleNovelAuthorCounts(GuestRestriction r) {
        if (r == null) return List.of();
        StringBuilder sql = new StringBuilder(
                "SELECT n.author_id AS author_id, COUNT(*) AS cnt FROM novels n"
                        + " WHERE n.author_id IS NOT NULL AND n.author_id > 0");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendVisibilityClauses(sql, params, r, "Author");
        sql.append(" GROUP BY n.author_id");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new NovelAuthorSummary(
                rs.getLong("author_id"),
                null,
                rs.getLong("cnt")));
    }

    /** 对该访客可见的系列 ID 与对应可见小说数。 */
    public List<NovelSeriesSummary> findVisibleNovelSeriesCounts(GuestRestriction r) {
        if (r == null) return List.of();
        StringBuilder sql = new StringBuilder(
                "SELECT n.series_id AS series_id, COUNT(*) AS cnt FROM novels n"
                        + " WHERE n.series_id IS NOT NULL AND n.series_id > 0");
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

    /** 对该访客可见的标签 ID 与对应可见小说数。 */
    public List<NovelTagOption> findVisibleNovelTagCounts(GuestRestriction r, String search, int limit) {
        if (r == null) return List.of();
        StringBuilder sql = new StringBuilder(
                "SELECT t.tag_id AS tag_id, t.name AS name, t.translated_name AS translated_name,"
                        + " COUNT(*) AS cnt"
                        + " FROM novel_tags nt"
                        + " JOIN novels n ON n.novel_id = nt.novel_id"
                        + " JOIN tags t ON t.tag_id = nt.tag_id"
                        + " WHERE 1=1");
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
}
