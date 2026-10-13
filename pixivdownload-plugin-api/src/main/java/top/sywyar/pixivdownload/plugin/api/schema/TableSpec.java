package top.sywyar.pixivdownload.plugin.api.schema;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;

/**
 * 插件自有表的纯数据声明。宿主负责 owner 与冲突检查，数据库提供者负责物理结构；
 * 本契约不引用宿主 schema 实现模型。
 *
 * @param name 名称
 * @param columns 列集合
 * @param indexes 索引列表
 * @param checkExpression 已发布的旧 SQL 入口，由提供者校验支持范围，不保证跨引擎兼容；新声明使用 check
 * @param check 当前表的中性行约束；FALSE 拒绝写入，TRUE 或 NULL 接受
 */
public record TableSpec(
        String name,
        List<ColumnSpec> columns,
        List<IndexSpec> indexes,
        String checkExpression,
        Expression check
) {
    /**
     * 创建 {@code TableSpec} 实例。
     *
     * @param name 名称
     * @param columns 列集合
     * @param indexes 索引列表
     * @param checkExpression 检查表达式
     * @param check 中性行约束，不允许子查询、聚合、传入行或其它表引用
     */
    public TableSpec {
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
        if (check != null && (checkExpression != null && !checkExpression.isBlank()
                || !"BOOLEAN".equals(checkType(name, columns, check)))) throw invalidCheck();
    }

    /**
     * 保留旧 SQL 声明的二进制调用入口；支持范围由提供者决定。
     * @param name 表名
     * @param columns 列集合
     * @param indexes 索引集合
     * @param checkExpression 提供者校验的旧表达式
     */
    public TableSpec(String name, List<ColumnSpec> columns, List<IndexSpec> indexes, String checkExpression) {
        this(name, columns, indexes, checkExpression, null);
    }

    /**
     * 返回携带中性约束的新声明；不会同时保留旧 SQL。
     * @param expression 当前表的中性行约束
     * @return 新声明
     */
    public TableSpec withCheck(Expression expression) {
        return new TableSpec(name, columns, indexes, null, java.util.Objects.requireNonNull(expression));
    }

    private static String checkType(String table, List<ColumnSpec> columns, Expression expression) {
        if (expression instanceof Expression.Column column) {
            if (!column.table().equals(table)) throw invalidCheck();
            return columns.stream().filter(c -> c.name().equals(column.name())).findFirst()
                    .orElseThrow(TableSpec::invalidCheck).type();
        }
        if (expression instanceof Expression.Value value) {
            Object v = value.value();
            if (v == null) return "NULL";
            if (v instanceof String s && s.indexOf('\0') < 0) {
                top.sywyar.pixivdownload.plugin.api.database.migration.MigrationValues.canonical(s, "TEXT");
                return "TEXT";
            }
            if (v instanceof Long) return "INTEGER";
            if (v instanceof Double) return "REAL";
            throw invalidCheck();
        }
        if (!(expression instanceof Expression.Call call)) throw invalidCheck();
        var types = call.arguments().stream().map(e -> checkType(table, columns, e)).toList();
        return switch (call.operator()) {
            case AND, OR, NOT -> {
                if (types.stream().anyMatch(t -> !t.equals("BOOLEAN"))) throw invalidCheck();
                yield "BOOLEAN";
            }
            case IS_NULL -> "BOOLEAN";
            case LENGTH, TRIM, LOWER_HEX -> {
                if (!List.of("TEXT", "NULL").contains(types.get(0))) throw invalidCheck();
                yield call.operator() == Expression.Operator.LENGTH ? "INTEGER"
                        : call.operator() == Expression.Operator.TRIM ? "TEXT" : "BOOLEAN";
            }
            case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, IN -> {
                var concrete = types.stream().filter(t -> !t.equals("NULL"))
                        .map(t -> t.equals("REAL") ? "INTEGER" : t).distinct().toList();
                if (concrete.size() > 1 || concrete.contains("BLOB") || concrete.contains("BOOLEAN")) throw invalidCheck();
                yield "BOOLEAN";
            }
            default -> throw invalidCheck();
        };
    }

    private static IllegalArgumentException invalidCheck() {
        return new IllegalArgumentException("STORAGE_CHECK_INVALID");
    }

    /**
     * 创建 {@code TableSpec} 实例。
     *
     * @param name 名称
     * @param columns 列集合
     * @param indexes 索引列表
     */
    public TableSpec(String name, List<ColumnSpec> columns, List<IndexSpec> indexes) {
        this(name, columns, indexes, null);
    }
}
