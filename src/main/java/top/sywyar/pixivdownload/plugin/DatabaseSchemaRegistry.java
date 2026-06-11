package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.plugin.api.ColumnMigrationSpec;
import top.sywyar.pixivdownload.plugin.api.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.TableSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * schema 注册中心。收集核心与各插件的 {@link SchemaContribution}，
 * 合并为受管 schema（{@link ManagedDatabaseSchema.DatabaseSchema}）。
 * 标识符归一化在合并路径由 {@code core.db} 的规格 record 构造器统一完成，
 * contribution 侧（plugin.api 类型）保持原始形态。
 * <p>
 * 按 ownerPluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁。
 */
@Component
public class DatabaseSchemaRegistry {

    private final Object lock = new Object();

    private volatile List<RegisteredContribution> snapshot = List.of();

    public DatabaseSchemaRegistry(PluginRegistry pluginRegistry) {
        for (PixivFeaturePlugin plugin : pluginRegistry.plugins()) {
            plugin.schema().forEach(this::register);
        }
    }

    /** 以内置插件清单构建注册中心，供 Spring 上下文之外的入口（GUI 启动期检查等）使用。 */
    public static DatabaseSchemaRegistry forBuiltInPlugins() {
        return new DatabaseSchemaRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    /**
     * 注册一份 contribution。表名与已注册表冲突立即抛出，使应用启动失败而不是带病运行。
     */
    public void register(SchemaContribution contribution) {
        String ownerPluginId = contribution.ownerPluginId();
        if (ownerPluginId == null || ownerPluginId.isBlank()) {
            throw new IllegalStateException("schema contribution without ownerPluginId");
        }
        if (!contribution.indexes().isEmpty()) {
            // plugin.api 的 IndexSpec 尚无目标表字段，跨表附加索引暂时无法表达；
            // 显式拒绝以免被静默丢弃造成 schema 漂移。
            throw new IllegalStateException(
                    "standalone cross-table index contributions are not supported yet (plugin: " + ownerPluginId + ")");
        }
        List<ManagedDatabaseSchema.TableSpec> tables = contribution.tables().stream()
                .map(DatabaseSchemaRegistry::convertTable)
                .toList();
        synchronized (lock) {
            Set<String> existingNames = snapshot.stream()
                    .flatMap(registered -> registered.tables().stream())
                    .map(ManagedDatabaseSchema.TableSpec::name)
                    .collect(Collectors.toCollection(HashSet::new));
            for (ManagedDatabaseSchema.TableSpec table : tables) {
                if (!existingNames.add(table.name())) {
                    throw new IllegalStateException("duplicate table in schema contributions: "
                            + table.name() + " (plugin: " + ownerPluginId + ")");
                }
            }
            List<RegisteredContribution> next = new ArrayList<>(snapshot);
            next.add(new RegisteredContribution(ownerPluginId, contribution, tables));
            snapshot = List.copyOf(next);
        }
    }

    /**
     * 注销该插件的全部 contribution，注销后状态与其从未注册过一致。
     * 插件可以不声明任何 schema，故对未注册的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        synchronized (lock) {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.ownerPluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        }
    }

    /** 按注册顺序返回全部 contribution 的不可变快照。 */
    public List<SchemaContribution> contributions() {
        return snapshot.stream().map(RegisteredContribution::contribution).toList();
    }

    /**
     * 合并全部 contribution 为受管 schema：表声明取并集，再套用各插件对既有表的安全补列规则。
     */
    public ManagedDatabaseSchema.DatabaseSchema mergedSchema() {
        List<RegisteredContribution> current = snapshot;
        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> tables = new LinkedHashMap<>();
        for (RegisteredContribution registered : current) {
            registered.tables().forEach(table -> tables.put(table.name(), table));
        }
        for (RegisteredContribution registered : current) {
            for (ColumnMigrationSpec migration : registered.contribution().columnMigrations()) {
                applyColumnMigration(tables, registered.ownerPluginId(), migration);
            }
        }
        return new ManagedDatabaseSchema.DatabaseSchema(tables);
    }

    private static void applyColumnMigration(LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> tables,
                                             String ownerPluginId,
                                             ColumnMigrationSpec migration) {
        String tableName = ManagedDatabaseSchema.normalizeIdentifier(migration.table());
        ManagedDatabaseSchema.TableSpec table = tables.get(tableName);
        if (table == null) {
            throw new IllegalStateException("column migration targets unknown table: "
                    + tableName + " (plugin: " + ownerPluginId + ")");
        }
        ManagedDatabaseSchema.ColumnSpec column = convertColumn(migration.column());
        if (table.columns().stream().anyMatch(existing -> existing.name().equals(column.name()))) {
            throw new IllegalStateException("column migration duplicates existing column: "
                    + tableName + "." + column.name() + " (plugin: " + ownerPluginId + ")");
        }
        List<ManagedDatabaseSchema.ColumnSpec> columns = new ArrayList<>(table.columns());
        columns.add(column);
        tables.put(tableName, new ManagedDatabaseSchema.TableSpec(table.name(), columns, table.indexes()));
    }

    private static ManagedDatabaseSchema.TableSpec convertTable(TableSpec table) {
        return new ManagedDatabaseSchema.TableSpec(
                table.name(),
                table.columns().stream().map(DatabaseSchemaRegistry::convertColumn).toList(),
                table.indexes().stream().map(DatabaseSchemaRegistry::convertIndex).toList());
    }

    private static ManagedDatabaseSchema.ColumnSpec convertColumn(ColumnSpec column) {
        return new ManagedDatabaseSchema.ColumnSpec(
                column.name(),
                column.type(),
                column.notNull(),
                column.defaultValue(),
                column.primaryKeyPosition());
    }

    private static ManagedDatabaseSchema.IndexSpec convertIndex(IndexSpec index) {
        ManagedDatabaseSchema.IndexOrigin origin = switch (index.origin()) {
            case CREATE_INDEX -> ManagedDatabaseSchema.IndexOrigin.CREATE_INDEX;
            case UNIQUE_CONSTRAINT -> ManagedDatabaseSchema.IndexOrigin.UNIQUE_CONSTRAINT;
        };
        return new ManagedDatabaseSchema.IndexSpec(index.name(), origin, index.unique(), index.columns());
    }

    private record RegisteredContribution(String ownerPluginId,
                                          SchemaContribution contribution,
                                          List<ManagedDatabaseSchema.TableSpec> tables) {}
}
