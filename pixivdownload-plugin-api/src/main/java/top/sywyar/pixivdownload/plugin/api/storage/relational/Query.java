package top.sywyar.pixivdownload.plugin.api.storage.relational;

import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import java.util.*;

/** 同一受管存储内的投影、关联、过滤和有界分页。 */
public record Query(Source from, List<Join> joins, Map<String, Expression> select, Expression where,
                    List<Expression> groupBy, Expression having, List<Order> orderBy,
                    boolean distinct, int limit, long offset) {
    /**
     * 固定查询结构并拒绝空投影、无效分页与非法标识符。
     * @param distinct 是否对投影结果去重
     * @param from 主查询来源
     * @param groupBy 分组表达式，空集合表示不分组
     * @param having 分组过滤条件，null 表示不限制
     * @param joins 按声明顺序执行的关联
     * @param limit 最多返回的行数，必须大于零
     * @param offset 跳过的行数，不能为负数
     * @param orderBy 排序规则；分页需要稳定顺序时须由调用方显式提供
     * @param select 按投影名称组织的表达式
     * @param where 过滤条件，null 表示不限制
     */
    public Query {
        Objects.requireNonNull(from);
        joins = List.copyOf(joins);
        if (select.isEmpty() || limit <= 0 || offset < 0) throw new IllegalArgumentException("STORAGE_QUERY_INVALID");
        var copy = new LinkedHashMap<String, Expression>();
        select.forEach((name, value) -> copy.put(MigrationSchema.identifier(name), Objects.requireNonNull(value)));
        select = Collections.unmodifiableMap(copy);
        groupBy = List.copyOf(groupBy);
        orderBy = List.copyOf(orderBy);
    }
    /**
     * 创建无关联、无 HAVING、无去重的单表查询。
     * @param groupBy 分组表达式，空集合表示不分组
     * @param limit 最多返回的行数，必须大于零
     * @param offset 跳过的行数，不能为负数
     * @param orderBy 排序规则；分页需要稳定顺序时须由调用方显式提供
     * @param select 按投影名称组织的表达式
     * @param table 当前作用域中已声明的表或引用别名
     * @param where 过滤条件，null 表示不限制
     */
    public Query(String table, Map<String, Expression> select, Expression where,
                 List<Expression> groupBy, List<Order> orderBy, int limit, long offset) {
        this(new Table(table), List.of(), select, where, groupBy, null, orderBy, false, limit, offset);
    }
    /** 别名限定列引用；嵌套查询不得遮蔽外层别名。 */
    public sealed interface Source {
        /**
         * 返回当前查询作用域中的唯一别名。
         * @return 当前作用域中的引用别名
         */
        String alias();
    }
    /** 对已声明表的引用。 */
    public record Table(String name, String alias) implements Source {
        /**
         * 校验表名和别名。
         * @param alias 当前查询作用域的引用别名
         * @param name 符合标识符规则的逻辑名称
         */
        public Table { MigrationSchema.identifier(name); MigrationSchema.identifier(alias); }
        /**
         * 使用表名作为引用别名。
         * @param name 符合标识符规则的逻辑名称
         */
        public Table(String name) { this(name, name); }
    }
    /** 对子查询结果的命名引用。 */
    public record Derived(Query query, String alias) implements Source {
        /**
         * 固定子查询并校验别名。
         * @param alias 当前查询作用域的引用别名
         * @param query 受当前声明范围限制的查询
         */
        public Derived { Objects.requireNonNull(query); MigrationSchema.identifier(alias); }
    }
    /** 各分支按投影顺序合并，名称和类型必须相同。 */
    public record Union(List<Query> queries, boolean all, String alias) implements Source {
        /**
         * 固定至少两个分支并校验别名。
         * @param alias 当前查询作用域的引用别名
         * @param all 是否保留重复行
         * @param queries 投影名称、顺序和类型一致的查询分支
         */
        public Union {
            queries = List.copyOf(queries);
            if (queries.size() < 2) throw new IllegalArgumentException("STORAGE_UNION_INVALID");
            MigrationSchema.identifier(alias);
        }
    }
    /** 关联行的保留方式。 */
    public enum JoinKind {
        /** 仅保留匹配行。 */
        INNER,
        /** 保留左侧全部行，右侧未匹配列为空。 */
        LEFT
    }
    /** 关联来源及其匹配条件。 */
    public record Join(JoinKind kind, Source source, Expression on) {
        /**
         * 拒绝缺失的关联类型、来源或条件。
         * @param kind 关联类型
         * @param on 关联匹配条件
         * @param source 参与关联的查询来源
         */
        public Join { Objects.requireNonNull(kind); Objects.requireNonNull(source); Objects.requireNonNull(on); }
    }
    /** NULL 位置显式声明，不依赖引擎默认值。 */
    public record Order(Expression expression, boolean descending, boolean nullsFirst) {
        /**
         * 拒绝缺失的排序表达式。
         * @param descending 是否按降序排列
         * @param expression 待计算的表达式
         * @param nullsFirst 是否将空值排在非空值之前
         */
        public Order { Objects.requireNonNull(expression); }
    }
    /**
     * 创建返回单行 count 投影的计数查询。
     * @param table 当前作用域中已声明的表或引用别名
     * @param where 过滤条件，null 表示不限制
     * @return 具有单个 count 投影的查询
     */
    public static Query count(String table, Expression where) {
        return new Query(table, Map.of("count", Expression.call(Expression.Operator.COUNT)), where, List.of(), List.of(), 1, 0);
    }
}
