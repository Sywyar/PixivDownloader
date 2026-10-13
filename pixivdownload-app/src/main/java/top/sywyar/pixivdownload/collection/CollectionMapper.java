package top.sywyar.pixivdownload.collection;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@Repository
public class CollectionMapper {
    private static final String TABLE = "collections";
    private static final String LINKS = "artwork_collections";
    private final MainDatabase database;
    public CollectionMapper(MainDatabase database) { this.database = database; }

    private int migrateTime(String table, String field) {
        var time = column(table, field);
        return database.transaction(s -> s.update(table, Map.of(field, call(MULTIPLY, time, value(1000))),
                call(AND, call(GREATER, time, value(0)), call(LESS, time, value(1_000_000_000_000L)))));
    }
    public int migrateCollectionTimestampsToMillis() { return migrateTime(TABLE, "created_time"); }
    public int migrateArtworkCollectionTimestampsToMillis() { return migrateTime(LINKS, "added_time"); }
    private static Expression identity(long id) { return call(EQUAL, column(TABLE, "id"), value(id)); }
    private List<top.sywyar.pixivdownload.collection.Collection> find(Expression where, int limit) {
        var fields = new LinkedHashMap<String, Expression>();
        for (String field : List.of("id", "name", "icon_ext", "download_root", "sort_order", "created_time"))
            fields.put(field, column(TABLE, field));
        fields.put("artwork_count", new Scalar(Query.count(LINKS,
                call(EQUAL, column(LINKS, "collection_id"), column(TABLE, "id")))));
        fields.put("novel_count", new Scalar(Query.count("novel_collections",
                call(EQUAL, column("novel_collections", "collection_id"), column(TABLE, "id")))));
        return database.read(s -> s.query(new Query(TABLE, fields, where, List.of(),
                List.of(new Query.Order(column(TABLE, "sort_order"), false, true),
                        new Query.Order(column(TABLE, "id"), false, true)), limit, 0))).stream()
                .map(row -> new top.sywyar.pixivdownload.collection.Collection((Long) row.get("id"), (String) row.get("name"),
                        (String) row.get("icon_ext"), (String) row.get("download_root"), number(row, "sort_order"),
                        (Long) row.get("created_time"), number(row, "artwork_count"), number(row, "novel_count"))).toList();
    }
    private static int number(Map<String, Object> row, String name) {
        return row.get(name) == null ? 0 : Math.toIntExact((Long) row.get(name));
    }
    public List<top.sywyar.pixivdownload.collection.Collection> findAll() { return find(null, Integer.MAX_VALUE); }
    public top.sywyar.pixivdownload.collection.Collection findById(long id) {
        return find(identity(id), 1).stream().findFirst().orElse(null);
    }
    public void insert(CollectionInsert insert) {
        var row = new LinkedHashMap<String, Object>();
        row.put("name", insert.getName()); row.put("icon_ext", insert.getIconExt());
        row.put("download_root", insert.getDownloadRoot()); row.put("sort_order", insert.getSortOrder());
        row.put("created_time", insert.getCreatedTime());
        insert.setId(database.transaction(s -> s.insertGenerated(TABLE, row, "id")));
    }
    private int update(long id, String field, Object value) {
        return database.transaction(s -> s.update(TABLE, Map.of(field, value(value)), identity(id)));
    }
    public int updateName(long id, String name) { return update(id, "name", name); }
    public int updateIconExt(long id, String iconExt) { return update(id, "icon_ext", iconExt); }
    public int updateDownloadRoot(long id, String downloadRoot) { return update(id, "download_root", downloadRoot); }
    public int updateSortOrder(long id, int sortOrder) { return update(id, "sort_order", sortOrder); }
    public int deleteById(long id) { return database.transaction(s -> s.delete(TABLE, identity(id))); }
    public int deleteArtworkLinksByCollection(long id) {
        return database.transaction(s -> s.delete(LINKS, call(EQUAL, column(LINKS, "collection_id"), value(id))));
    }
    public int addArtwork(long collectionId, long artworkId, long addedTime) {
        return database.transaction(s -> s.insert(LINKS, Map.of("collection_id", collectionId, "artwork_id", artworkId,
                "added_time", addedTime), DatabaseSession.Conflict.ignore()));
    }
    public int removeArtwork(long collectionId, long artworkId) {
        return database.transaction(s -> s.delete(LINKS, call(AND,
                call(EQUAL, column(LINKS, "collection_id"), value(collectionId)),
                call(EQUAL, column(LINKS, "artwork_id"), value(artworkId)))));
    }
    public int removeAllArtworkLinks(long artworkId) {
        return database.transaction(s -> s.delete(LINKS, call(EQUAL, column(LINKS, "artwork_id"), value(artworkId))));
    }
    public List<Long> findCollectionIdsByArtwork(long artworkId) {
        return database.read(s -> s.query(new Query(LINKS, Map.of("id", column(LINKS, "collection_id")),
                call(EQUAL, column(LINKS, "artwork_id"), value(artworkId)), List.of(), List.of(), Integer.MAX_VALUE, 0)))
                .stream().map(row -> (Long) row.get("id")).toList();
    }
    private static Expression in(String field, java.util.Collection<Long> ids) {
        var arguments = new ArrayList<Expression>();
        arguments.add(column(LINKS, field)); ids.forEach(id -> arguments.add(value(id)));
        return new Call(IN, arguments);
    }
    public List<Map<String, Object>> findLinksByArtworks(java.util.Collection<Long> artworkIds) {
        if (artworkIds.isEmpty()) return List.of();
        return database.read(s -> s.query(new Query(LINKS, Map.of("artwork_id", column(LINKS, "artwork_id"),
                "collection_id", column(LINKS, "collection_id")), in("artwork_id", artworkIds),
                List.of(), List.of(), Integer.MAX_VALUE, 0))).stream()
                .map(row -> Map.<String, Object>of("artworkId", row.get("artwork_id"), "collectionId", row.get("collection_id"))).toList();
    }
    public List<Long> findArtworkIdsInCollections(java.util.Collection<Long> collectionIds) {
        if (collectionIds.isEmpty()) return List.of();
        return database.read(s -> s.query(new Query(new Query.Table(LINKS), List.of(),
                Map.of("id", column(LINKS, "artwork_id")), in("collection_id", collectionIds),
                List.of(), null, List.of(), true, Integer.MAX_VALUE, 0))).stream().map(row -> (Long) row.get("id")).toList();
    }
    private int count(Expression where) {
        return Math.toIntExact((Long) database.read(s -> s.query(Query.count(TABLE, where))).get(0).get("count"));
    }
    public int countById(long id) { return count(identity(id)); }
    public int countByNameExcludingId(String name, long excludeId) {
        return count(call(AND, call(EQUAL, call(LOWER_ASCII, column(TABLE, "name")), call(LOWER_ASCII, value(name))),
                call(NOT_EQUAL, column(TABLE, "id"), value(excludeId))));
    }
    public int countByName(String name) {
        return count(call(EQUAL, call(LOWER_ASCII, column(TABLE, "name")), call(LOWER_ASCII, value(name))));
    }
}
