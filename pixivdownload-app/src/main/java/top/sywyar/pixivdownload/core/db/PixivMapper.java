package top.sywyar.pixivdownload.core.db;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@Repository
public class PixivMapper {
    private static final String ARTWORKS = "artworks";
    private final MainDatabase database;
    public PixivMapper(MainDatabase database) { this.database = database; }

    private static Expression eq(String table, String name, Object value) {
        return call(EQUAL, column(table, name), value(value));
    }
    private static Expression id(long id) { return eq(ARTWORKS, "artwork_id", id); }
    private static Expression active() { return eq(ARTWORKS, "deleted", 0); }
    private static Map<String, Expression> fields(String table, String... names) {
        var result = new LinkedHashMap<String, Expression>();
        for (String name : names) result.put(name, column(table, name));
        return result;
    }
    private List<Map<String, Object>> select(String table, Map<String, Expression> fields, Expression where,
                                             List<Query.Order> order, int limit, long offset) {
        if (limit == 0) return List.of();
        return database.read(s -> s.query(new Query(table, fields, where, List.of(), order, limit, offset)));
    }
    private Object scalar(String table, Expression field, Expression where) {
        var rows = select(table, Map.of("result", field), where, List.of(), 1, 0);
        return rows.isEmpty() ? null : rows.get(0).get("result");
    }
    private int update(String table, Map<String, Expression> values, Expression where) {
        return database.transaction(s -> s.update(table, values, where));
    }
    private void deleteRelated(String table, long artworkId) {
        database.transaction(s -> s.delete(table, eq(table, "artwork_id", artworkId)));
    }
    private static Expression in(String table, String name, Collection<Long> ids) {
        var args = new ArrayList<Expression>(); args.add(column(table, name));
        ids.forEach(id -> args.add(value(id)));
        return new Call(IN, args);
    }
    private static Long bool(Boolean value) { return value == null ? null : value ? 1L : 0L; }
    private static Boolean booleanValue(Object value) { return value == null ? null : (Long) value != 0; }
    private static Integer integer(Object value) { return value == null ? null : Math.toIntExact((Long) value); }
    private static int countValue(Object value) { return value == null ? 0 : Math.toIntExact((Long) value); }

    public List<String> findArtworkFileNames(long artworkId) {
        return select("artwork_file_names", fields("artwork_file_names", "base_name"),
                eq("artwork_file_names", "artwork_id", artworkId),
                List.of(new Query.Order(column("artwork_file_names", "page"), false, true)), Integer.MAX_VALUE, 0)
                .stream().map(row -> (String) row.get("base_name")).toList();
    }
    public void deleteArtworkFileNames(long artworkId) { deleteRelated("artwork_file_names", artworkId); }
    public void insertArtworkFileNames(long artworkId, List<String> names) {
        database.transaction(s -> {
            for (int page = 0; page < names.size(); page++) s.insert("artwork_file_names",
                    Map.of("artwork_id", artworkId, "page", (long) page, "base_name", names.get(page)), DatabaseSession.Conflict.error());
            return null;
        });
    }
    public void deleteArtworkMedia(long artworkId) { deleteRelated("artwork_media", artworkId); }
    public void deleteUnusedArtworkMedia(long artworkId) {
        var count = new Scalar(new Query(ARTWORKS, fields(ARTWORKS, "count"), id(artworkId), List.of(), List.of(), 1, 0));
        database.transaction(s -> s.delete("artwork_media", call(AND, eq("artwork_media", "artwork_id", artworkId),
                call(GREATER_EQUAL, column("artwork_media", "page"), count))));
    }
    public void updateFileNameMaxLength(long id, int length) { update(ARTWORKS, Map.of("file_name_max_length", value(length)), id(id)); }
    public Integer findFileNameMaxLength(long id) { return integer(scalar(ARTWORKS, column(ARTWORKS, "file_name_max_length"), id(id))); }
    public Integer findNovelFileNameMaxLength(long id) {
        return integer(scalar("novels", column("novels", "file_name_max_length"), eq("novels", "novel_id", id)));
    }

    public void ensureDefaultFileNameTemplate(String template) {
        database.transaction(s -> s.insert("file_name_templates", Map.of("id", 1L, "template", template), DatabaseSession.Conflict.ignore()));
    }
    public void initStatistics() {
        database.transaction(s -> s.insert("statistics", Map.of("id", 1L, "total_artworks", 0L, "total_images", 0L, "total_moved", 0L),
                DatabaseSession.Conflict.ignore()));
    }
    private int migrateTime(String name) {
        var time = column(ARTWORKS, name);
        return update(ARTWORKS, Map.of(name, call(MULTIPLY, time, value(1000))),
                call(AND, call(GREATER, time, value(0)), call(LESS, time, value(1_000_000_000_000L))));
    }
    public int migrateArtworkTimestampsToMillis() { return migrateTime("time"); }
    public int migrateArtworkMoveTimestampsToMillis() { return migrateTime("move_time"); }
    public void insertFileNameTemplateIfAbsent(String template) {
        database.transaction(s -> s.insert("file_name_templates", Map.of("template", template), DatabaseSession.Conflict.ignore()));
    }
    public Long findFileNameTemplateId(String template) {
        return (Long) scalar("file_name_templates", column("file_name_templates", "id"), eq("file_name_templates", "template", template));
    }
    public String findFileNameTemplateById(long id) {
        return (String) scalar("file_name_templates", column("file_name_templates", "template"), eq("file_name_templates", "id", id));
    }
    public List<FileNameTemplateRow> findFileNameTemplatesByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return select("file_name_templates", fields("file_name_templates", "id", "template"), in("file_name_templates", "id", ids),
                List.of(), Integer.MAX_VALUE, 0).stream()
                .map(row -> new FileNameTemplateRow((Long) row.get("id"), (String) row.get("template"))).toList();
    }
    public void insertFileAuthorNameIfAbsent(String name) {
        database.transaction(s -> s.insert("file_author_names", Map.of("name", name), DatabaseSession.Conflict.ignore()));
    }
    public Long findFileAuthorNameId(String name) {
        return (Long) scalar("file_author_names", column("file_author_names", "id"), eq("file_author_names", "name", name));
    }
    public String findFileAuthorNameById(long id) {
        return (String) scalar("file_author_names", column("file_author_names", "name"), eq("file_author_names", "id", id));
    }

    private List<ArtworkRecord> artworks(Expression where, int limit) {
        var projection = fields(ARTWORKS, "artwork_id", "title", "folder", "count", "extensions", "time", "moved",
                "move_folder", "move_time", "r18", "is_ai", "author_id", "description", "file_name", "file_author_name_id",
                "series_id", "series_order", "deleted", "upload_time", "is_original");
        return select(ARTWORKS, projection, where, List.of(), limit, 0).stream().map(row -> new ArtworkRecord(
                (Long) row.get("artwork_id"), (String) row.get("title"), (String) row.get("folder"), countValue(row.get("count")),
                (String) row.get("extensions"), (Long) row.get("time"), Boolean.TRUE.equals(booleanValue(row.get("moved"))),
                (String) row.get("move_folder"), (Long) row.get("move_time"), integer(row.get("r18")), booleanValue(row.get("is_ai")),
                (Long) row.get("author_id"), (String) row.get("description"), (Long) row.get("file_name"), (Long) row.get("file_author_name_id"),
                (Long) row.get("series_id"), (Long) row.get("series_order"), Boolean.TRUE.equals(booleanValue(row.get("deleted"))),
                (Long) row.get("upload_time"), booleanValue(row.get("is_original")))).toList();
    }
    public ArtworkRecord findById(long artworkId) { return artworks(id(artworkId), 1).stream().findFirst().orElse(null); }
    public List<ArtworkRecord> findByIds(Collection<Long> ids) { return ids.isEmpty() ? List.of() : artworks(in(ARTWORKS, "artwork_id", ids), Integer.MAX_VALUE); }
    public void insertOrIgnore(long artworkId, String title, String folder, int count, String extensions, long time,
                               Integer xRestrict, Boolean isAi, Long authorId, String description, long fileName,
                               Long fileAuthorNameId, Long seriesId, Long seriesOrder) {
        var row = new LinkedHashMap<String, Object>();
        row.put("artwork_id", artworkId); row.put("title", title); row.put("folder", folder); row.put("count", (long) count);
        row.put("extensions", extensions); row.put("time", time); row.put("r18", xRestrict == null ? null : xRestrict.longValue());
        row.put("is_ai", bool(isAi)); row.put("author_id", authorId); row.put("description", description); row.put("file_name", fileName);
        row.put("file_author_name_id", fileAuthorNameId); row.put("series_id", seriesId); row.put("series_order", seriesOrder);
        database.transaction(s -> s.insert(ARTWORKS, row, DatabaseSession.Conflict.ignore()));
    }
    /** 下载成功后替换落盘事实，清除已经失效的移动位置。 */
    public void updateDownloadedFiles(long artworkId, String folder, int count, String extensions, long time) {
        update(ARTWORKS, Map.of("folder", value(folder), "count", value(count), "extensions", value(extensions), "time", value(time),
                "moved", value(0), "move_folder", value(null), "move_time", value(null)), call(AND, id(artworkId), active()));
    }
    public ArtworkRecord findByNormalizedMoveFolder(String moveFolder) {
        var normalized = call(RTRIM, call(RTRIM, column(ARTWORKS, "move_folder"), value("/")), value("\\"));
        return artworks(call(AND, call(EQUAL, normalized, value(moveFolder)), active()), 1).stream().findFirst().orElse(null);
    }
    public void updateMove(long artworkId, String movePath, long moveTime) {
        update(ARTWORKS, Map.of("moved", value(1), "move_folder", value(movePath), "move_time", value(moveTime)), id(artworkId));
    }
    /** 仅补齐缺失资料；标题和描述的空字符串也视为缺失。 */
    public void updateMetadataIfMissing(long artworkId, String title, Integer xRestrict, Boolean isAi, Long authorId, String description) {
        update(ARTWORKS, Map.of("title", call(COALESCE, call(NULL_IF, column(ARTWORKS, "title"), value("")), value(title)),
                "r18", call(COALESCE, column(ARTWORKS, "r18"), value(xRestrict)),
                "is_ai", call(COALESCE, column(ARTWORKS, "is_ai"), value(bool(isAi))),
                "author_id", call(COALESCE, column(ARTWORKS, "author_id"), value(authorId)),
                "description", call(COALESCE, call(NULL_IF, column(ARTWORKS, "description"), value("")), value(description))), id(artworkId));
    }
    /** 成功重下后刷新有效元数据；null 表示保留已有字段。 */
    public void refreshMetadataAfterDownload(long artworkId, String title, Integer xRestrict, Boolean isAi, Long authorId,
                                            String description, long fileName, Long fileAuthorNameId, Long seriesId, Long seriesOrder) {
        var changes = new LinkedHashMap<String, Expression>();
        changes.put("file_name", value(fileName));
        var values = new LinkedHashMap<String, Object>();
        values.put("title", title); values.put("r18", xRestrict); values.put("is_ai", bool(isAi)); values.put("author_id", authorId);
        values.put("description", description); values.put("file_author_name_id", fileAuthorNameId);
        values.put("series_id", seriesId); values.put("series_order", seriesOrder);
        values.forEach((name, v) -> { if (v != null) changes.put(name, value(v)); });
        update(ARTWORKS, changes, call(AND, id(artworkId), active()));
    }
    public void deleteById(long artworkId) { database.transaction(s -> s.delete(ARTWORKS, id(artworkId))); }
    public void markDeletedById(long artworkId) {
        update(ARTWORKS, Map.of("deleted", value(1), "metadata_json", value(null)), id(artworkId));
    }
    public int deleteIfMarkedDeleted(long artworkId) {
        return database.transaction(s -> s.delete(ARTWORKS, call(AND, id(artworkId), eq(ARTWORKS, "deleted", 1))));
    }
    public void deleteImageHashesByArtwork(long artworkId) { deleteRelated("artwork_image_hashes", artworkId); }
    public void deleteArtworkCollections(long artworkId) { deleteRelated("artwork_collections", artworkId); }
    private long count(Expression where) { return (Long) scalar(ARTWORKS, call(COUNT), where); }
    public int countById(long artworkId) { return Math.toIntExact(count(id(artworkId))); }
    public int countActiveById(long artworkId) { return Math.toIntExact(count(call(AND, id(artworkId), active()))); }
    public int countDeletedById(long artworkId) { return Math.toIntExact(count(call(AND, id(artworkId), eq(ARTWORKS, "deleted", 1)))); }
    public int countByTime(long time) { return Math.toIntExact(count(eq(ARTWORKS, "time", time))); }
    public Long findMaxTime() { return (Long) scalar(ARTWORKS, call(MAX, column(ARTWORKS, "time")), null); }
    public long countAll() { return count(active()); }
    private List<Long> ids(Expression where, boolean time, boolean author, int limit, int offset) {
        var order = new ArrayList<Query.Order>();
        if (author) order.add(new Query.Order(call(COALESCE, column(ARTWORKS, "author_id"), value(Long.MAX_VALUE)), false, true));
        if (time) order.add(new Query.Order(column(ARTWORKS, "time"), true, false));
        return select(ARTWORKS, fields(ARTWORKS, "artwork_id"), where, order, limit, offset)
                .stream().map(row -> (Long) row.get("artwork_id")).toList();
    }
    public List<Long> findAllIds() { return ids(active(), false, false, Integer.MAX_VALUE, 0); }
    public List<Long> findAllIdsSortedByTimeDesc() { return ids(active(), true, false, Integer.MAX_VALUE, 0); }
    public List<Long> findAllIdsSortedByAuthorIdAsc() { return ids(active(), true, true, Integer.MAX_VALUE, 0); }
    public List<Long> findIdsSortedByTimeDescPaged(int size, int offset) { return ids(active(), true, false, size, offset); }
    public List<Long> findIdsSortedByAuthorIdAscPaged(int size, int offset) { return ids(active(), true, true, size, offset); }
    public List<ArtworkRecord> findByTimeBefore(long beforeTime) {
        return artworks(call(AND, call(LESS, column(ARTWORKS, "time"), value(beforeTime)), active()), Integer.MAX_VALUE);
    }
    public void updateAuthorId(long artworkId, long authorId) { update(ARTWORKS, Map.of("author_id", value(authorId)), id(artworkId)); }
    public List<Long> findIdsMissingAuthor() {
        return ids(call(AND, active(), call(IS_NULL, column(ARTWORKS, "author_id"))), false, false, Integer.MAX_VALUE, 0);
    }
    public void updateSeriesInfo(long artworkId, Long seriesId, Long seriesOrder) {
        update(ARTWORKS, Map.of("series_id", value(seriesId), "series_order", value(seriesOrder)), id(artworkId));
    }
    /** 只供显式 CLI 回填；运行期批量请求需要先建立分页和速率限制。 */
    public List<Long> findIdsMissingSeries() {
        return ids(call(AND, active(), call(IS_NULL, column(ARTWORKS, "series_id"))), false, false, Integer.MAX_VALUE, 0);
    }

    public void upsertTag(String name, String translatedName) {
        var row = new LinkedHashMap<String, Object>(); row.put("name", name); row.put("translated_name", translatedName);
        database.transaction(s -> s.insert("tags", row, new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE,
                List.of("name"), Map.of("translated_name", call(COALESCE, column("tags", "translated_name"), new Incoming("translated_name"))))));
    }
    public Long findTagIdByName(String name) { return (Long) scalar("tags", column("tags", "tag_id"), eq("tags", "name", name)); }
    public void insertArtworkTag(long artworkId, long tagId) {
        database.transaction(s -> s.insert("artwork_tags", Map.of("artwork_id", artworkId, "tag_id", tagId), DatabaseSession.Conflict.ignore()));
    }
    public void deleteArtworkTags(long artworkId) { deleteRelated("artwork_tags", artworkId); }
    private List<ArtworkTagRow> tags(Expression where) {
        var projection = fields("t", "tag_id", "name", "translated_name");
        projection.put("artwork_id", column("at", "artwork_id"));
        return database.read(s -> s.query(new Query(new Query.Table("artwork_tags", "at"),
                List.of(new Query.Join(Query.JoinKind.INNER, new Query.Table("tags", "t"),
                        call(EQUAL, column("at", "tag_id"), column("t", "tag_id")))), projection, where,
                List.of(), null, List.of(new Query.Order(column("at", "artwork_id"), false, true),
                        new Query.Order(column("t", "tag_id"), false, true)), false, Integer.MAX_VALUE, 0)))
                .stream().map(row -> new ArtworkTagRow((Long) row.get("artwork_id"), (Long) row.get("tag_id"),
                        (String) row.get("name"), (String) row.get("translated_name"))).toList();
    }
    public List<TagDto> findTagsByArtworkId(long artworkId) {
        return tags(eq("at", "artwork_id", artworkId)).stream()
                .map(row -> new TagDto(row.getTagId(), row.getName(), row.getTranslatedName())).toList();
    }
    public List<ArtworkTagRow> findTagsByArtworkIds(Collection<Long> artworkIds) {
        return artworkIds.isEmpty() ? List.of() : tags(in("at", "artwork_id", artworkIds));
    }
    public Integer existsTagsForArtwork(long artworkId) {
        return integer(scalar("artwork_tags", value(1), eq("artwork_tags", "artwork_id", artworkId)));
    }

    public StatisticsData getStats() {
        var rows = select("statistics", fields("statistics", "total_artworks", "total_images", "total_moved",
                "daily_date", "daily_completed", "daily_failed"), eq("statistics", "id", 1), List.of(), 1, 0);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        return new StatisticsData(countValue(row.get("total_artworks")), countValue(row.get("total_images")),
                countValue(row.get("total_moved")), (String) row.get("daily_date"),
                countValue(row.get("daily_completed")), countValue(row.get("daily_failed")));
    }
    private Expression daily(String name, String date, boolean increment) {
        Expression current = call(COALESCE, column("statistics", name), value(0));
        if (increment) current = call(ADD, current, value(1));
        return new Conditional(eq("statistics", "daily_date", date), current, value(increment ? 1 : 0));
    }
    public void recordCompletedDownload(int imageCount, String date) {
        update("statistics", Map.of("total_artworks", call(ADD, column("statistics", "total_artworks"), value(1)),
                "total_images", call(ADD, column("statistics", "total_images"), value(imageCount)),
                "daily_completed", daily("daily_completed", date, true), "daily_failed", daily("daily_failed", date, false),
                "daily_date", value(date)), eq("statistics", "id", 1));
    }
    public void recordFailedDownload(String date) {
        update("statistics", Map.of("daily_completed", daily("daily_completed", date, false),
                "daily_failed", daily("daily_failed", date, true), "daily_date", value(date)), eq("statistics", "id", 1));
    }
    public void incrementMoved() {
        update("statistics", Map.of("total_moved", call(ADD, column("statistics", "total_moved"), value(1))), eq("statistics", "id", 1));
    }
    public void setStats(int totalArtworks, int totalImages, int totalMoved) {
        update("statistics", Map.of("total_artworks", value(totalArtworks), "total_images", value(totalImages), "total_moved", value(totalMoved)),
                eq("statistics", "id", 1));
    }
}
