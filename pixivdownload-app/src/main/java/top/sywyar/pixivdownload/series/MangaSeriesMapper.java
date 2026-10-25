package top.sywyar.pixivdownload.series;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@Repository
public class MangaSeriesMapper {
    private static final String TABLE = "manga_series";
    private final MainDatabase database;
    public MangaSeriesMapper(MainDatabase database) { this.database = database; }
    private static Expression identity(long id) { return call(EQUAL, column(TABLE, "series_id"), value(id)); }

    public int migrateSeriesTimestampsToMillis() {
        var time = column(TABLE, "updated_time");
        return database.transaction(s -> s.update(TABLE, Map.of("updated_time", call(MULTIPLY, time, value(1000))),
                call(AND, call(GREATER, time, value(0)), call(LESS, time, value(1_000_000_000_000L)))));
    }
    public int insertIfAbsent(long id, String title, Long authorId, long updatedTime) {
        var row = new LinkedHashMap<String, Object>();
        row.put("series_id", id); row.put("title", title); row.put("author_id", authorId); row.put("updated_time", updatedTime);
        return database.transaction(s -> s.insert(TABLE, row, DatabaseSession.Conflict.ignore()));
    }
    public int updateInfo(long id, String title, Long authorId, long updatedTime) {
        return database.transaction(s -> s.update(TABLE, Map.of("title", value(title), "author_id", value(authorId),
                "updated_time", value(updatedTime)), identity(id)));
    }
    public int updateMetadata(long id, String description, String coverExt, String coverFolder) {
        return database.transaction(s -> s.update(TABLE, Map.of("description", value(description),
                "cover_ext", value(coverExt), "cover_folder", value(coverFolder)), identity(id)));
    }
    private List<MangaSeries> find(Expression where, int limit) {
        var fields = new LinkedHashMap<String, Expression>();
        for (String field : List.of("series_id", "title", "author_id", "updated_time", "description", "cover_ext", "cover_folder"))
            fields.put(field, column(TABLE, field));
        return database.read(s -> s.query(new Query(TABLE, fields, where, List.of(),
                List.of(new Query.Order(call(LOWER_ASCII, column(TABLE, "title")), false, true),
                        new Query.Order(column(TABLE, "series_id"), false, true)), limit, 0))).stream()
                .map(row -> new MangaSeries((Long) row.get("series_id"), (String) row.get("title"), (Long) row.get("author_id"),
                        row.get("updated_time") == null ? 0L : (Long) row.get("updated_time"), (String) row.get("description"),
                        (String) row.get("cover_ext"), (String) row.get("cover_folder"))).toList();
    }
    public MangaSeries findById(long id) { return find(identity(id), 1).stream().findFirst().orElse(null); }
    public List<MangaSeries> findAll() { return find(null, Integer.MAX_VALUE); }
    public List<MangaSeries> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var arguments = new ArrayList<Expression>();
        arguments.add(column(TABLE, "series_id")); ids.forEach(id -> arguments.add(value(id)));
        return find(new Call(IN, arguments), Integer.MAX_VALUE);
    }
    private Query series(String search, Long seriesId, String sort, int limit, long offset) {
        var id = column("a", "series_id");
        var title = column("ms", "title");
        var author = column("ms", "author_id");
        var authorName = column("au", "name");
        var display = call(COALESCE, title, call(TEXT, id));
        var count = call(COUNT);
        var fields = new LinkedHashMap<String, Expression>();
        fields.put("series_id", id); fields.put("title", display); fields.put("author_id", author);
        fields.put("author_name", authorName); fields.put("artwork_count", count);
        var groups = new ArrayList<Expression>(List.of(id, title, author, authorName));
        if (seriesId != null) for (String field : List.of("updated_time", "description", "cover_ext", "cover_folder")) {
            fields.put(field, column("ms", field)); groups.add(column("ms", field));
        }
        var orders = new ArrayList<Query.Order>();
        if ("artworks".equals(sort)) orders.add(new Query.Order(count, true, false));
        else if (!"seriesId".equals(sort)) orders.add(new Query.Order(call(LOWER_ASCII, display), false, true));
        orders.add(new Query.Order(id, false, true));
        Expression filter = seriesId != null ? call(EQUAL, id, value(seriesId)) : call(OR,
                call(LIKE, title, value(search)), call(LIKE, authorName, value(search)), call(LIKE, call(TEXT, id), value(search)));
        return new Query(new Query.Table("artworks", "a"), List.of(
                new Query.Join(Query.JoinKind.LEFT, new Query.Table(TABLE, "ms"), call(EQUAL, id, column("ms", "series_id"))),
                new Query.Join(Query.JoinKind.LEFT, new Query.Table("authors", "au"), call(EQUAL, author, column("au", "author_id")))),
                fields, call(AND, call(GREATER, id, value(0)), call(EQUAL, column("a", "deleted"), value(0)), filter),
                groups, null, orders, false, limit, offset);
    }
    public long countSeriesWithArtworks(String search) {
        return (Long) database.read(s -> s.query(new Query(new Query.Derived(series(search, null, "seriesId", Integer.MAX_VALUE, 0), "series_with_works"),
                List.of(), Map.of("count", call(COUNT)), null, List.of(), null, List.of(), false, 1, 0))).get(0).get("count");
    }
    public List<MangaSeriesSummary> findSeriesWithArtworks(String search, String sort, int limit, long offset) {
        return database.read(s -> s.query(series(search, null, sort, limit, offset))).stream()
                .map(row -> new MangaSeriesSummary((Long) row.get("series_id"), (String) row.get("title"),
                        (Long) row.get("author_id"), (String) row.get("author_name"), (Long) row.get("artwork_count"))).toList();
    }
    public MangaSeriesDetail findSeriesDetailById(long seriesId) {
        return database.read(s -> s.query(series(null, seriesId, "seriesId", 1, 0))).stream().findFirst()
                .map(row -> new MangaSeriesDetail((Long) row.get("series_id"), (String) row.get("title"),
                        (Long) row.get("author_id"), (String) row.get("author_name"), (Long) row.get("artwork_count"),
                        (Long) row.get("updated_time"), (String) row.get("description"), (String) row.get("cover_ext"),
                        (String) row.get("cover_folder"))).orElse(null);
    }
}
