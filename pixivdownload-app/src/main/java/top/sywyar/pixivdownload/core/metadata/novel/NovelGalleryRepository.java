package top.sywyar.pixivdownload.core.metadata.novel;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.query.AuthorSummary;
import top.sywyar.pixivdownload.core.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Query;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;
import static top.sywyar.pixivdownload.core.metadata.query.WorkQueryPredicates.*;

/** 核心小说元数据查询，在数据库内应用访客可见范围与聚合。 */
@Repository
public class NovelGalleryRepository {
    private final MainDatabase database;
    public NovelGalleryRepository(MainDatabase database) { this.database = database; }
    private static Expression n(String name) { return column("n", name); }
    private static Expression visibility(WorkRestriction r) {
        return and(eq(n("deleted"), 0), visible("n", "novel_id", "novel_tags", "novel_collections", r));
    }
    private static Query.Order order(Expression expression, boolean descending) {
        return new Query.Order(expression, descending, !descending);
    }
    private static Query.Join join(String table, String alias, Expression on) {
        return new Query.Join(Query.JoinKind.INNER, new Query.Table(table, alias), on);
    }
    private static Query query(Map<String, Expression> select, Expression where, List<Query.Join> joins,
                               List<Expression> groups, List<Query.Order> order, int limit) {
        return new Query(new Query.Table("novels", "n"), joins, select, where, groups, null, order, false, limit, 0);
    }
    private List<Map<String, Object>> rows(Query query) { return database.read(s -> s.query(query)); }
    private List<Long> ids(Query query) { return rows(query).stream().map(row -> (Long) row.get("novel_id")).toList(); }

    public Map<Long, Long> countVisibleWorksByCollection(WorkRestriction r) {
        var counts = new LinkedHashMap<Long, Long>();
        rows(query(Map.of("id", column("c", "collection_id"), "cnt", call(COUNT)), visibility(r),
                List.of(join("novel_collections", "c", call(EQUAL, column("c", "novel_id"), n("novel_id")))),
                List.of(column("c", "collection_id")), List.of(), Integer.MAX_VALUE))
                .forEach(row -> counts.put((Long) row.get("id"), (Long) row.get("cnt")));
        return counts;
    }
    public List<Long> findVisibleNovelIds(WorkRestriction r) {
        return r == null ? List.of() : ids(query(Map.of("novel_id", n("novel_id")), visibility(r),
                List.of(), List.of(), List.of(order(n("time"), true)), Integer.MAX_VALUE));
    }
    public Set<Long> findVisibleNovelIdSet(WorkRestriction r) { return new LinkedHashSet<>(findVisibleNovelIds(r)); }
    public long countVisibleNovels(WorkRestriction r) {
        return r == null ? 0 : (Long) rows(query(Map.of("cnt", call(COUNT)), visibility(r), List.of(),
                List.of(), List.of(), 1)).get(0).get("cnt");
    }
    private List<Map<String, Object>> dimensionCounts(String column, WorkRestriction r) {
        return rows(query(Map.of("id", n(column), "cnt", call(COUNT)),
                and(visibility(r), call(GREATER, n(column), value(0))), List.of(),
                List.of(n(column)), List.of(order(n(column), false)), Integer.MAX_VALUE));
    }
    public List<AuthorSummary> findVisibleNovelAuthorCounts(WorkRestriction r) {
        return dimensionCounts("author_id", r).stream()
                .map(row -> new AuthorSummary((Long) row.get("id"), null, (Long) row.get("cnt"))).toList();
    }
    public Map<Long, Long> countVisibleNovelsBySeries(WorkRestriction r) {
        var counts = new LinkedHashMap<Long, Long>();
        dimensionCounts("series_id", r).forEach(row -> counts.put((Long) row.get("id"), (Long) row.get("cnt")));
        return Collections.unmodifiableMap(counts);
    }
    public List<TagOption> findVisibleNovelTagCounts(WorkRestriction r, String search, int limit) {
        Expression where = visibility(r);
        Expression name = column("t", "name"), translated = column("t", "translated_name"), tagId = column("t", "tag_id");
        if (search != null && !search.isBlank()) {
            String term = search.trim().toLowerCase(Locale.ROOT);
            where = and(where, or(like(call(LOWER_ASCII, name), term),
                    like(call(LOWER_ASCII, call(COALESCE, translated, value(""))), term)));
        }
        return rows(query(Map.of("id", tagId, "name", name, "translated_name", translated, "cnt", call(COUNT)), where,
                List.of(join("novel_tags", "nt", call(EQUAL, column("nt", "novel_id"), n("novel_id"))),
                        join("tags", "t", call(EQUAL, tagId, column("nt", "tag_id")))),
                List.of(tagId, name, translated), List.of(order(call(COUNT), true), order(call(LOWER_ASCII, name), false)),
                Math.min(Math.max(1, limit), 5000))).stream().map(NovelGalleryRepository::tag).toList();
    }
    public List<Long> findNovelIdsByAuthor(long authorId, long excludeNovelId, int limit) {
        if (limit == 0) return List.of();
        return ids(query(Map.of("novel_id", n("novel_id")), and(visibility(null), eq(n("author_id"), authorId),
                not(eq(n("novel_id"), excludeNovelId))), List.of(), List.of(), List.of(order(n("time"), true)), limit));
    }
    public List<Long> findNovelIdsBySeries(long seriesId, long excludeNovelId, int limit) {
        if (limit == 0) return List.of();
        return ids(query(Map.of("novel_id", n("novel_id")), and(visibility(null), eq(n("series_id"), seriesId),
                call(GREATER, n("series_id"), value(0)), not(eq(n("novel_id"), excludeNovelId))), List.of(), List.of(),
                List.of(order(n("series_order"), false), order(n("time"), false)), limit));
    }
    public List<Long> findRelatedByTags(long novelId, int limit) {
        if (limit == 0) return List.of();
        Expression related = exists("novel_tags", "original_tag", and(eq(column("original_tag", "novel_id"), novelId),
                call(EQUAL, column("original_tag", "tag_id"), column("related_tag", "tag_id"))));
        return ids(query(Map.of("novel_id", n("novel_id")), and(visibility(null), not(eq(n("novel_id"), novelId)), related),
                List.of(join("novel_tags", "related_tag", call(EQUAL, column("related_tag", "novel_id"), n("novel_id")))),
                List.of(n("novel_id"), n("time")), List.of(order(call(COUNT), true), order(n("time"), true)), limit));
    }
    public TagOption findTagByExactName(String name, String translatedName) {
        var predicates = new ArrayList<Expression>(); var orders = new ArrayList<Query.Order>();
        for (var entry : List.of(new AbstractMap.SimpleEntry<>("name", name), new AbstractMap.SimpleEntry<>("translated_name", translatedName))) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            Expression match = eq(call(LOWER_ASCII, call(COALESCE, column("t", entry.getKey()), value(""))),
                    entry.getValue().trim().toLowerCase(Locale.ROOT));
            predicates.add(match); orders.add(order(new Conditional(match, value(0), value(1)), false));
        }
        if (predicates.isEmpty()) return null;
        Expression id = column("t", "tag_id"), tagName = column("t", "name"), translated = column("t", "translated_name");
        Expression count = call(COUNT_DISTINCT, column("nt", "novel_id"));
        orders.add(order(count, true)); orders.add(order(id, false));
        var rows = rows(new Query(new Query.Table("tags", "t"),
                List.of(new Query.Join(Query.JoinKind.LEFT, new Query.Table("novel_tags", "nt"),
                        call(EQUAL, column("nt", "tag_id"), id))),
                Map.of("id", id, "name", tagName, "translated_name", translated, "cnt", count),
                combine(OR, predicates), List.of(id, tagName, translated), call(GREATER, count, value(0)), orders, false, 1, 0));
        return rows.isEmpty() ? null : tag(rows.get(0));
    }
    private static TagOption tag(Map<String, Object> row) {
        return new TagOption((Long) row.get("id"), (String) row.get("name"), (String) row.get("translated_name"), (Long) row.get("cnt"));
    }
}
