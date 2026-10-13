package top.sywyar.pixivdownload.plugin.api.storage.relational;

import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.util.List;

/** 每个 owner 拥有独立逻辑私库，由提供者映射物理位置。 */
public final class PrivateStorageSchema {
    private PrivateStorageSchema() {}
    /**
     * 合并同一 owner 的私库声明，拒绝共享授权和宿主路径列。
     * @param contributions 同一 owner 的结构声明
     * @return 包含全部声明表及必要转换历史的中性结构
     */
    public static MigrationSchema merge(List<SchemaContribution> contributions) {
        var schema = new MigrationSchema(MigrationSnapshot.FORMAT_VERSION,
                contributions.stream().flatMap(c -> c.tables().stream()).toList(), List.of(), List.of());
        for (var contribution : contributions) {
            if (contribution.sharedAccess() != null) throw new IllegalArgumentException("PRIVATE_SHARED_ACCESS_UNSUPPORTED");
            if (!contribution.pathColumns().isEmpty()) throw new IllegalArgumentException("PRIVATE_PATH_COLUMNS_UNSUPPORTED");
            for (var change : contribution.columnMigrations()) {
                if (!schema.table(change.table()).columns().contains(change.column()))
                    throw new IllegalArgumentException("PRIVATE_COLUMN_MIGRATION_INVALID");
            }
        }
        return schema;
    }

    /**
     * 合并私库声明并加入转换历史表，拒绝重复转换身份。
     * @param contributions 同一 owner 的结构声明
     * @param migrations 按发布顺序排列的稳定业务转换
     * @return 包含全部声明表及必要转换历史的中性结构
     */
    public static MigrationSchema merge(List<SchemaContribution> contributions, List<StorageMigration> migrations) {
        var base = merge(contributions);
        if (migrations.isEmpty()) return base;
        if (migrations.stream().map(StorageMigration::id).distinct().count() != migrations.size())
            throw new IllegalArgumentException("STORAGE_MIGRATION_DUPLICATE");
        var tables = new java.util.ArrayList<>(base.tables());
        tables.add(StorageMigration.historyTable());
        return new MigrationSchema(base.formatVersion(), tables, base.seeds(), base.references());
    }
}
