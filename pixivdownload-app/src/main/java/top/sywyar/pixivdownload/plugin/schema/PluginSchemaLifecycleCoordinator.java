package top.sywyar.pixivdownload.plugin.schema;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.util.List;
import java.util.Objects;

/**
 * 把运行期首次安装或物理 reload 的 schema contribution 合并进 owner 累计 ledger，先在同一事务连接上
 * 应用并精确验证磁盘结构，再一次发布 registry 快照。owner reservation 覆盖事务提交与快照发布，
 * 不存在数据库已提交后因 stale preparation 拒绝发布的窗口。
 */
@Component
public final class PluginSchemaLifecycleCoordinator implements PluginSchemaLifecycle {

    private final DatabaseSchemaRegistry schemaRegistry;
    private final DatabaseInitializer databaseInitializer;
    private final TransactionTemplate transactionTemplate;

    public PluginSchemaLifecycleCoordinator(
            DatabaseSchemaRegistry schemaRegistry,
            DatabaseInitializer databaseInitializer,
            PlatformTransactionManager transactionManager) {
        this.schemaRegistry = schemaRegistry;
        this.databaseInitializer = databaseInitializer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void activate(PluginRegistry.RegisteredPlugin registered) {
        Objects.requireNonNull(registered, "registered");
        List<SchemaContribution> declared = registered.plugin().schema();
        if (declared == null) {
            throw new IllegalStateException("schema() returned null (plugin: " + registered.id() + ")");
        }
        List<SchemaContribution> contributions = List.copyOf(declared);
        try (DatabaseSchemaRegistry.OwnerSchemaReservation reservation =
                     schemaRegistry.reserveOwnerEvolution(registered.id(), contributions)) {
            transactionTemplate.executeWithoutResult(status -> databaseInitializer.applyRuntimeOwnerSchema(
                    reservation.ownerContributions(),
                    reservation.ownerSchema(),
                    reservation.ownerTableNames()));
            reservation.publish();
        }
    }
}
