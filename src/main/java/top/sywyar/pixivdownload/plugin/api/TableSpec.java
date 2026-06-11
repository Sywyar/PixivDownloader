package top.sywyar.pixivdownload.plugin.api;

import java.util.List;

/**
 * 表结构声明。字段形态与 {@code ManagedDatabaseSchema.TableSpec} 对齐，
 * schema registry 落地时由其接管标识符归一化等校验逻辑。
 */
public record TableSpec(
        String name,
        List<ColumnSpec> columns,
        List<IndexSpec> indexes
) {
    public TableSpec {
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
    }
}
