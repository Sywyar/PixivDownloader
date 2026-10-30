package top.sywyar.pixivdownload.core.metadata.artwork;

import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Query;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;
import static top.sywyar.pixivdownload.core.metadata.query.WorkQueryPredicates.*;

/** 核心插画查询；筛选、计数和访客权限在数据库内执行。 */
@Repository
public class GalleryRepository {
    private final MainDatabase database;
    public GalleryRepository(MainDatabase database) { this.database = database; }
    private static Expression a(String name) { return column("a", name); }
    private static Query.Order order(Expression e, boolean desc) { return new Query.Order(e, desc, !desc); }
    private static Query.Join join(Query.JoinKind kind, String table, String alias, Expression on) {
        return new Query.Join(kind, new Query.Table(table, alias), on);
    }
    private static Expression active() { return eq(a("deleted"), 0); }
    private static Expression visibility(WorkRestriction r) {
        return visible("a", "artwork_id", "artwork_tags", "artwork_collections", r);
    }
    private static Query query(Map<String, Expression> select, Expression where, List<Query.Join> joins,
                               List<Expression> groups, Expression having, List<Query.Order> order,
                               boolean distinct, int limit, long offset) {
        return new Query(new Query.Table("artworks", "a"), joins, select, where, groups, having, order, distinct, limit, offset);
    }
    private List<Map<String, Object>> rows(Query query) { return database.read(s -> s.query(query)); }
    private List<Long> ids(Query query) { return rows(query).stream().map(row -> (Long) row.get("artwork_id")).toList(); }
    private long count(Expression where) {
        return (Long) rows(query(Map.of("cnt", call(COUNT)), where, List.of(), List.of(), null, List.of(), false, 1, 0))
                .get(0).get("cnt");
    }
    private static List<Query.Join> authorJoin() {
        return List.of(join(Query.JoinKind.LEFT, "authors", "au", call(EQUAL, column("au", "author_id"), a("author_id"))));
    }
    public QueryResult findArtworkIds(WorkQuery q) {
        Expression where = filter(q);
        long total = (Long) rows(query(Map.of("cnt", call(COUNT)), where, authorJoin(),
                List.of(), null, List.of(), false, 1, 0)).get(0).get("cnt");
        if (total == 0) return new QueryResult(List.of(), 0);
        return new QueryResult(ids(query(Map.of("artwork_id", a("artwork_id")), where, authorJoin(), List.of(),
                null, ordering(q), false, q.size(), Math.multiplyExact((long) q.page(), q.size()))), total);
    }
    public List<Long> findAllArtworkIds(WorkQuery q) {
        return ids(query(Map.of("artwork_id", a("artwork_id")), filter(q), authorJoin(), List.of(),
                null, ordering(q), false, Integer.MAX_VALUE, 0));
    }
    private Expression filter(WorkQuery q) {
        var conditions = new ArrayList<Expression>();
        conditions.add(active());
        if (q.downloadedFrom() != null) conditions.add(call(GREATER_EQUAL, a("time"), value(q.downloadedFrom())));
        if (q.downloadedBefore() != null) conditions.add(call(LESS, a("time"), value(q.downloadedBefore())));
        if (q.uploadedFrom() != null) conditions.add(call(GREATER_EQUAL, a("upload_time"), value(q.uploadedFrom())));
        if (q.uploadedBefore() != null) conditions.add(call(LESS, a("upload_time"), value(q.uploadedBefore())));
        if (q.search() != null) {
            String search = q.search();
            String type = q.searchType() == null ? "all" : q.searchType();
            conditions.add(switch (type) {
                case "title" -> like(a("title"), search);
                case "author" -> like(column("au", "name"), search);
                case "desc" -> like(a("description"), search);
                case "id", "authorId" -> {
                    Long id = parseLongOrNull(search);
                    yield id == null ? truth(false) : eq(a(type.equals("id") ? "artwork_id" : "author_id"), id);
                }
                case "tag", "tagExact" -> tagSearch(search, type.equals("tagExact"));
                default -> or(like(a("title"), search), like(column("au", "name"), search));
            });
        }
        if ("r18g".equals(q.r18())) conditions.add(eq(a("r18"), 2));
        else if ("r18".equals(q.r18()) || "yes".equals(q.r18())) conditions.add(eq(a("r18"), 1));
        else if ("r18plus".equals(q.r18())) conditions.add(call(GREATER_EQUAL, a("r18"), value(1)));
        else if ("no".equals(q.r18())) conditions.add(or(eq(a("r18"), 0), nil(a("r18"))));
        if ("yes".equals(q.ai())) conditions.add(eq(a("is_ai"), 1));
        else if ("no".equals(q.ai())) conditions.add(or(eq(a("is_ai"), 0), nil(a("is_ai"))));
        if (present(q.formats())) conditions.add(combine(OR, q.formats().stream()
                .map(format -> like(a("extensions"), format)).toList()));
        if (present(q.collectionIds())) {
            WorkRestriction restriction = q.restriction();
            if (restriction != null && q.collectionIds().stream().anyMatch(id -> id == null || !restriction.isCollectionVisible(id))) {
                conditions.add(truth(false));
            }
            conditions.add(exists("artwork_collections", "selected_collection", and(
                    call(EQUAL, column("selected_collection", "artwork_id"), a("artwork_id")),
                    in(column("selected_collection", "collection_id"), q.collectionIds()))));
        }
        if (present(q.excludedTagIds())) conditions.add(not(tags(q.excludedTagIds(), false)));
        if (present(q.excludedAuthorIds())) conditions.add(or(nil(a("author_id")), not(in(a("author_id"), q.excludedAuthorIds()))));
        var positive = new ArrayList<Expression>();
        Expression requiredTags = present(q.tagIds()) ? tags(q.tagIds(), true) : null;
        Expression requiredAuthors = present(q.authorIds()) ? in(a("author_id"), q.authorIds()) : null;
        if (requiredTags != null || requiredAuthors != null) positive.add(and(requiredTags, requiredAuthors));
        if (present(q.optionalTagIds())) positive.add(tags(q.optionalTagIds(), false));
        if (present(q.optionalAuthorIds())) positive.add(in(a("author_id"), q.optionalAuthorIds()));
        if (!positive.isEmpty()) conditions.add(combine(OR, positive));
        if (present(q.seriesIds())) conditions.add(in(a("series_id"), q.seriesIds()));
        if (present(q.excludedSeriesIds())) conditions.add(or(nil(a("series_id")), not(in(a("series_id"), q.excludedSeriesIds()))));
        conditions.add(visibility(q.restriction()));
        return combine(AND, conditions);
    }
    private static boolean present(Collection<?> items) { return items != null && !items.isEmpty(); }
    private static Long parseLongOrNull(String value) {
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException failure) { return null; }
    }
    private static Expression tags(List<Long> ids, boolean all) {
        Expression where = and(call(EQUAL, column("selected_tag", "artwork_id"), a("artwork_id")),
                in(column("selected_tag", "tag_id"), ids));
        if (!all) return exists("artwork_tags", "selected_tag", where);
        return eq(new Scalar(new Query(new Query.Table("artwork_tags", "selected_tag"), List.of(),
                Map.of("cnt", call(COUNT_DISTINCT, column("selected_tag", "tag_id"))), where,
                List.of(), null, List.of(), false, 1, 0)), ids.size());
    }
    private static Expression tagSearch(String text, boolean exact) {
        Expression name = column("t", "name"), translated = call(COALESCE, column("t", "translated_name"), value(""));
        Expression match = exact
                ? or(eq(call(LOWER_ASCII, name), text.toLowerCase(Locale.ROOT)), eq(call(LOWER_ASCII, translated), text.toLowerCase(Locale.ROOT)))
                : or(like(name, text), like(translated, text));
        return new Exists(new Query(new Query.Table("artwork_tags", "searched_tag"),
                List.of(join(Query.JoinKind.INNER, "tags", "t", call(EQUAL, column("t", "tag_id"), column("searched_tag", "tag_id")))),
                Map.of("present", value(1)), and(call(EQUAL, column("searched_tag", "artwork_id"), a("artwork_id")), match),
                List.of(), null, List.of(), false, 1, 0));
    }
    private static List<Query.Order> ordering(WorkQuery q) {
        boolean desc = !"asc".equals(q.order());
        var result = new ArrayList<Query.Order>();
        switch (q.sort()) {
            case "uploadTime" -> {
                return List.of(new Query.Order(a("upload_time"), desc, false), order(a("artwork_id"), desc));
            }
            case "artworkId" -> result.add(order(a("artwork_id"), desc));
            case "imgs" -> result.add(order(a("count"), desc));
            case "status" -> result.add(order(a("moved"), desc));
            case "authorId" -> result.add(order(call(COALESCE, a("author_id"), value(Long.MAX_VALUE)), desc));
            case "tags" -> result.add(order(new Scalar(new Query("artwork_tags", Map.of("cnt", call(COUNT)),
                    call(EQUAL, column("artwork_tags", "artwork_id"), a("artwork_id")), List.of(), List.of(), 1, 0)), desc));
            case "series" -> {
                result.add(order(call(COALESCE, a("series_id"), value(0)), desc));
                result.add(order(call(COALESCE, a("series_order"), value(0)), false));
            }
            default -> { return List.of(order(a("time"), desc)); }
        }
        if (!"artworkId".equals(q.sort())) result.add(order(a("time"), true));
        return result;
    }
    public List<Long> findByAuthor(long authorId, long excludeArtworkId, int limit) {
        if (limit == 0) return List.of();
        return ids(query(Map.of("artwork_id", a("artwork_id")), and(active(), eq(a("author_id"), authorId),
                not(eq(a("artwork_id"), excludeArtworkId))), List.of(), List.of(), null,
                List.of(order(a("time"), true)), false, limit, 0));
    }
    public List<Long> findBySeries(long seriesId, long excludeArtworkId, int limit) {
        if (limit == 0) return List.of();
        return ids(query(Map.of("artwork_id", a("artwork_id")), and(active(), eq(a("series_id"), seriesId),
                call(GREATER, a("series_id"), value(0)), not(eq(a("artwork_id"), excludeArtworkId))), List.of(), List.of(), null,
                List.of(order(call(COALESCE, a("series_order"), value(0)), false), order(a("artwork_id"), false)), false, limit, 0));
    }
    public SeriesNeighbors findSeriesNeighbors(long artworkId) {
        var rows = rows(query(Map.of("series_id", a("series_id"), "series_order", a("series_order"),
                "series_title", column("ms", "title")), and(active(), eq(a("artwork_id"), artworkId)),
                List.of(join(Query.JoinKind.LEFT, "manga_series", "ms", call(EQUAL, column("ms", "series_id"), a("series_id")))),
                List.of(), null, List.of(), false, 1, 0));
        if (rows.isEmpty()) return null;
        var row = rows.get(0); Long id = (Long) row.get("series_id"), ordinal = (Long) row.get("series_order");
        if (id == null || id <= 0 || ordinal == null) return null;
        return new SeriesNeighbors(id, (String) row.get("series_title"), ordinal,
                findNeighbor(id, ordinal, true), findNeighbor(id, ordinal, false));
    }
    private Neighbor findNeighbor(long seriesId, long current, boolean previous) {
        var rows = rows(query(Map.of("artwork_id", a("artwork_id"), "title", a("title"), "series_order", a("series_order")),
                and(active(), eq(a("series_id"), seriesId), call(previous ? LESS : GREATER, a("series_order"), value(current))),
                List.of(), List.of(), null, List.of(order(a("series_order"), previous)), false, 1, 0));
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        return new Neighbor((Long) row.get("artwork_id"), (String) row.get("title"), (Long) row.get("series_order"));
    }
    public List<Long> findRelatedByTags(long artworkId, int limit) {
        if (limit == 0) return List.of();
        Expression related = exists("artwork_tags", "original_tag", and(eq(column("original_tag", "artwork_id"), artworkId),
                call(EQUAL, column("original_tag", "tag_id"), column("related_tag", "tag_id"))));
        return ids(query(Map.of("artwork_id", a("artwork_id")), and(active(), not(eq(a("artwork_id"), artworkId)), related),
                List.of(join(Query.JoinKind.INNER, "artwork_tags", "related_tag",
                        call(EQUAL, column("related_tag", "artwork_id"), a("artwork_id")))),
                List.of(a("artwork_id"), a("time")), null,
                List.of(order(call(COUNT), true), order(a("time"), true)), false, limit, 0));
    }
    public Set<Long> findVisibleTagIds(WorkRestriction r) {
        if (r == null) return Set.of();
        return new LinkedHashSet<>(rows(query(Map.of("id", column("visible_ids", "tag_id")), and(active(), visibility(r)),
                List.of(join(Query.JoinKind.INNER, "artwork_tags", "visible_ids",
                        call(EQUAL, column("visible_ids", "artwork_id"), a("artwork_id")))), List.of(), null,
                List.of(), true, Integer.MAX_VALUE, 0)).stream().map(row -> (Long) row.get("id")).toList());
    }
    private Expression dimension(String column) {
        Expression c = a(column);
        return and(active(), not(nil(c)), column.equals("series_id") ? call(GREATER, c, value(0)) : null);
    }
    private Set<Long> visibleIds(String column, WorkRestriction r) {
        if (r == null) return Set.of();
        return new LinkedHashSet<>(rows(query(Map.of("id", a(column)), and(dimension(column), visibility(r)),
                List.of(), List.of(), null, List.of(), true, Integer.MAX_VALUE, 0)).stream().map(row -> (Long) row.get("id")).toList());
    }
    public Set<Long> findVisibleAuthorIds(WorkRestriction r) { return visibleIds("author_id", r); }
    public Set<Long> findVisibleSeriesIds(WorkRestriction r) { return visibleIds("series_id", r); }
    private List<Map<String, Object>> dimensionCounts(String column, WorkRestriction r) {
        return rows(query(Map.of("id", a(column), "cnt", call(COUNT)), and(dimension(column), visibility(r)),
                List.of(), List.of(a(column)), null, List.of(), false, Integer.MAX_VALUE, 0));
    }
    public List<AuthorCount> findAuthorCounts(WorkRestriction r) {
        return dimensionCounts("author_id", r).stream().map(row -> new AuthorCount((Long) row.get("id"), (Long) row.get("cnt"))).toList();
    }
    public List<SeriesCount> findSeriesCounts(WorkRestriction r) {
        return dimensionCounts("series_id", r).stream().map(row -> new SeriesCount((Long) row.get("id"), (Long) row.get("cnt"))).toList();
    }
    public long countArtworksInSeries(long seriesId, WorkRestriction r) {
        return count(and(dimension("series_id"), eq(a("series_id"), seriesId), visibility(r)));
    }
    public GuestStatistics findGuestStatistics(WorkRestriction r) {
        if (r == null) return new GuestStatistics(0, 0, 0);
        Expression images = new Conditional(call(GREATER, a("count"), value(0)), a("count"), value(0));
        Expression moved = new Conditional(eq(a("moved"), 1), value(1), value(0));
        var row = rows(query(Map.of("artworks", call(COUNT), "images", call(COALESCE, call(SUM, images), value(0)),
                "moved", call(COALESCE, call(SUM, moved), value(0))), and(active(), visibility(r)),
                List.of(), List.of(), null, List.of(), false, 1, 0)).get(0);
        return new GuestStatistics(Math.toIntExact((Long) row.get("artworks")),
                Math.toIntExact((Long) row.get("images")), Math.toIntExact((Long) row.get("moved")));
    }
    public Map<Long, Long> countVisibleWorksByCollection(WorkRestriction r) {
        var result = new LinkedHashMap<Long, Long>();
        rows(query(Map.of("id", column("c", "collection_id"), "cnt", call(COUNT)), and(active(), visibility(r)),
                List.of(join(Query.JoinKind.INNER, "artwork_collections", "c", call(EQUAL, column("c", "artwork_id"), a("artwork_id")))),
                List.of(column("c", "collection_id")), null, List.of(), false, Integer.MAX_VALUE, 0))
                .forEach(row -> result.put((Long) row.get("id"), (Long) row.get("cnt")));
        return result;
    }
    private List<TagOption> tagOptions(Expression where, List<Query.Order> priority, int limit) {
        Expression tagId = column("t", "tag_id"), name = column("t", "name"), translated = column("t", "translated_name");
        Expression count = call(COUNT_DISTINCT, column("tag_usage", "artwork_id"));
        var order = new ArrayList<>(priority); order.add(order(count, true)); order.add(order(tagId, false));
        return rows(new Query(new Query.Table("tags", "t"),
                List.of(join(Query.JoinKind.LEFT, "artwork_tags", "tag_usage", call(EQUAL, tagId, column("tag_usage", "tag_id")))),
                Map.of("tag_id", tagId, "name", name, "translated_name", translated, "cnt", count), where,
                List.of(tagId, name, translated), call(GREATER, count, value(0)), order, false, limit, 0)).stream()
                .map(row -> new TagOption((Long) row.get("tag_id"), (String) row.get("name"),
                        (String) row.get("translated_name"), Math.toIntExact((Long) row.get("cnt")))).toList();
    }
    public List<TagOption> findTagsWithCounts(String search, int limit) {
        Expression where = search == null || search.isBlank() ? null : or(
                like(call(LOWER_ASCII, column("t", "name")), search.trim().toLowerCase(Locale.ROOT)),
                like(call(LOWER_ASCII, call(COALESCE, column("t", "translated_name"), value(""))), search.trim().toLowerCase(Locale.ROOT)));
        return tagOptions(where, List.of(), limit > 0 ? limit : Integer.MAX_VALUE);
    }
    public TagOption findTagByExactName(String name, String translatedName) {
        var predicates = new ArrayList<Expression>(); var priority = new ArrayList<Query.Order>();
        for (var entry : List.of(new AbstractMap.SimpleEntry<>("name", name), new AbstractMap.SimpleEntry<>("translated_name", translatedName))) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            Expression match = eq(call(LOWER_ASCII, call(COALESCE, column("t", entry.getKey()), value(""))),
                    entry.getValue().trim().toLowerCase(Locale.ROOT));
            predicates.add(match); priority.add(order(new Conditional(match, value(0), value(1)), false));
        }
        if (predicates.isEmpty()) return null;
        var items = tagOptions(combine(OR, predicates), priority, 1);
        return items.isEmpty() ? null : items.get(0);
    }
    public record QueryResult(List<Long> ids, long totalElements) {}
    public record Neighbor(long artworkId, String title, long seriesOrder) {}
    public record SeriesNeighbors(long seriesId, String seriesTitle, long currentOrder, Neighbor prev, Neighbor next) {}
    public record AuthorCount(long authorId, long artworkCount) {}
    public record SeriesCount(long seriesId, long artworkCount) {}
    public record GuestStatistics(int artworks, int images, int moved) {}
    public record TagOption(long tagId, String name, String translatedName, int artworkCount) {}
}
