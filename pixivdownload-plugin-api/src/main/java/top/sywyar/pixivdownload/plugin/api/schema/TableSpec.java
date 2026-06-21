package top.sywyar.pixivdownload.plugin.api.schema;

import java.util.List;

/**
 * 表结构声明。字段形态与 {@code ManagedDatabaseSchema.TableSpec} 对齐，
 * schema registry 落地时由其接管标识符归一化等校验逻辑。
 *
 * @param checkExpression 表级 {@code CHECK (...)} 约束表达式（不含外层 CHECK 与括号），
 *                        无则为 {@code null}。仅用于 DDL 生成；{@code PRAGMA table_info}
 *                        不可见，schema 检查器对比不感知本字段。
 */
public record TableSpec(
        String name,
        List<ColumnSpec> columns,
        List<IndexSpec> indexes,
        String checkExpression
) {
    public TableSpec {
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
    }

    public TableSpec(String name, List<ColumnSpec> columns, List<IndexSpec> indexes) {
        this(name, columns, indexes, null);
    }
}
