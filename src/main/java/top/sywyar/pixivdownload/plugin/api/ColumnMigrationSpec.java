package top.sywyar.pixivdownload.plugin.api;

/**
 * 对既有表的安全补列规则（{@code ALTER TABLE ... ADD COLUMN}）。
 *
 * @param table  目标表名
 * @param column 要补的列
 */
public record ColumnMigrationSpec(
        String table,
        ColumnSpec column
) {
}
