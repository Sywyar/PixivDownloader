package top.sywyar.pixivdownload.plugin;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.core.db.PathPrefixColumns;
import top.sywyar.pixivdownload.plugin.api.ColumnMigrationSpec;
import top.sywyar.pixivdownload.plugin.api.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.PathColumnSpec;
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
 * 合并为受管 schema（{@link ManagedDatabaseSchema.DatabaseSchema}）与
 * 路径前缀列清单（{@link PathPrefixColumns}）。
 * 标识符归一化在合并路径由 {@code core.db} 的规格 record 构造器统一完成，
 * contribution 侧（plugin.api 类型）保持原始形态。
 * <p>
 * 按 ownerPluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁。
 * <p>
 * 冲突检测：表名、列名（表内重复）、显式索引名（SQLite 索引名是库级命名空间，
 * 跨表也不允许重复）与路径列表级声明重复都在 {@link #register} 即抛错，
 * 使应用启动失败而不是带病运行；跨 contribution 的引用完整性
 * （补列目标表、路径列指向的表/列）在合并时校验。
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
     * 注册一份 contribution。表名 / 列名 / 索引名 / 路径列声明冲突立即抛出。
     */
    public void register(SchemaContribution contribution) {
        String ownerPluginId = contribution.ownerPluginId();
        if (ownerPluginId == null || ownerPluginId.isBlank()) {
            throw new IllegalStateException("schema contribution without ownerPluginId");
        }
        if (!contribution.indexes().isEmpty()) {
            // plugin.api 的 IndexSpec 尚无目标表字段，跨表附加索引暂时无法表达；
            // 显式拒绝以免被静默丢弃造成 schema 漂移。支持形态待首个真实消费者出现再定。
            throw new IllegalStateException(
                    "standalone cross-table index contributions are not supported yet (plugin: " + ownerPluginId + ")");
        }
        List<ManagedDatabaseSchema.TableSpec> tables = contribution.tables().stream()
                .map(DatabaseSchemaRegistry::convertTable)
                .toList();
        for (ManagedDatabaseSchema.TableSpec table : tables) {
            Set<String> columnNames = new HashSet<>();
            for (ManagedDatabaseSchema.ColumnSpec column : table.columns()) {
                if (!columnNames.add(column.name())) {
                    throw new IllegalStateException("duplicate column in schema contribution: "
                            + table.name() + "." + column.name() + " (plugin: " + ownerPluginId + ")");
                }
            }
        }
        synchronized (lock) {
            Set<String> existingTables = snapshot.stream()
                    .flatMap(registered -> registered.tables().stream())
                    .map(ManagedDatabaseSchema.TableSpec::name)
                    .collect(Collectors.toCollection(HashSet::new));
            Set<String> existingIndexNames = snapshot.stream()
                    .flatMap(registered -> registered.tables().stream())
                    .flatMap(table -> explicitIndexNames(table).stream())
                    .collect(Collectors.toCollection(HashSet::new));
            Set<String> existingPathTables = snapshot.stream()
                    .flatMap(registered -> registered.contribution().pathColumns().stream())
                    .map(spec -> ManagedDatabaseSchema.normalizeIdentifier(spec.table()))
                    .collect(Collectors.toCollection(HashSet::new));
            for (ManagedDatabaseSchema.TableSpec table : tables) {
                if (!existingTables.add(table.name())) {
                    throw new IllegalStateException("duplicate table in schema contributions: "
                            + table.name() + " (plugin: " + ownerPluginId + ")");
                }
                for (String indexName : explicitIndexNames(table)) {
                    if (!existingIndexNames.add(indexName)) {
                        throw new IllegalStateException("duplicate index name in schema contributions: "
                                + indexName + " (plugin: " + ownerPluginId + ")");
                    }
                }
            }
            for (PathColumnSpec pathColumn : contribution.pathColumns()) {
                String tableName = ManagedDatabaseSchema.normalizeIdentifier(pathColumn.table());
                if (!existingPathTables.add(tableName)) {
                    throw new IllegalStateException("duplicate path column declaration for table: "
                            + tableName + " (plugin: " + ownerPluginId + ")");
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
        return new ManagedDatabaseSchema.DatabaseSchema(mergedTables(snapshot));
    }

    /**
     * 合并全部 contribution 声明的路径前缀列为统一清单。
     * 路径列指向的表与列必须存在于合并后的受管 schema，缺失即抛错——
     * 漏声明 / 错声明的路径列意味着符号根折叠与前缀改写会漏改，是悬空前缀事故源。
     */
    public PathPrefixColumns pathPrefixColumns() {
        List<RegisteredContribution> current = snapshot;
        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> tables = mergedTables(current);
        List<PathPrefixColumns.TableColumns> merged = new ArrayList<>();
        for (RegisteredContribution registered : current) {
            for (PathColumnSpec spec : registered.contribution().pathColumns()) {
                String tableName = ManagedDatabaseSchema.normalizeIdentifier(spec.table());
                ManagedDatabaseSchema.TableSpec table = tables.get(tableName);
                if (table == null) {
                    throw new IllegalStateException("path columns target unknown table: "
                            + tableName + " (plugin: " + registered.ownerPluginId() + ")");
                }
                Set<String> columnNames = table.columns().stream()
                        .map(ManagedDatabaseSchema.ColumnSpec::name)
                        .collect(Collectors.toSet());
                String idColumn = requireColumn(columnNames, tableName, spec.idColumn(),
                        registered.ownerPluginId());
                List<String> pathColumns = spec.pathColumns().stream()
                        .map(column -> requireColumn(columnNames, tableName, column,
                                registered.ownerPluginId()))
                        .toList();
                merged.add(new PathPrefixColumns.TableColumns(tableName, idColumn, pathColumns));
            }
        }
        return new PathPrefixColumns(merged);
    }

    private static String requireColumn(Set<String> columnNames, String tableName,
                                        String column, String ownerPluginId) {
        String normalized = ManagedDatabaseSchema.normalizeIdentifier(column);
        if (!columnNames.contains(normalized)) {
            throw new IllegalStateException("path columns target unknown column: "
                    + tableName + "." + normalized + " (plugin: " + ownerPluginId + ")");
        }
        return normalized;
    }

    private static LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> mergedTables(
            List<RegisteredContribution> current) {
        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> tables = new LinkedHashMap<>();
        for (RegisteredContribution registered : current) {
            registered.tables().forEach(table -> tables.put(table.name(), table));
        }
        for (RegisteredContribution registered : current) {
            for (ColumnMigrationSpec migration : registered.contribution().columnMigrations()) {
                applyColumnMigration(tables, registered.ownerPluginId(), migration);
            }
        }
        return tables;
    }

    private static List<String> explicitIndexNames(ManagedDatabaseSchema.TableSpec table) {
        return table.indexes().stream()
                .map(ManagedDatabaseSchema.IndexSpec::name)
                .filter(name -> name != null && !name.isEmpty())
                .toList();
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
