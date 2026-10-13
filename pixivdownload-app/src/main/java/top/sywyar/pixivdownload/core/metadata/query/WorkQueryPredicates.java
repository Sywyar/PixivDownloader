package top.sywyar.pixivdownload.core.metadata.query;

import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Query;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 插画与小说查询共用的访客排除优先、正向 OR 规则。 */
public final class WorkQueryPredicates {
    private WorkQueryPredicates() {}
    public static Expression eq(Expression column, Object v) { return call(EQUAL, column, value(v)); }
    public static Expression nil(Expression column) { return call(IS_NULL, column); }
    public static Expression not(Expression condition) { return call(NOT, condition); }
    public static Expression truth(boolean v) { return eq(value(1), v ? 1 : 0); }
    public static Expression and(Expression... values) { return combine(AND, Arrays.asList(values)); }
    public static Expression or(Expression... values) { return combine(OR, Arrays.asList(values)); }
    public static Expression combine(Operator op, Collection<Expression> values) {
        var present = values.stream().filter(Objects::nonNull).toList();
        return present.isEmpty() ? truth(op == AND) : present.size() == 1 ? present.get(0) : new Call(op, present);
    }
    public static Expression in(Expression column, Collection<?> values) {
        if (values == null || values.isEmpty()) return truth(false);
        var args = new ArrayList<Expression>(); args.add(column);
        values.forEach(v -> args.add(value(v)));
        return new Call(IN, args);
    }
    /** 原筛选允许 % 和 _ 通配符；只转义中性合同的转义符本身。 */
    public static Expression like(Expression column, String search) {
        return call(LIKE, column, value("%" + search.replace("!", "!!") + "%"));
    }
    public static Expression exists(String table, String alias, Expression where) {
        return new Exists(new Query(new Query.Table(table, alias), List.of(), Map.of("present", value(1)),
                where, List.of(), null, List.of(), false, 1, 0));
    }
    public static Expression visible(String alias, String id, String tags, String collections, GuestRestriction r) {
        if (r == null) return truth(true);
        var all = new ArrayList<Expression>();
        var rating = column(alias, "r18");
        var ratings = new ArrayList<Expression>();
        Set<Integer> allowed = r.allowedXRestricts();
        if (allowed != null) {
            if (allowed.contains(0)) ratings.add(or(eq(rating, 0), nil(rating)));
            if (allowed.contains(1)) ratings.add(eq(rating, 1));
            if (allowed.contains(2)) ratings.add(eq(rating, 2));
        }
        all.add(combine(OR, ratings));
        var workId = column(alias, id);
        if (r.collectionRestrictsWorks() && !r.collectionUnrestricted()) {
            all.add(not(exists(collections, "visible_collection",
                    and(call(EQUAL, column("visible_collection", id), workId),
                            not(in(column("visible_collection", "collection_id"), r.collectionIds()))))));
        }
        if (!r.tagUnrestricted()) {
            all.add(not(exists(tags, "hidden_tag", and(call(EQUAL, column("hidden_tag", id), workId),
                    not(in(column("hidden_tag", "tag_id"), r.tagIds()))))));
        }
        var author = column(alias, "author_id");
        if (!r.authorUnrestricted()) all.add(or(nil(author), in(author, r.authorIds())));
        Expression tagMatch = r.tagUnrestricted() ? truth(true)
                : exists(tags, "visible_tag", and(call(EQUAL, column("visible_tag", id), workId),
                        in(column("visible_tag", "tag_id"), r.tagIds())));
        all.add(or(tagMatch, r.authorUnrestricted() ? truth(true) : in(author, r.authorIds())));
        return combine(AND, all);
    }
}
