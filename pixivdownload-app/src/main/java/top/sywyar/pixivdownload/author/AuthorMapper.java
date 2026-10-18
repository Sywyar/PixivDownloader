package top.sywyar.pixivdownload.author;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@Repository
public class AuthorMapper {
    private static final String TABLE = "authors";
    private final MainDatabase database;
    public AuthorMapper(MainDatabase database) { this.database = database; }

    public int migrateAuthorTimestampsToMillis() {
        var time = column(TABLE, "updated_time");
        return database.transaction(s -> s.update(TABLE, Map.of("updated_time", call(MULTIPLY, time, value(1000))),
                call(AND, call(GREATER, time, value(0)), call(LESS, time, value(1_000_000_000_000L)))));
    }
    public int insertIfAbsent(long id, String name, long updatedTime) {
        var row = new LinkedHashMap<String, Object>();
        row.put("author_id", id); row.put("name", name); row.put("updated_time", updatedTime);
        return database.transaction(s -> s.insert(TABLE, row, DatabaseSession.Conflict.ignore()));
    }
    public int updateName(long id, String name, long updatedTime) {
        return database.transaction(s -> s.update(TABLE, Map.of("name", value(name), "updated_time", value(updatedTime)),
                call(EQUAL, column(TABLE, "author_id"), value(id))));
    }
    private List<Author> authors(Expression where, List<Query.Order> order, int limit) {
        return database.read(s -> s.query(new Query(TABLE, Map.of("author_id", column(TABLE, "author_id"),
                "name", column(TABLE, "name"), "updated_time", column(TABLE, "updated_time")),
                where, List.of(), order, limit, 0))).stream()
                .map(row -> new Author((Long) row.get("author_id"), (String) row.get("name"),
                        row.get("updated_time") == null ? 0L : (Long) row.get("updated_time"))).toList();
    }
    public Author findById(long id) {
        return authors(call(EQUAL, column(TABLE, "author_id"), value(id)), List.of(), 1).stream().findFirst().orElse(null);
    }
    public List<Author> findAll() {
        return authors(null, List.of(new Query.Order(call(LOWER_ASCII, column(TABLE, "name")), false, true),
                new Query.Order(column(TABLE, "author_id"), false, true)), Integer.MAX_VALUE);
    }
    public List<Author> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var arguments = new ArrayList<Expression>();
        arguments.add(column(TABLE, "author_id")); ids.forEach(id -> arguments.add(value(id)));
        return authors(new Call(IN, arguments), List.of(), Integer.MAX_VALUE);
    }
    private Query artworkAuthors(String search, String sort, int limit, long offset) {
        var id = column("a", "author_id");
        var name = column("au", "name");
        var display = call(COALESCE, name, call(TEXT, id));
        var count = call(COUNT);
        var orders = new ArrayList<Query.Order>();
        if ("artworks".equals(sort)) orders.add(new Query.Order(count, true, false));
        else if (!"authorId".equals(sort)) orders.add(new Query.Order(call(LOWER_ASCII, display), false, true));
        orders.add(new Query.Order(id, false, true));
        return new Query(new Query.Table("artworks", "a"),
                List.of(new Query.Join(Query.JoinKind.LEFT, new Query.Table(TABLE, "au"), call(EQUAL, id, column("au", "author_id")))),
                Map.of("author_id", id, "name", display, "artwork_count", count),
                call(AND, call(NOT, call(IS_NULL, id)), call(EQUAL, column("a", "deleted"), value(0)),
                        call(OR, call(LIKE, name, value(search)), call(LIKE, call(TEXT, id), value(search)))),
                List.of(id, name), null, orders, false, limit, offset);
    }
    public long countAuthorsWithArtworks(String search) {
        var query = new Query(new Query.Derived(artworkAuthors(search, "authorId", Integer.MAX_VALUE, 0), "authors_with_works"),
                List.of(), Map.of("count", call(COUNT)), null, List.of(), null, List.of(), false, 1, 0);
        return (Long) database.read(s -> s.query(query)).get(0).get("count");
    }
    public List<AuthorSummary> findAuthorsWithArtworks(String search, String sort, int limit, long offset) {
        return database.read(s -> s.query(artworkAuthors(search, sort, limit, offset))).stream()
                .map(row -> new AuthorSummary((Long) row.get("author_id"), (String) row.get("name"), (Long) row.get("artwork_count"))).toList();
    }
}
