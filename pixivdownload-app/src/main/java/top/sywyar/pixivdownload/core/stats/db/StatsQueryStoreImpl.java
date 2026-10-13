package top.sywyar.pixivdownload.core.stats.db;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.stats.StatsAggregates;
import top.sywyar.pixivdownload.core.stats.StatsQueryStore;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 核心负责统计口径，提供者执行集合合并、分组和时区投影；插件只取得语义聚合。 */
@Repository
public class StatsQueryStoreImpl implements StatsQueryStore {
    private final MainDatabase database;
    public StatsQueryStoreImpl(MainDatabase database) { this.database = database; }

    private static Query values(String table, String field, boolean live, boolean positive, boolean distinct) {
        Expression where = call(NOT, call(IS_NULL, column(table, field)));
        if (live) where = call(AND, where, call(EQUAL, column(table, "deleted"), value(0)));
        if (positive) where = call(AND, where, call(GREATER, column(table, field), value(0)));
        return new Query(new Query.Table(table), List.of(), Map.of(field, column(table, field)), where,
                List.of(), null, List.of(), distinct, Integer.MAX_VALUE, 0);
    }
    private static Query.Union works(String field, boolean positive, boolean distinct) {
        return new Query.Union(List.of(values("artworks", field, true, positive, distinct),
                values("novels", field, true, positive, distinct)), true, "works");
    }
    private static Query.Union tags() {
        return new Query.Union(List.of(values("artwork_tags", "tag_id", false, false, false),
                values("novel_tags", "tag_id", false, false, false)), true, "work_tags");
    }
    private static Query aggregate(Query.Source source, Expression value) {
        return new Query(source, List.of(), Map.of("total", value), null, List.of(), null, List.of(), false, 1, 0);
    }
    private static long total(DatabaseSession session, Query query) throws java.sql.SQLException {
        return (Long) session.query(query).get(0).values().iterator().next();
    }
    private static long number(Map<String, Object> row, String field) { return row.get(field) == null ? 0L : (Long) row.get(field); }

    @Override public StatsAggregates.Overview overview() {
        if (!database.available()) return new StatsAggregates.Overview(0, 0, 0, 0, 0, 0, 0);
        return database.read(s -> {
            var rows = s.query(new Query("statistics", Map.of("total_artworks", column("statistics", "total_artworks"),
                    "total_images", column("statistics", "total_images"), "total_moved", column("statistics", "total_moved")),
                    call(EQUAL, column("statistics", "id"), value(1)), List.of(), List.of(), 1, 0));
            var counts = rows.isEmpty() ? Map.<String, Object>of() : rows.get(0);
            return new StatsAggregates.Overview(number(counts, "total_artworks"), number(counts, "total_images"),
                    number(counts, "total_moved"), total(s, Query.count("novels", call(EQUAL, column("novels", "deleted"), value(0)))),
                    total(s, aggregate(works("author_id", false, false), call(COUNT_DISTINCT, column("works", "author_id")))),
                    total(s, aggregate(tags(), call(COUNT_DISTINCT, column("work_tags", "tag_id")))),
                    total(s, aggregate(works("series_id", true, true), call(COUNT))));
        });
    }

    @Override public List<StatsAggregates.AuthorStat> topAuthors(int limit) {
        if (!database.available() || limit == 0) return List.of();
        var id = column("works", "author_id");
        var name = column("authors", "name");
        var count = call(COUNT);
        var query = new Query(works("author_id", false, false),
                List.of(new Query.Join(Query.JoinKind.LEFT, new Query.Table("authors"), call(EQUAL, id, column("authors", "author_id")))),
                Map.of("id", id, "name", name, "count", count), null, List.of(id, name), null,
                List.of(new Query.Order(count, true, false), new Query.Order(id, false, true)), false, limit, 0);
        return database.read(s -> s.query(query)).stream()
                .map(row -> new StatsAggregates.AuthorStat((Long) row.get("id"), (String) row.get("name"), (Long) row.get("count"))).toList();
    }

    @Override public List<StatsAggregates.TagStat> topTags(int limit) {
        if (!database.available() || limit == 0) return List.of();
        var id = column("tags", "tag_id");
        var name = column("tags", "name");
        var translated = column("tags", "translated_name");
        var count = call(COUNT);
        var query = new Query(new Query.Table("tags"),
                List.of(new Query.Join(Query.JoinKind.INNER, tags(), call(EQUAL, id, column("work_tags", "tag_id")))),
                Map.of("id", id, "name", name, "translated", translated, "count", count), null,
                List.of(id, name, translated), null, List.of(new Query.Order(count, true, false), new Query.Order(id, false, true)),
                false, limit, 0);
        return database.read(s -> s.query(query)).stream().map(row -> new StatsAggregates.TagStat(
                (Long) row.get("id"), (String) row.get("name"), (String) row.get("translated"), (Long) row.get("count"))).toList();
    }

    @Override public List<StatsAggregates.MonthlyStat> monthlyArtworkCounts() {
        if (!database.available()) return List.of();
        var month = call(EPOCH_MONTH, column("works", "time"), value(java.time.ZoneId.systemDefault().getId()));
        var months = new Query(works("time", true, false), List.of(), Map.of("month", month), null,
                List.of(), null, List.of(), false, Integer.MAX_VALUE, 0);
        var key = column("months", "month");
        var query = new Query(new Query.Derived(months, "months"), List.of(), Map.of("month", key, "count", call(COUNT)),
                null, List.of(key), null, List.of(new Query.Order(key, false, true)), false, Integer.MAX_VALUE, 0);
        return database.read(s -> s.query(query)).stream().map(row ->
                new StatsAggregates.MonthlyStat((String) row.get("month"), (Long) row.get("count"))).toList();
    }
}
