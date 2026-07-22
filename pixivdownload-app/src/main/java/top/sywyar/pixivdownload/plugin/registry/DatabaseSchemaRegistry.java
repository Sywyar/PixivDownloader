package top.sywyar.pixivdownload.plugin.registry;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixColumns;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnMigrationSpec;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexSpec;
import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * schema 注册中心。收集核心与各插件的 {@link SchemaContribution}，
 * 合并为受管 schema（{@link ManagedDatabaseSchema.DatabaseSchema}）与
 * 路径前缀列清单（{@link PathPrefixColumns}）。
 * 标识符归一化在合并路径由 {@code core.db} 的规格 record 构造器统一完成，
 * contribution 侧（plugin.api 类型）保持原始形态。
 * <p>
 * 按宿主签发的 ownerPluginId 可逆注册（{@link #register} / {@link #unregister}），
 * 读路径走不可变快照：注册变更时整体替换快照引用，读侧无锁。运行期 owner schema
 * 演进经 {@link #reserveOwnerEvolution} 独占写屏障；屏障覆盖数据库事务提交和快照发布，
 * 因而数据库一旦提交，快照发布不会再因并发 stale preparation 失败。
 * <p>
 * 冲突检测：表名、列名（表内重复）、显式索引名（SQLite 索引名是库级命名空间，
 * 跨表也不允许重复）与路径列表级声明重复都在 {@link #register} 即抛错，
 * 使应用启动失败而不是带病运行；跨 contribution 的引用完整性
 * （补列目标表、路径列指向的表/列）在合并时校验。
 */
@Component
public class DatabaseSchemaRegistry {

    private final ReentrantLock mutationLock = new ReentrantLock();

    private volatile List<RegisteredContribution> snapshot = List.of();

    public DatabaseSchemaRegistry(PluginRegistry pluginRegistry) {
        // 受管 schema 合并经 allRegisteredPlugins()（全部安装态插件，含被禁用的内置与外置插件）：
        // 禁用插件声明的表 / 列仍需创建，
        // 已有数据保留，故 schema 不随插件启用开关变化（当前全部长期事实表归核心，亦为前向兼容守住此不变量）。
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.allRegisteredPlugins()) {
            registered.plugin().schema().forEach(contribution -> register(registered.id(), contribution));
        }
    }

    /** 以内置插件清单构建注册中心，供 Spring 上下文之外的入口（GUI 启动期检查等）使用。 */
    public static DatabaseSchemaRegistry forBuiltInPlugins() {
        return new DatabaseSchemaRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
    }

    /**
     * 以宿主捕获的稳定插件 id 注册一份 contribution。
     * 表名 / 列名 / 索引名 / 路径列声明冲突立即抛出。
     */
    public void register(String trustedOwnerPluginId, SchemaContribution contribution) {
        RegisteredContribution candidate = registeredContribution(trustedOwnerPluginId, contribution);
        rejectNestedMutationDuringReservation();
        mutationLock.lock();
        try {
            validateAppend(snapshot, candidate);
            List<RegisteredContribution> next = new ArrayList<>(snapshot);
            next.add(candidate);
            snapshot = List.copyOf(next);
        } finally {
            mutationLock.unlock();
        }
    }

    /**
     * 为某个 owner 的运行期 schema 演进取得独占写屏障。
     * <p>
     * owner ledger 只累计、从不因旧 generation 或空声明缩小：新声明若是现有 ledger 的
     * 兼容子集，继续保留现有超集；若是安全的兼容超集，推进为新超集；混合删除与新增、
     * 或任何重定义均拒绝。调用方须在 reservation 存活期间提交数据库事务，事务成功后调用
     * {@link OwnerSchemaReservation#publish()}，最后关闭 reservation 释放写屏障。
     */
    public OwnerSchemaReservation reserveOwnerEvolution(
            String trustedOwnerPluginId,
            List<SchemaContribution> contributions) {
        if (trustedOwnerPluginId == null || trustedOwnerPluginId.isBlank()) {
            throw new IllegalStateException("schema contribution without trusted ownerPluginId");
        }
        List<SchemaContribution> declaredContributions = List.copyOf(
                Objects.requireNonNull(contributions, "contributions"));
        List<RegisteredContribution> declaredOwner = new ArrayList<>();
        for (SchemaContribution contribution : declaredContributions) {
            RegisteredContribution candidate = registeredContribution(trustedOwnerPluginId, contribution);
            validateAppend(declaredOwner, candidate);
            declaredOwner.add(candidate);
        }
        List<RegisteredContribution> immutableDeclaredOwner = List.copyOf(declaredOwner);

        rejectNestedMutationDuringReservation();
        mutationLock.lock();
        try {
            List<RegisteredContribution> base = snapshot;
            List<RegisteredContribution> previousOwner = base.stream()
                    .filter(registered -> registered.ownerPluginId().equals(trustedOwnerPluginId))
                    .toList();

            boolean declaredIsSubset = isCompatibleSubset(immutableDeclaredOwner, previousOwner);
            boolean previousIsSubset = isCompatibleSubset(previousOwner, immutableDeclaredOwner);
            if (!declaredIsSubset && !previousIsSubset) {
                throw new IllegalStateException("runtime schema declaration must be a compatible subset or safe "
                        + "superset of the retained owner ledger (plugin: " + trustedOwnerPluginId + ")");
            }
            List<RegisteredContribution> retainedOwner;
            if (previousIsSubset) {
                requireSafeEvolution(trustedOwnerPluginId, previousOwner, immutableDeclaredOwner);
                retainedOwner = immutableDeclaredOwner;
            } else {
                retainedOwner = previousOwner;
            }

            List<PathPrefixColumns.TableColumns> previousPaths = pathPrefixTableColumns(previousOwner);
            List<PathPrefixColumns.TableColumns> retainedPaths = pathPrefixTableColumns(retainedOwner);
            if (!previousPaths.equals(retainedPaths)) {
                throw new IllegalStateException("runtime schema evolution cannot change path-prefix columns without "
                        + "an owner-scoped path migration (plugin: " + trustedOwnerPluginId + ")");
            }

            int insertionIndex = firstOwnerIndex(base, trustedOwnerPluginId);
            List<RegisteredContribution> next = base.stream()
                    .filter(registered -> !registered.ownerPluginId().equals(trustedOwnerPluginId))
                    .collect(Collectors.toCollection(ArrayList::new));
            if (insertionIndex < 0) {
                insertionIndex = next.size();
            }
            for (RegisteredContribution candidate : retainedOwner) {
                validateAppend(next, candidate);
                next.add(insertionIndex++, candidate);
            }
            List<RegisteredContribution> prepared = List.copyOf(next);
            // 完整求值一次，保证全局 column migration / path 引用在进入 DDL 前已 fail-fast。
            new ManagedDatabaseSchema.DatabaseSchema(mergedTables(prepared));
            pathPrefixTableColumns(prepared);
            ManagedDatabaseSchema.DatabaseSchema ownerSchema =
                    new ManagedDatabaseSchema.DatabaseSchema(mergedTables(retainedOwner));
            return new OwnerSchemaReservation(
                    this,
                    trustedOwnerPluginId,
                    prepared,
                    retainedOwner.stream().map(RegisteredContribution::contribution).toList(),
                    ownerSchema);
        } catch (RuntimeException | Error failure) {
            mutationLock.unlock();
            throw failure;
        }
    }

    private static RegisteredContribution registeredContribution(
            String trustedOwnerPluginId,
            SchemaContribution contribution) {
        if (trustedOwnerPluginId == null || trustedOwnerPluginId.isBlank()) {
            throw new IllegalStateException("schema contribution without trusted ownerPluginId");
        }
        Objects.requireNonNull(contribution, "schema contribution");
        for (TableSpec table : contribution.tables()) {
            validateAutoIncrement(table, trustedOwnerPluginId);
        }
        List<ManagedDatabaseSchema.TableSpec> tables = contribution.tables().stream()
                .map(DatabaseSchemaRegistry::convertTable)
                .toList();
        for (ManagedDatabaseSchema.TableSpec table : tables) {
            Set<String> columnNames = new HashSet<>();
            for (ManagedDatabaseSchema.ColumnSpec column : table.columns()) {
                if (!columnNames.add(column.name())) {
                    throw new IllegalStateException("duplicate column in schema contribution: "
                            + table.name() + "." + column.name() + " (plugin: " + trustedOwnerPluginId + ")");
                }
            }
        }
        return new RegisteredContribution(trustedOwnerPluginId, contribution, tables);
    }

    private static void validateAppend(
            List<RegisteredContribution> existing,
            RegisteredContribution candidate) {
        Set<String> existingTables = existing.stream()
                .flatMap(registered -> registered.tables().stream())
                .map(ManagedDatabaseSchema.TableSpec::name)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> existingIndexNames = existing.stream()
                .flatMap(registered -> registered.tables().stream())
                .flatMap(table -> explicitIndexNames(table).stream())
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> existingPathTables = existing.stream()
                .flatMap(registered -> registered.contribution().pathColumns().stream())
                .map(spec -> ManagedDatabaseSchema.normalizeIdentifier(spec.table()))
                .collect(Collectors.toCollection(HashSet::new));
        for (ManagedDatabaseSchema.TableSpec table : candidate.tables()) {
            if (!existingTables.add(table.name())) {
                throw new IllegalStateException("duplicate table in schema contributions: "
                        + table.name() + " (plugin: " + candidate.ownerPluginId() + ")");
            }
            for (String indexName : explicitIndexNames(table)) {
                if (!existingIndexNames.add(indexName)) {
                    throw new IllegalStateException("duplicate index name in schema contributions: "
                            + indexName + " (plugin: " + candidate.ownerPluginId() + ")");
                }
            }
        }
        for (PathColumnSpec pathColumn : candidate.contribution().pathColumns()) {
            String tableName = ManagedDatabaseSchema.normalizeIdentifier(pathColumn.table());
            if (!existingPathTables.add(tableName)) {
                throw new IllegalStateException("duplicate path column declaration for table: "
                        + tableName + " (plugin: " + candidate.ownerPluginId() + ")");
            }
        }
    }

    /**
     * 注销该插件的全部 contribution，注销后状态与其从未注册过一致。
     * 插件可以不声明任何 schema，故对未注册的 pluginId 静默返回。
     */
    public void unregister(String pluginId) {
        rejectNestedMutationDuringReservation();
        mutationLock.lock();
        try {
            snapshot = snapshot.stream()
                    .filter(registered -> !registered.ownerPluginId().equals(pluginId))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), List::copyOf));
        } finally {
            mutationLock.unlock();
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
        return new PathPrefixColumns(pathPrefixTableColumns(snapshot));
    }

    private static List<PathPrefixColumns.TableColumns> pathPrefixTableColumns(
            List<RegisteredContribution> current) {
        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> tables = mergedTables(current);
        LinkedHashMap<String, String> tableOwners = tableOwners(current);
        List<PathPrefixColumns.TableColumns> merged = new ArrayList<>();
        for (RegisteredContribution registered : current) {
            for (PathColumnSpec spec : registered.contribution().pathColumns()) {
                String tableName = ManagedDatabaseSchema.normalizeIdentifier(spec.table());
                ManagedDatabaseSchema.TableSpec table = tables.get(tableName);
                if (table == null) {
                    throw new IllegalStateException("path columns target unknown table: "
                            + tableName + " (plugin: " + registered.ownerPluginId() + ")");
                }
                requireOwnedTable(tableOwners, tableName, registered.ownerPluginId(), "path columns");
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
        return List.copyOf(merged);
    }

    private static int firstOwnerIndex(List<RegisteredContribution> contributions, String ownerPluginId) {
        for (int i = 0; i < contributions.size(); i++) {
            if (contributions.get(i).ownerPluginId().equals(ownerPluginId)) {
                return i;
            }
        }
        return -1;
    }

    private void rejectNestedMutationDuringReservation() {
        if (mutationLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("schema registry mutation is not allowed from inside an active owner "
                    + "reservation");
        }
    }

    private static boolean isCompatibleSubset(
            List<RegisteredContribution> subset,
            List<RegisteredContribution> superset) {
        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> subsetTables;
        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> supersetTables;
        try {
            subsetTables = mergedTables(subset);
            supersetTables = mergedTables(superset);
        } catch (IllegalStateException invalidDeclaration) {
            return false;
        }
        for (Map.Entry<String, ManagedDatabaseSchema.TableSpec> entry : subsetTables.entrySet()) {
            ManagedDatabaseSchema.TableSpec candidateSuperset = supersetTables.get(entry.getKey());
            if (candidateSuperset == null) {
                return false;
            }
            Map<String, ManagedDatabaseSchema.ColumnSpec> supersetColumns = candidateSuperset.columns().stream()
                    .collect(Collectors.toMap(
                            ManagedDatabaseSchema.ColumnSpec::name,
                            column -> column,
                            (left, right) -> left,
                            LinkedHashMap::new));
            for (ManagedDatabaseSchema.ColumnSpec column : entry.getValue().columns()) {
                if (!column.equals(supersetColumns.get(column.name()))) {
                    return false;
                }
            }
            if (!candidateSuperset.indexes().containsAll(entry.getValue().indexes())) {
                return false;
            }
        }

        Map<String, PathPrefixColumns.TableColumns> supersetPaths;
        try {
            supersetPaths = pathPrefixTableColumns(superset).stream()
                    .collect(Collectors.toMap(
                            PathPrefixColumns.TableColumns::table,
                            columns -> columns,
                            (left, right) -> left,
                            LinkedHashMap::new));
            for (PathPrefixColumns.TableColumns subsetPath : pathPrefixTableColumns(subset)) {
                PathPrefixColumns.TableColumns candidateSuperset = supersetPaths.get(subsetPath.table());
                if (candidateSuperset == null
                        || !subsetPath.idColumn().equals(candidateSuperset.idColumn())
                        || !candidateSuperset.columns().containsAll(subsetPath.columns())) {
                    return false;
                }
            }
        } catch (IllegalStateException invalidDeclaration) {
            return false;
        }

        Map<String, TableSpec> supersetRawTables = rawTables(superset);
        for (Map.Entry<String, TableSpec> entry : rawTables(subset).entrySet()) {
            TableSpec candidateSuperset = supersetRawTables.get(entry.getKey());
            if (candidateSuperset == null
                    || !Objects.equals(entry.getValue().checkExpression(), candidateSuperset.checkExpression())) {
                return false;
            }
            Map<String, ColumnSpec> supersetRawColumns = candidateSuperset.columns().stream()
                    .collect(Collectors.toMap(
                            column -> ManagedDatabaseSchema.normalizeIdentifier(column.name()),
                            column -> column,
                            (left, right) -> left,
                            LinkedHashMap::new));
            for (ColumnSpec subsetColumn : entry.getValue().columns()) {
                ColumnSpec candidateColumn = supersetRawColumns.get(
                        ManagedDatabaseSchema.normalizeIdentifier(subsetColumn.name()));
                if (candidateColumn == null || subsetColumn.autoIncrement() != candidateColumn.autoIncrement()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void requireSafeEvolution(
            String ownerPluginId,
            List<RegisteredContribution> previous,
            List<RegisteredContribution> replacement) {
        if (previous.isEmpty()) {
            return;
        }
        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> previousTables = mergedTables(previous);
        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> replacementTables = mergedTables(replacement);
        for (Map.Entry<String, ManagedDatabaseSchema.TableSpec> entry : previousTables.entrySet()) {
            ManagedDatabaseSchema.TableSpec next = replacementTables.get(entry.getKey());
            if (next == null) {
                throw nonMonotonic(ownerPluginId, "table", entry.getKey());
            }
            Map<String, ManagedDatabaseSchema.ColumnSpec> previousColumns = entry.getValue().columns().stream()
                    .collect(Collectors.toMap(
                            ManagedDatabaseSchema.ColumnSpec::name,
                            column -> column,
                            (left, right) -> left,
                            LinkedHashMap::new));
            Map<String, ManagedDatabaseSchema.ColumnSpec> nextColumns = next.columns().stream()
                    .collect(Collectors.toMap(
                            ManagedDatabaseSchema.ColumnSpec::name,
                            column -> column,
                            (left, right) -> left,
                            LinkedHashMap::new));
            for (ManagedDatabaseSchema.ColumnSpec column : entry.getValue().columns()) {
                if (!column.equals(nextColumns.get(column.name()))) {
                    throw nonMonotonic(ownerPluginId, "column", entry.getKey() + "." + column.name());
                }
            }
            for (ManagedDatabaseSchema.ColumnSpec column : next.columns()) {
                if (!previousColumns.containsKey(column.name()) && !canAppendColumn(column)) {
                    throw new IllegalStateException("runtime schema replacement cannot safely append column: "
                            + entry.getKey() + "." + column.name() + " (plugin: " + ownerPluginId + ")");
                }
            }
            if (!next.indexes().containsAll(entry.getValue().indexes())) {
                throw nonMonotonic(ownerPluginId, "index", entry.getKey());
            }
            for (ManagedDatabaseSchema.IndexSpec index : next.indexes()) {
                if (!entry.getValue().indexes().contains(index)
                        && (index.origin() != ManagedDatabaseSchema.IndexOrigin.CREATE_INDEX || index.unique())) {
                    throw new IllegalStateException("runtime schema replacement cannot safely append constraint: "
                            + entry.getKey() + " (plugin: " + ownerPluginId + ")");
                }
            }
        }

        Map<String, PathPrefixColumns.TableColumns> replacementPaths = pathPrefixTableColumns(replacement).stream()
                .collect(Collectors.toMap(
                        PathPrefixColumns.TableColumns::table,
                        columns -> columns,
                        (left, right) -> left,
                        LinkedHashMap::new));
        for (PathPrefixColumns.TableColumns previousPath : pathPrefixTableColumns(previous)) {
            PathPrefixColumns.TableColumns nextPath = replacementPaths.get(previousPath.table());
            if (nextPath == null
                    || !previousPath.idColumn().equals(nextPath.idColumn())
                    || !nextPath.columns().containsAll(previousPath.columns())) {
                throw nonMonotonic(ownerPluginId, "path columns", previousPath.table());
            }
        }

        Map<String, TableSpec> replacementRawTables = rawTables(replacement);
        for (Map.Entry<String, TableSpec> entry : rawTables(previous).entrySet()) {
            TableSpec next = replacementRawTables.get(entry.getKey());
            if (next == null || !Objects.equals(entry.getValue().checkExpression(), next.checkExpression())) {
                throw nonMonotonic(ownerPluginId, "table check", entry.getKey());
            }
            Map<String, ColumnSpec> nextRawColumns = next.columns().stream()
                    .collect(Collectors.toMap(
                            column -> ManagedDatabaseSchema.normalizeIdentifier(column.name()),
                            column -> column,
                            (left, right) -> left,
                            LinkedHashMap::new));
            for (ColumnSpec previousColumn : entry.getValue().columns()) {
                ColumnSpec nextColumn = nextRawColumns.get(
                        ManagedDatabaseSchema.normalizeIdentifier(previousColumn.name()));
                if (nextColumn == null || previousColumn.autoIncrement() != nextColumn.autoIncrement()) {
                    throw nonMonotonic(ownerPluginId, "AUTOINCREMENT", entry.getKey() + "."
                            + ManagedDatabaseSchema.normalizeIdentifier(previousColumn.name()));
                }
            }
        }
    }

    private static boolean canAppendColumn(ManagedDatabaseSchema.ColumnSpec column) {
        return column.primaryKeyPosition() == 0 && (!column.notNull() || column.defaultValue() != null);
    }

    private static Map<String, TableSpec> rawTables(List<RegisteredContribution> contributions) {
        Map<String, TableSpec> tables = new HashMap<>();
        for (RegisteredContribution registered : contributions) {
            for (TableSpec table : registered.contribution().tables()) {
                tables.put(ManagedDatabaseSchema.normalizeIdentifier(table.name()), table);
            }
        }
        return tables;
    }

    private static IllegalStateException nonMonotonic(
            String ownerPluginId,
            String kind,
            String target) {
        return new IllegalStateException("runtime schema replacement may not remove or redefine "
                + kind + ": " + target + " (plugin: " + ownerPluginId + ")");
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
        LinkedHashMap<String, String> tableOwners = tableOwners(current);
        for (RegisteredContribution registered : current) {
            for (ColumnMigrationSpec migration : registered.contribution().columnMigrations()) {
                applyColumnMigration(tables, tableOwners, registered.ownerPluginId(), migration);
            }
        }
        return tables;
    }

    private static LinkedHashMap<String, String> tableOwners(List<RegisteredContribution> current) {
        LinkedHashMap<String, String> owners = new LinkedHashMap<>();
        for (RegisteredContribution registered : current) {
            registered.tables().forEach(table -> owners.put(table.name(), registered.ownerPluginId()));
        }
        return owners;
    }

    private static List<String> explicitIndexNames(ManagedDatabaseSchema.TableSpec table) {
        return table.indexes().stream()
                .map(ManagedDatabaseSchema.IndexSpec::name)
                .filter(name -> name != null && !name.isEmpty())
                .toList();
    }

    private static void applyColumnMigration(LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> tables,
                                             LinkedHashMap<String, String> tableOwners,
                                             String ownerPluginId,
                                             ColumnMigrationSpec migration) {
        String tableName = ManagedDatabaseSchema.normalizeIdentifier(migration.table());
        ManagedDatabaseSchema.TableSpec table = tables.get(tableName);
        if (table == null) {
            throw new IllegalStateException("column migration targets unknown table: "
                    + tableName + " (plugin: " + ownerPluginId + ")");
        }
        requireOwnedTable(tableOwners, tableName, ownerPluginId, "column migration");
        ManagedDatabaseSchema.ColumnSpec column = convertColumn(migration.column());
        if (table.columns().stream().anyMatch(existing -> existing.name().equals(column.name()))) {
            throw new IllegalStateException("column migration duplicates existing column: "
                    + tableName + "." + column.name() + " (plugin: " + ownerPluginId + ")");
        }
        List<ManagedDatabaseSchema.ColumnSpec> columns = new ArrayList<>(table.columns());
        columns.add(column);
        tables.put(tableName, new ManagedDatabaseSchema.TableSpec(table.name(), columns, table.indexes()));
    }

    private static void requireOwnedTable(LinkedHashMap<String, String> tableOwners,
                                          String tableName,
                                          String ownerPluginId,
                                          String declarationType) {
        String tableOwner = tableOwners.get(tableName);
        if (!ownerPluginId.equals(tableOwner)) {
            throw new IllegalStateException(declarationType + " targets table owned by another plugin: "
                    + tableName + " (owner: " + tableOwner + ", plugin: " + ownerPluginId + ")");
        }
    }

    /**
     * {@code AUTOINCREMENT} 只能出现在单列 INTEGER 主键上（SQLite 语法约束，
     * 也是 DDL 生成器的渲染前提），违规声明在注册期拒绝。
     */
    private static void validateAutoIncrement(TableSpec table, String ownerPluginId) {
        long primaryKeyColumns = table.columns().stream()
                .filter(column -> column.primaryKeyPosition() > 0)
                .count();
        for (ColumnSpec column : table.columns()) {
            if (!column.autoIncrement()) {
                continue;
            }
            if (column.primaryKeyPosition() != 1 || primaryKeyColumns != 1
                    || !"INTEGER".equalsIgnoreCase(column.type().trim())) {
                throw new IllegalStateException("AUTOINCREMENT requires a single-column INTEGER primary key: "
                        + table.name() + "." + column.name() + " (plugin: " + ownerPluginId + ")");
            }
        }
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

    /**
     * 一次 owner schema 演进的独占写 reservation。关闭前必须由取得它的线程完成数据库事务；
     * 事务成功后 {@link #publish()} 只做一次不可失败的快照指针发布。
     */
    public static final class OwnerSchemaReservation implements AutoCloseable {
        private final DatabaseSchemaRegistry registry;
        private final String ownerPluginId;
        private final List<RegisteredContribution> replacementSnapshot;
        private final List<SchemaContribution> ownerContributions;
        private final ManagedDatabaseSchema.DatabaseSchema ownerSchema;
        private boolean published;
        private boolean closed;

        private OwnerSchemaReservation(
                DatabaseSchemaRegistry registry,
                String ownerPluginId,
                List<RegisteredContribution> replacementSnapshot,
                List<SchemaContribution> ownerContributions,
                ManagedDatabaseSchema.DatabaseSchema ownerSchema) {
            this.registry = registry;
            this.ownerPluginId = ownerPluginId;
            this.replacementSnapshot = replacementSnapshot;
            this.ownerContributions = ownerContributions;
            this.ownerSchema = ownerSchema;
        }

        public String ownerPluginId() {
            return ownerPluginId;
        }

        public List<SchemaContribution> ownerContributions() {
            return ownerContributions;
        }

        public ManagedDatabaseSchema.DatabaseSchema ownerSchema() {
            return ownerSchema;
        }

        public Set<String> ownerTableNames() {
            return ownerSchema.tables().keySet();
        }

        /** 数据库事务已提交后发布累计 owner ledger；写屏障保证这里不存在 stale 分支。 */
        public void publish() {
            requireOpenOwnerThread();
            if (published) {
                return;
            }
            registry.snapshot = replacementSnapshot;
            published = true;
        }

        @Override
        public void close() {
            requireOpenOwnerThread();
            closed = true;
            registry.mutationLock.unlock();
        }

        private void requireOpenOwnerThread() {
            if (closed) {
                throw new IllegalStateException("owner schema reservation is already closed (plugin: "
                        + ownerPluginId + ")");
            }
            if (!registry.mutationLock.isHeldByCurrentThread()) {
                throw new IllegalStateException("owner schema reservation must be used by the reserving thread "
                        + "(plugin: " + ownerPluginId + ")");
            }
        }
    }

    private record RegisteredContribution(String ownerPluginId,
                                          SchemaContribution contribution,
                                          List<ManagedDatabaseSchema.TableSpec> tables) {}
}
