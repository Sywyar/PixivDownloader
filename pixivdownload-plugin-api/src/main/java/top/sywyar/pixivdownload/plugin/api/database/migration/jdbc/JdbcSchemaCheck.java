package top.sywyar.pixivdownload.plugin.api.database.migration.jdbc;

import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;
import java.util.function.Function;

/** JDBC 提供者可选复用的 CHECK 渲染；引擎专用谓词由调用方实现。 */
public final class JdbcSchemaCheck {
    private JdbcSchemaCheck() {}

    /**
     * 渲染已校验的中性约束，不接收任意 SQL。
     * @param table 表声明
     * @param lowerHex 提供者的小写十六进制谓词，参数为已渲染的文本表达式
     * @return 不含外层 CHECK 的表达式；没有中性约束时为空
     */
    public static String render(TableSpec table, Function<String, String> lowerHex) {
        return table.check() == null ? "" : render(table.check(), lowerHex, 0);
    }

    private static String render(Expression expression, Function<String, String> lowerHex, int parent) {
        if (expression instanceof Expression.Column c) return JdbcMigrationSupport.quote(c.name());
        if (expression instanceof Expression.Value v) return v.value() == null ? "NULL"
                : v.value() instanceof String s ? "'" + s.replace("'", "''") + "'" : v.value().toString();
        var call = (Expression.Call) expression;
        int precedence = switch (call.operator()) { case OR -> 1; case AND -> 2; case NOT -> 3; default -> 4; };
        var args = call.arguments().stream().map(e -> render(e, lowerHex, precedence)).toList();
        String sql = switch (call.operator()) {
            case AND, OR -> String.join(" " + call.operator().name() + " ", args);
            case NOT -> "NOT (" + args.get(0) + ")";
            case IS_NULL -> args.get(0) + " IS NULL";
            case IN -> args.get(0) + " IN (" + String.join(",", args.subList(1, args.size())) + ")";
            case LENGTH, TRIM -> call.operator().name().toLowerCase(java.util.Locale.ROOT) + "(" + args.get(0) + ")";
            case LOWER_HEX -> lowerHex.apply(args.get(0));
            case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL ->
                    args.get(0) + switch (call.operator()) {
                        case EQUAL -> " = "; case NOT_EQUAL -> " <> "; case LESS -> " < ";
                        case LESS_EQUAL -> " <= "; case GREATER -> " > "; default -> " >= ";
                    } + args.get(1);
            default -> throw new IllegalArgumentException("DATABASE_SCHEMA_UNSUPPORTED");
        };
        return precedence < parent ? "(" + sql + ")" : sql;
    }

}
