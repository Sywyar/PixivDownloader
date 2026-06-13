package top.sywyar.pixivdownload.plugin.api.schema;

/**
 * 列声明。字段形态与 {@code ManagedDatabaseSchema.ColumnSpec} 对齐。
 *
 * @param primaryKeyPosition 主键序号，0 表示非主键列
 * @param autoIncrement      是否为 {@code AUTOINCREMENT} 主键。仅用于 DDL 生成
 *                           （禁止 rowid 复用的行为语义）；{@code PRAGMA table_info}
 *                           不可见，schema 检查器对比不感知本字段。只允许出现在
 *                           单列 INTEGER 主键上，违规在 registry 注册时拒绝。
 */
public record ColumnSpec(
        String name,
        String type,
        boolean notNull,
        String defaultValue,
        int primaryKeyPosition,
        boolean autoIncrement
) {
    public ColumnSpec(String name,
                      String type,
                      boolean notNull,
                      String defaultValue,
                      int primaryKeyPosition) {
        this(name, type, notNull, defaultValue, primaryKeyPosition, false);
    }
}
