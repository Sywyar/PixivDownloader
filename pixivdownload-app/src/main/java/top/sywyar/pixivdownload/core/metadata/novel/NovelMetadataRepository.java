package top.sywyar.pixivdownload.core.metadata.novel;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.metadata.query.WorkQueryPredicates;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 核心只读取小说共享事实的窄投影；正文及其派生状态仍由小说插件维护。 */
@Repository
public class NovelMetadataRepository {
    private final MainDatabase database;
    private final PathPrefixCodec paths;
    public NovelMetadataRepository(MainDatabase database, PathPrefixCodec paths) {
        this.database = database;
        this.paths = paths;
    }
    private static Expression equal(String table, String name, Object value) {
        return call(EQUAL, column(table, name), value(value));
    }
    private static Expression in(String table, String name, Collection<Long> values) {
        var args = new ArrayList<Expression>();
        args.add(column(table, name)); values.forEach(v -> args.add(value(v)));
        return new Call(IN, args);
    }
    private static Map<String, Expression> fields(String table, String... names) {
        var fields = new LinkedHashMap<String, Expression>();
        for (var name : names) fields.put(name, column(table, name));
        return fields;
    }
    private List<Map<String, Object>> rows(String table, Expression where, String... columns) {
        return database.read(s -> s.query(new Query(table, fields(table, columns), where,
                List.of(), List.of(), Integer.MAX_VALUE, 0)));
    }
    public String getFileBaseName(long id) {
        var rows = rows("novels", equal("novels", "novel_id", id), "file_base_name");
        return rows.isEmpty() ? null : (String) rows.get(0).get("file_base_name");
    }
    public List<String> getImageFileNames(long id) {
        return rows("novel_images", equal("novel_images", "novel_id", id), "image_id", "ext").stream()
                .map(row -> "embed_" + row.get("image_id") + "." + row.get("ext")).toList();
    }
    private boolean exists(Expression where) {
        return !database.read(s -> s.query(new Query("novels", Map.of("found", value(1)), where,
                List.of(), List.of(), 1, 0))).isEmpty();
    }
    public boolean hasNovel(long novelId) { return exists(equal("novels", "novel_id", novelId)); }
    public boolean hasActiveNovel(long novelId) {
        return exists(call(AND, equal("novels", "novel_id", novelId), equal("novels", "deleted", 0)));
    }
    private List<NovelMetadataRow> novels(Expression where, List<Query.Order> order, int limit) {
        var columns = fields("novels", "novel_id", "title", "folder", "count", "extensions", "time", "r18", "is_ai",
                "author_id", "description", "file_name", "file_author_name_id", "series_id", "series_order",
                "is_original", "cover_ext", "deleted", "upload_time");
        return database.read(s -> s.query(new Query("novels", columns, where, List.of(), order, limit, 0)))
                .stream().map(this::novel).toList();
    }
    public NovelMetadataRow getNovel(long novelId) {
        return novels(equal("novels", "novel_id", novelId), List.of(), 1).stream().findFirst().orElse(null);
    }
    public List<NovelMetadataRow> getNovels(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) return List.of();
        return novels(in("novels", "novel_id", novelIds), List.of(), Integer.MAX_VALUE);
    }
    public List<NovelMetadataRow> getNovelsBySeriesId(long seriesId) {
        return novels(call(AND, equal("novels", "series_id", seriesId), call(GREATER, column("novels", "series_id"), value(0)),
                        equal("novels", "deleted", 0)),
                List.of(new Query.Order(column("novels", "series_order"), false, true),
                        new Query.Order(column("novels", "time"), false, true)), Integer.MAX_VALUE);
    }
    public List<NovelMetadataRow> findSearchCandidates(WorkQuery query) {
        Expression where = call(AND, equal("novels", "deleted", 0),
                WorkQueryPredicates.visible("novels", "novel_id", "novel_tags", "novel_collections", query.restriction()));
        if (query.downloadedFrom() != null) where = call(AND, where,
                call(GREATER_EQUAL, column("novels", "time"), value(query.downloadedFrom())));
        if (query.downloadedBefore() != null) where = call(AND, where,
                call(LESS, column("novels", "time"), value(query.downloadedBefore())));
        if (query.uploadedFrom() != null) where = call(AND, where,
                call(GREATER_EQUAL, column("novels", "upload_time"), value(query.uploadedFrom())));
        if (query.uploadedBefore() != null) where = call(AND, where,
                call(LESS, column("novels", "upload_time"), value(query.uploadedBefore())));
        if (query.collectionIds() != null && !query.collectionIds().isEmpty()) {
            where = call(AND, where, WorkQueryPredicates.exists("novel_collections", "selected_collection",
                    call(AND, call(EQUAL, column("selected_collection", "novel_id"), column("novels", "novel_id")),
                            in("selected_collection", "collection_id", query.collectionIds()))));
        }
        return novels(where, List.of(new Query.Order(column("novels", "time"), true, false)), Integer.MAX_VALUE);
    }
    private NovelMetadataRow novel(Map<String, Object> row) {
        return new NovelMetadataRow((Long) row.get("novel_id"), (String) row.get("title"),
                paths.resolve((String) row.get("folder")), integer(row, "count") == null ? 0 : integer(row, "count"),
                (String) row.get("extensions"), row.get("time") == null ? 0 : (Long) row.get("time"),
                integer(row, "r18"), bool(row, "is_ai"), (Long) row.get("author_id"), (String) row.get("description"),
                (Long) row.get("file_name"), (Long) row.get("file_author_name_id"), (Long) row.get("series_id"),
                (Long) row.get("series_order"), bool(row, "is_original"), (String) row.get("cover_ext"),
                Boolean.TRUE.equals(bool(row, "deleted")), (Long) row.get("upload_time"));
    }
    private List<Map<String, Object>> tags(Expression where) {
        var columns = fields("tags", "tag_id", "name", "translated_name");
        columns.put("novel_id", column("novel_tags", "novel_id"));
        return database.read(s -> s.query(new Query(new Query.Table("novel_tags"), List.of(
                new Query.Join(Query.JoinKind.INNER, new Query.Table("tags"),
                        call(EQUAL, column("novel_tags", "tag_id"), column("tags", "tag_id")))), columns, where,
                List.of(), null, List.of(new Query.Order(column("novel_tags", "novel_id"), false, true),
                        new Query.Order(column("tags", "tag_id"), false, true)), false, Integer.MAX_VALUE, 0)));
    }
    public List<TagDto> getNovelTags(long novelId) {
        return tags(equal("novel_tags", "novel_id", novelId)).stream().map(NovelMetadataRepository::tag).toList();
    }
    public Map<Long, List<TagDto>> getNovelTagsBatch(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) return Map.of();
        var result = new LinkedHashMap<Long, List<TagDto>>();
        for (var row : tags(in("novel_tags", "novel_id", novelIds)))
            result.computeIfAbsent((Long) row.get("novel_id"), ignored -> new ArrayList<>()).add(tag(row));
        return result;
    }
    public NovelSeriesTitleRow getSeries(long seriesId) {
        return rows("novel_series", equal("novel_series", "series_id", seriesId), "series_id", "title")
                .stream().findFirst().map(NovelMetadataRepository::series).orElse(null);
    }
    public List<NovelSeriesTitleRow> getSeriesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return rows("novel_series", in("novel_series", "series_id", ids), "series_id", "title")
                .stream().map(NovelMetadataRepository::series).toList();
    }
    private static NovelSeriesTitleRow series(Map<String, Object> row) {
        return new NovelSeriesTitleRow((Long) row.get("series_id"), (String) row.get("title"));
    }
    public boolean addToCollection(long collectionId, long novelId) {
        return database.transaction(s -> s.insert("novel_collections", Map.of("collection_id", collectionId, "novel_id", novelId,
                "added_time", System.currentTimeMillis()), DatabaseSession.Conflict.ignore())) > 0;
    }
    public boolean removeFromCollection(long collectionId, long novelId) {
        return database.transaction(s -> s.delete("novel_collections", call(AND, equal("novel_collections", "collection_id", collectionId),
                equal("novel_collections", "novel_id", novelId)))) > 0;
    }
    public List<Long> getCollectionIdsForNovel(long novelId) {
        return rows("novel_collections", equal("novel_collections", "novel_id", novelId), "collection_id")
                .stream().map(row -> (Long) row.get("collection_id")).toList();
    }
    public List<Map<String, Object>> findCollectionLinksByNovels(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) return List.of();
        return rows("novel_collections", in("novel_collections", "novel_id", novelIds), "novel_id", "collection_id").stream()
                .map(row -> Map.<String, Object>of("novelId", row.get("novel_id"), "collectionId", row.get("collection_id"))).toList();
    }
    public List<Long> getNovelIdsInCollection(long collectionId) {
        return rows("novel_collections", equal("novel_collections", "collection_id", collectionId), "novel_id")
                .stream().map(row -> (Long) row.get("novel_id")).toList();
    }
    /** 仅标记共享主行；正文和派生数据的清理仍归声明其行为的 owner。 */
    @Transactional
    public void markNovelDeleted(long novelId) {
        database.transaction(s -> s.update("novels", Map.of("deleted", value(1), "metadata_json", value(null)),
                equal("novels", "novel_id", novelId)));
    }
    private static TagDto tag(Map<String, Object> row) {
        var tag = new TagDto((String) row.get("name"), (String) row.get("translated_name"));
        tag.setTagId((Long) row.get("tag_id"));
        return tag;
    }
    private static Integer integer(Map<String, Object> row, String name) {
        return row.get(name) == null ? null : Math.toIntExact((Long) row.get(name));
    }
    private static Boolean bool(Map<String, Object> row, String name) {
        return row.get(name) == null ? null : (Long) row.get(name) != 0;
    }
}
