package top.sywyar.pixivdownload.core.metadata.novel;

import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.TagDto;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心侧的小说数据访问仓库：把 {@code novels} 系核心表的窄行投影、标签 / 内嵌图 / 译文语言 /
 * 系列 / 收藏夹链接的读取与软删除主行标记从 {@code novel.db.NovelDatabase} 沿
 * 「查询 vs 持久化」边界拆出，使核心查询 / 资产 / 收藏 / 计划 / 访客可见性链路无需反向
 * import 小说插件包。宿主行投影不读取正文 {@code raw_content}；完整持久化行与正文读取由
 * 小说插件自己的 {@code NovelMapper} 负责。
 *
 * <p>卸载投影：{@code novels} 系表为核心所有（小说插件未安装时仍存在），故该读取面随核心
 * 永驻、作为根包扫描的核心 Bean；下载 / 正文 / 翻译 / TTS 的完整行读取、写入与 FTS
 * 索引维护均留小说插件。宿主软删除只更新主行；小说插件拥有的数据库触发器原子清理普通关系，
 * FTS 查询过滤软删除行并由小说插件启动时 best-effort 回收陈旧索引。
 */
@Repository
public class NovelMetadataRepository {

    private static final String SELECT_NOVEL_METADATA =
            "SELECT novel_id AS novelId, title, folder, count, extensions, time,"
                    + " \"R18\" AS xRestrict, is_ai AS isAi, author_id AS authorId, description,"
                    + " file_name AS fileName, file_author_name_id AS fileAuthorNameId,"
                    + " series_id AS seriesId, series_order AS seriesOrder,"
                    + " word_count AS wordCount, text_length AS textLength,"
                    + " reading_time_seconds AS readingTimeSeconds, page_count AS pageCount,"
                    + " is_original AS isOriginal, x_language AS xLanguage, cover_ext AS coverExt,"
                    + " deleted, upload_time AS uploadTime"
                    + " FROM novels";

    private static final String SELECT_SERIES_METADATA =
            "SELECT series_id AS seriesId, title, author_id AS authorId, cover_ext AS coverExt"
                    + " FROM novel_series";

    private final NamedParameterJdbcTemplate jdbc;
    private final PathPrefixCodec pathPrefixCodec;

    public NovelMetadataRepository(DataSource dataSource, PathPrefixCodec pathPrefixCodec) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.pathPrefixCodec = pathPrefixCodec;
    }

    // ── Novel rows ───────────────────────────────────────────────────────────────

    public boolean hasNovel(long novelId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM novels WHERE novel_id = :novelId",
                new MapSqlParameterSource("novelId", novelId), Long.class);
        return count != null && count > 0;
    }

    /** 是否存在未被软删除的记录；deleted 行视为不存在（可重新下载）。 */
    public boolean hasActiveNovel(long novelId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM novels WHERE novel_id = :novelId AND deleted = 0",
                new MapSqlParameterSource("novelId", novelId), Long.class);
        return count != null && count > 0;
    }

    public NovelMetadataRow getNovel(long novelId) {
        List<NovelMetadataRow> rows = jdbc.query(SELECT_NOVEL_METADATA + " WHERE novel_id = :novelId",
                new MapSqlParameterSource("novelId", novelId), this::mapNovelMetadata);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 批量取小说行（含软删除行，路径前缀已解析）；返回顺序不保证，由调用方按需重排。 */
    public List<NovelMetadataRow> getNovels(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(SELECT_NOVEL_METADATA + " WHERE novel_id IN (:ids)",
                new MapSqlParameterSource("ids", novelIds), this::mapNovelMetadata);
    }

    /** 同系列其他小说（{@code series_order ASC, time ASC}），软删除行过滤。 */
    public List<NovelMetadataRow> getNovelsBySeriesId(long seriesId) {
        return jdbc.query(SELECT_NOVEL_METADATA
                        + " WHERE series_id = :seriesId AND series_id > 0 AND deleted = 0"
                        + " ORDER BY series_order ASC, time ASC",
                new MapSqlParameterSource("seriesId", seriesId), this::mapNovelMetadata);
    }

    public List<Long> getAllNovelIdsSortedByTimeDesc() {
        return jdbc.query("SELECT novel_id FROM novels WHERE deleted = 0 ORDER BY time DESC",
                new MapSqlParameterSource(), (rs, rowNum) -> rs.getLong("novel_id"));
    }

    private NovelMetadataRow mapNovelMetadata(ResultSet rs, int rowNum) throws SQLException {
        return new NovelMetadataRow(
                rs.getLong("novelId"),
                rs.getString("title"),
                pathPrefixCodec.resolve(rs.getString("folder")),
                rs.getInt("count"),
                rs.getString("extensions"),
                rs.getLong("time"),
                getInteger(rs, "xRestrict"),
                getBoolean(rs, "isAi"),
                getLongObj(rs, "authorId"),
                rs.getString("description"),
                getLongObj(rs, "fileName"),
                getLongObj(rs, "fileAuthorNameId"),
                getLongObj(rs, "seriesId"),
                getLongObj(rs, "seriesOrder"),
                getInteger(rs, "wordCount"),
                getInteger(rs, "textLength"),
                getInteger(rs, "readingTimeSeconds"),
                getInteger(rs, "pageCount"),
                getBoolean(rs, "isOriginal"),
                rs.getString("xLanguage"),
                rs.getString("coverExt"),
                rs.getBoolean("deleted"),
                getLongObj(rs, "uploadTime"));
    }

    /**
     * 写入小说上传时间列投影（{@code upload_time} 毫秒，nullable）。sidecar 是权威落点、本列为可
     * 重建投影；行不存在为 no-op，写失败由调用方 warn-continue 自愈。核心写 novels
     * 表（与 {@link #markNovelDeleted} 同属核心表写入面，不反向 import 小说插件包）。
     */
    public void updateNovelUploadTime(long novelId, Long uploadTime) {
        jdbc.update("UPDATE novels SET upload_time = :uploadTime WHERE novel_id = :novelId",
                new MapSqlParameterSource()
                        .addValue("uploadTime", uploadTime)
                        .addValue("novelId", novelId));
    }

    // ── Tags ──────────────────────────────────────────────────────────────────────

    public List<TagDto> getNovelTags(long novelId) {
        return jdbc.query(
                "SELECT t.tag_id AS tagId, t.name AS name, t.translated_name AS translatedName"
                        + " FROM novel_tags nt JOIN tags t ON t.tag_id = nt.tag_id"
                        + " WHERE nt.novel_id = :novelId"
                        + " ORDER BY t.tag_id",
                new MapSqlParameterSource("novelId", novelId), NovelMetadataRepository::mapTag);
    }

    /** 批量取多本小说的标签，按 novelId 分组；无标签的小说不出现在结果中。 */
    public Map<Long, List<TagDto>> getNovelTagsBatch(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) return Collections.emptyMap();
        return jdbc.query(
                "SELECT nt.novel_id AS novelId, t.tag_id AS tagId, t.name AS name,"
                        + " t.translated_name AS translatedName"
                        + " FROM novel_tags nt JOIN tags t ON t.tag_id = nt.tag_id"
                        + " WHERE nt.novel_id IN (:ids)"
                        + " ORDER BY nt.novel_id, t.tag_id",
                new MapSqlParameterSource("ids", novelIds),
                (ResultSetExtractor<Map<Long, List<TagDto>>>) rs -> groupTagsByKey(rs, "novelId"));
    }

    /** 单个系列的系列标签（{@code tag_id} 升序）；与批量版 {@link #getNovelSeriesTagsBatch} 同源 SQL。 */
    public List<TagDto> getNovelSeriesTags(long seriesId) {
        return jdbc.query(
                "SELECT t.tag_id AS tagId, t.name AS name, t.translated_name AS translatedName"
                        + " FROM novel_series_tags nst JOIN tags t ON t.tag_id = nst.tag_id"
                        + " WHERE nst.series_id = :seriesId"
                        + " ORDER BY t.tag_id",
                new MapSqlParameterSource("seriesId", seriesId), NovelMetadataRepository::mapTag);
    }

    /** 批量取多个系列的系列标签，按 seriesId 分组；无标签的系列不出现在结果中。 */
    public Map<Long, List<TagDto>> getNovelSeriesTagsBatch(Collection<Long> seriesIds) {
        if (seriesIds == null || seriesIds.isEmpty()) return Collections.emptyMap();
        return jdbc.query(
                "SELECT nst.series_id AS seriesId, t.tag_id AS tagId, t.name AS name,"
                        + " t.translated_name AS translatedName"
                        + " FROM novel_series_tags nst JOIN tags t ON t.tag_id = nst.tag_id"
                        + " WHERE nst.series_id IN (:ids)"
                        + " ORDER BY nst.series_id, t.tag_id",
                new MapSqlParameterSource("ids", seriesIds),
                (ResultSetExtractor<Map<Long, List<TagDto>>>) rs -> groupTagsByKey(rs, "seriesId"));
    }

    // ── Embedded images / translation langs (batch) ───────────────────────────────

    /** 批量取多本小说的内嵌图片 id，按 novelId 分组；无内嵌图的小说不出现在结果中。 */
    public Map<Long, List<String>> getNovelImageIdsBatch(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) return Collections.emptyMap();
        return jdbc.query(
                "SELECT novel_id AS novelId, image_id AS imageId FROM novel_images"
                        + " WHERE novel_id IN (:ids)"
                        + " ORDER BY novel_id",
                new MapSqlParameterSource("ids", novelIds),
                (ResultSetExtractor<Map<Long, List<String>>>) rs -> groupStringsByNovelId(rs, "imageId"));
    }

    /** 批量取多本小说已存在译文的语言代码，按 novelId 分组；无译文的小说不出现在结果中。 */
    public Map<Long, List<String>> getTranslationLangsBatch(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) return Collections.emptyMap();
        return jdbc.query(
                "SELECT novel_id AS novelId, lang_code AS langCode FROM novel_translations"
                        + " WHERE novel_id IN (:ids)"
                        + " ORDER BY novel_id, lang_code",
                new MapSqlParameterSource("ids", novelIds),
                (ResultSetExtractor<Map<Long, List<String>>>) rs -> groupStringsByNovelId(rs, "langCode"));
    }

    // ── Series ─────────────────────────────────────────────────────────────────────

    public NovelSeriesMetadataRow getSeries(long seriesId) {
        List<NovelSeriesMetadataRow> rows = jdbc.query(SELECT_SERIES_METADATA + " WHERE series_id = :id",
                new MapSqlParameterSource("id", seriesId), this::mapSeriesMetadata);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<NovelSeriesMetadataRow> getSeriesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return jdbc.query(SELECT_SERIES_METADATA + " WHERE series_id IN (:ids)",
                new MapSqlParameterSource("ids", ids), this::mapSeriesMetadata);
    }

    private NovelSeriesMetadataRow mapSeriesMetadata(ResultSet rs, int rowNum) throws SQLException {
        return new NovelSeriesMetadataRow(
                rs.getLong("seriesId"),
                rs.getString("title"),
                getLongObj(rs, "authorId"),
                rs.getString("coverExt"));
    }

    // ── Collections ─────────────────────────────────────────────────────────────────

    public boolean addToCollection(long collectionId, long novelId) {
        int rows = jdbc.update(
                "INSERT OR IGNORE INTO novel_collections(collection_id, novel_id, added_time)"
                        + " VALUES(:collectionId, :novelId, :addedTime)",
                new MapSqlParameterSource()
                        .addValue("collectionId", collectionId)
                        .addValue("novelId", novelId)
                        .addValue("addedTime", System.currentTimeMillis()));
        return rows > 0;
    }

    public boolean removeFromCollection(long collectionId, long novelId) {
        int rows = jdbc.update(
                "DELETE FROM novel_collections WHERE collection_id = :collectionId AND novel_id = :novelId",
                new MapSqlParameterSource()
                        .addValue("collectionId", collectionId)
                        .addValue("novelId", novelId));
        return rows > 0;
    }

    public List<Long> getCollectionIdsForNovel(long novelId) {
        return jdbc.query("SELECT collection_id FROM novel_collections WHERE novel_id = :novelId",
                new MapSqlParameterSource("novelId", novelId),
                (rs, rowNum) -> rs.getLong("collection_id"));
    }

    public List<Map<String, Object>> findCollectionLinksByNovels(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT novel_id AS novelId, collection_id AS collectionId FROM novel_collections"
                        + " WHERE novel_id IN (:ids)",
                new MapSqlParameterSource("ids", novelIds),
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>(2);
                    row.put("novelId", rs.getLong("novelId"));
                    row.put("collectionId", rs.getLong("collectionId"));
                    return row;
                });
    }

    public List<Long> getNovelIdsInCollection(long collectionId) {
        return jdbc.query("SELECT novel_id FROM novel_collections WHERE collection_id = :collectionId",
                new MapSqlParameterSource("collectionId", collectionId),
                (rs, rowNum) -> rs.getLong("novel_id"));
    }

    // ── Soft delete ──────────────────────────────────────────────────────────────────

    /**
     * 软删除主行：主行保留并置 {@code deleted = 1}，使下载判重能识别「已下载过，但被删除」。
     * 小说插件拥有的软删除触发器在同一 SQLite 语句内清理标签 / 收藏 / 内嵌图 / 译文 / 朗读脚本；
     * FTS 作为可再生辅助数据过滤软删除行并由小说插件启动时 best-effort 回收。宿主不得读取或维护
     * 这些私有正文及派生状态。
     */
    @Transactional
    public void markNovelDeleted(long novelId) {
        jdbc.update("UPDATE novels SET deleted = 1 WHERE novel_id = :novelId",
                new MapSqlParameterSource("novelId", novelId));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private static TagDto mapTag(ResultSet rs, int rowNum) throws SQLException {
        TagDto tag = new TagDto(rs.getString("name"), rs.getString("translatedName"));
        long tagId = rs.getLong("tagId");
        tag.setTagId(rs.wasNull() ? null : tagId);
        return tag;
    }

    private static Map<Long, List<TagDto>> groupTagsByKey(ResultSet rs, String keyColumn)
            throws SQLException {
        Map<Long, List<TagDto>> out = new LinkedHashMap<>();
        while (rs.next()) {
            long key = rs.getLong(keyColumn);
            TagDto tag = new TagDto(rs.getString("name"), rs.getString("translatedName"));
            long tagId = rs.getLong("tagId");
            tag.setTagId(rs.wasNull() ? null : tagId);
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(tag);
        }
        return out;
    }

    private static Map<Long, List<String>> groupStringsByNovelId(ResultSet rs, String valueColumn)
            throws SQLException {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        while (rs.next()) {
            long novelId = rs.getLong("novelId");
            out.computeIfAbsent(novelId, k -> new ArrayList<>()).add(rs.getString(valueColumn));
        }
        return out;
    }

    private static Integer getInteger(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long getLongObj(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Boolean getBoolean(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }
}
