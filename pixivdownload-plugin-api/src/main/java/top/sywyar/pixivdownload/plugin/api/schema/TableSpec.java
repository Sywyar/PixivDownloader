package top.sywyar.pixivdownload.plugin.api.schema;

import java.util.List;

/**
 * 插件自有表的纯数据声明。宿主 schema registry 负责标识符归一化、冲突检查与 DDL 落地；
 * 本契约不引用宿主 schema 实现模型。
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
