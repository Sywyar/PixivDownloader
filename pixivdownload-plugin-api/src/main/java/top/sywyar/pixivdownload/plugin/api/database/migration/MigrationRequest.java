package top.sywyar.pixivdownload.plugin.api.database.migration;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** 备份路径必须全新；主库与私库使用相同合同，owner 由调用方绑定的连接目标决定。 */
public record MigrationRequest(MigrationSchema schema, Path backup, Set<String> rebuildObjects,
        java.util.List<top.sywyar.pixivdownload.plugin.api.storage.relational.StorageMigration> storageMigrations) {
    /**
     * 创建不包含业务转换的迁移请求。
     * @param backup 尚不存在的一致备份路径
     * @param rebuildObjects 显式声明可再生的辅助对象名
     * @param schema 当前存储作用域的结构声明
     */
    public MigrationRequest(MigrationSchema schema, Path backup, Set<String> rebuildObjects) {
        this(schema, backup, rebuildObjects, java.util.List.of());
    }
    /**
     * 校验请求并固定备份绝对路径。
     * @param schema 完整的中性结构
     * @param backup 全新的备份路径
     * @param rebuildObjects 显式可再生对象名
     * @param storageMigrations 源结构事务需应用或核验的业务转换
     */
    public MigrationRequest {
        Objects.requireNonNull(schema);
        backup = Objects.requireNonNull(backup).toAbsolutePath().normalize();
        rebuildObjects = Set.copyOf(rebuildObjects);
        storageMigrations = java.util.List.copyOf(storageMigrations);
    }
}
