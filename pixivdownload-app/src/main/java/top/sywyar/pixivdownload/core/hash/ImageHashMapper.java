package top.sywyar.pixivdownload.core.hash;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@Repository
public class ImageHashMapper {
    private static final String TABLE = "artwork_image_hashes";
    private final MainDatabase database;
    public ImageHashMapper(MainDatabase database) { this.database = database; }

    private void write(long artworkId, int page, String ext, long dHash, Long aHash, long createdTime, boolean marker) {
        var row = new LinkedHashMap<String, Object>();
        row.put("artwork_id", artworkId); row.put("page", page); row.put("ext", ext);
        row.put("dhash", dHash); row.put("ahash", aHash); row.put("created_time", createdTime);
        Map<String, Expression> changes = marker ? Map.of("created_time", new Incoming("created_time"))
                : Map.of("ext", new Incoming("ext"), "dhash", new Incoming("dhash"),
                        "ahash", new Incoming("ahash"), "created_time", new Incoming("created_time"));
        database.transaction(s -> s.insert(TABLE, row,
                new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("artwork_id", "page"), changes)));
    }
    public void upsert(long artworkId, int page, String ext, long dHash, Long aHash, long createdTime) {
        write(artworkId, page, ext, dHash, aHash, createdTime, false);
    }
    /** 作品级哨兵排除全部页面的重复尝试，但不参与相似分组。 */
    public void markNoHash(long artworkId, long createdTime) {
        write(artworkId, -1, "", 0, null, createdTime, true);
    }
    /** 页级哨兵与真实页一一对应；相似分组只读取非负页号。 */
    public void markPageNoHash(long artworkId, int page, long createdTime) {
        write(artworkId, Math.toIntExact(-((long) page) - 2L), "", 0, null, createdTime, true);
    }
    public List<ImageHashRow> findAll() {
        var select = new LinkedHashMap<String, Expression>();
        for (String name : List.of("artwork_id", "page", "ext", "dhash", "ahash", "created_time"))
            select.put(name, column("h", name));
        select.put("title", column("a", "title")); select.put("author_id", column("a", "author_id"));
        select.put("author_name", column("au", "name")); select.put("r18", column("a", "r18"));
        var query = new Query(new Query.Table(TABLE, "h"), List.of(
                new Query.Join(Query.JoinKind.INNER, new Query.Table("artworks", "a"),
                        call(EQUAL, column("a", "artwork_id"), column("h", "artwork_id"))),
                new Query.Join(Query.JoinKind.LEFT, new Query.Table("authors", "au"),
                        call(EQUAL, column("au", "author_id"), column("a", "author_id")))),
                select, call(GREATER_EQUAL, column("h", "page"), value(0)), List.of(), null,
                List.of(new Query.Order(column("h", "artwork_id"), false, true), new Query.Order(column("h", "page"), false, true)),
                false, Integer.MAX_VALUE, 0);
        return database.read(s -> s.query(query)).stream().map(row -> new ImageHashRow(
                (Long) row.get("artwork_id"), Math.toIntExact((Long) row.get("page")), (String) row.get("ext"),
                (Long) row.get("dhash"), (Long) row.get("ahash"), (Long) row.get("created_time"), (String) row.get("title"),
                (Long) row.get("author_id"), (String) row.get("author_name"),
                row.get("r18") == null ? null : Math.toIntExact((Long) row.get("r18")))).toList();
    }
    public long countAllHashRows() {
        return (Long) database.read(s -> s.query(Query.count(TABLE, null))).get(0).get("count");
    }
    public Long maxCreatedTime() {
        return (Long) database.read(s -> s.query(new Query(TABLE, Map.of("time", call(MAX, column(TABLE, "created_time"))),
                null, List.of(), List.of(), 1, 0))).get(0).get("time");
    }
    private Expression missing() {
        var id = column("artworks", "artwork_id");
        var count = column("artworks", "count");
        var hashId = call(EQUAL, column("h", "artwork_id"), id);
        var page = column("h", "page");
        var normalized = new Conditional(call(GREATER_EQUAL, page, value(0)), page, call(SUBTRACT, value(-2), page));
        var covered = new Scalar(new Query(new Query.Table(TABLE, "h"), List.of(),
                Map.of("covered", call(COUNT_DISTINCT, normalized)),
                call(AND, hashId, call(GREATER_EQUAL, normalized, value(0)), call(LESS, normalized, count)),
                List.of(), null, List.of(), false, 1, 0));
        var marker = new Exists(new Query(new Query.Table(TABLE, "h"), List.of(), Map.of("found", value(1)),
                call(AND, hashId, call(EQUAL, page, value(-1))), List.of(), null, List.of(), false, 1, 0));
        return call(AND, call(GREATER, count, value(0)), call(EQUAL, column("artworks", "deleted"), value(0)),
                call(NOT, marker), call(LESS, covered, count));
    }
    public int countArtworksMissingHashes() {
        return Math.toIntExact((Long) database.read(s -> s.query(Query.count("artworks", missing()))).get(0).get("count"));
    }
    public List<Long> artworkIdsMissingHashes(int limit) {
        var query = new Query("artworks", Map.of("id", column("artworks", "artwork_id")), missing(), List.of(),
                List.of(new Query.Order(column("artworks", "time"), true, false)), limit, 0);
        return database.read(s -> s.query(query)).stream().map(row -> (Long) row.get("id")).toList();
    }
    public void deleteByArtwork(long artworkId) {
        database.transaction(s -> s.delete(TABLE, call(EQUAL, column(TABLE, "artwork_id"), value(artworkId))));
    }
    public void deleteAll() {
        database.transaction(s -> s.delete(TABLE, call(EQUAL, value(1), value(1))));
    }
}
