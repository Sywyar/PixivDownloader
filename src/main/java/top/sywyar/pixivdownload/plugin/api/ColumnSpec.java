package top.sywyar.pixivdownload.plugin.api;

/**
 * 列声明。字段形态与 {@code ManagedDatabaseSchema.ColumnSpec} 对齐。
 *
 * @param primaryKeyPosition 主键序号，0 表示非主键列
 */
public record ColumnSpec(
        String name,
        String type,
        boolean notNull,
        String defaultValue,
        int primaryKeyPosition
) {
}
