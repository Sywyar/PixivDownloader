package top.sywyar.pixivdownload.core.db.schema;

import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 读取当前提供者的物理 schema，并与期望的 {@link ManagedDatabaseSchema.DatabaseSchema}
 * （通常为 {@code plugin.DatabaseSchemaRegistry} 的合并结果）比对。
 */
public final class DatabaseSchemaInspector {

    private DatabaseSchemaInspector() {}

    public static SchemaComparison compare(DatabaseTarget target,
                                           ManagedDatabaseSchema.DatabaseSchema expectedSchema) throws SQLException {
        try (Connection connection = target.openConnection()) {
            return compare(connection, target, expectedSchema);
        }
    }

    public static SchemaComparison compare(Connection connection, DatabaseTarget target,
                                           ManagedDatabaseSchema.DatabaseSchema expectedSchema) throws SQLException {
        expectedSchema = target.physicalSchema(expectedSchema);
        Map<String, ManagedDatabaseSchema.TableSpec> actualTables = target.readSchema(connection);
        return compareTables(expectedSchema, actualTables, expectedSchema.tables().keySet(), true);
    }

    /**
     * 在调用方提供的同一连接上精确比较指定 owner 的受管表；其它 owner 或未受管表不参与本次判断。
     * 运行期插件 schema 在事务提交前使用此入口，保证 DDL 与验证观察同一事务连接。
     */
    public static SchemaComparison compareTables(
            Connection connection, DatabaseTarget target,
            ManagedDatabaseSchema.DatabaseSchema expectedSchema,
            Set<String> tableNames) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(expectedSchema, "expected schema");
        Objects.requireNonNull(tableNames, "table names");
        Set<String> normalizedTableNames = tableNames.stream()
                .map(ManagedDatabaseSchema::normalizeIdentifier)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String tableName : normalizedTableNames) {
            if (!expectedSchema.tables().containsKey(tableName)) {
                throw new IllegalArgumentException("owner table is absent from expected schema: " + tableName);
            }
        }
        return compareTables(target.physicalSchema(expectedSchema), target.readSchema(connection),
                normalizedTableNames, false);
    }

    private static SchemaComparison compareTables(
            ManagedDatabaseSchema.DatabaseSchema expectedSchema,
            Map<String, ManagedDatabaseSchema.TableSpec> actualTables,
            Set<String> tableNames,
            boolean includeUnmanagedTables) {
        List<SchemaDifference> differences = new ArrayList<>();
        Set<String> indexNames = actualTables.values().stream().flatMap(table -> table.indexes().stream())
                .map(ManagedDatabaseSchema.IndexSpec::name).filter(Objects::nonNull).collect(Collectors.toSet());

        for (String tableName : tableNames) {
            ManagedDatabaseSchema.TableSpec expectedTable = expectedSchema.tables().get(tableName);
            ManagedDatabaseSchema.TableSpec actualTable = actualTables.get(tableName);
            if (actualTable == null) {
                addDifference(differences, SchemaDifferenceKind.MISSING_TABLE, tableName, null,
                        MessageBundles.get("download.db.schema.missing-table", tableName));
                continue;
            }
            compareTable(expectedTable, actualTable, indexNames, differences);
        }

        if (includeUnmanagedTables) {
            for (String actualTable : actualTables.keySet()) {
                if (!expectedSchema.tables().containsKey(actualTable)) {
                    addDifference(differences, SchemaDifferenceKind.UNMANAGED_TABLE, actualTable, null,
                            MessageBundles.get("download.db.schema.unmanaged-table", actualTable));
                }
            }
        }

        return new SchemaComparison(differences.isEmpty(), differences);
    }

    private static void compareTable(ManagedDatabaseSchema.TableSpec expected,
                                     ManagedDatabaseSchema.TableSpec actual,
                                     Set<String> indexNames,
                                     List<SchemaDifference> differences) {
        Map<String, ManagedDatabaseSchema.ColumnSpec> expectedColumns = expected.columns().stream()
                .collect(Collectors.toMap(ManagedDatabaseSchema.ColumnSpec::name, column -> column, (left, right) -> left, LinkedHashMap::new));
        Map<String, ManagedDatabaseSchema.ColumnSpec> actualColumns = actual.columns().stream()
                .collect(Collectors.toMap(ManagedDatabaseSchema.ColumnSpec::name, column -> column, (left, right) -> left, LinkedHashMap::new));

        for (Map.Entry<String, ManagedDatabaseSchema.ColumnSpec> expectedEntry : expectedColumns.entrySet()) {
            String columnName = expectedEntry.getKey();
            ManagedDatabaseSchema.ColumnSpec actualColumn = actualColumns.get(columnName);
            if (actualColumn == null) {
                addDifference(differences, SchemaDifferenceKind.MISSING_COLUMN, expected.name(), columnName,
                        MessageBundles.get("download.db.schema.missing-column", expected.name(), columnName));
                continue;
            }
            compareColumn(expected.name(), expectedEntry.getValue(), actualColumn, differences);
        }

        for (String actualColumn : actualColumns.keySet()) {
            if (!expectedColumns.containsKey(actualColumn)) {
                var column = actualColumns.get(actualColumn);
                boolean required = column.primaryKeyPosition() > 0 || column.notNull() && column.defaultValue() == null;
                addDifference(differences, required ? SchemaDifferenceKind.UNMANAGED_REQUIRED_COLUMN
                                : SchemaDifferenceKind.UNMANAGED_COLUMN, expected.name(), actualColumn,
                        MessageBundles.get("download.db.schema.unmanaged-column", expected.name(), actualColumn));
            }
        }

        Set<String> expectedIndexes = expected.indexes().stream()
                .map(ManagedDatabaseSchema.IndexSpec::signature)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> actualIndexes = actual.indexes().stream()
                .map(ManagedDatabaseSchema.IndexSpec::signature)
                .collect(Collectors.toCollection(TreeSet::new));

        for (var expectedIndex : expected.indexes()) {
            if (!actualIndexes.contains(expectedIndex.signature())) {
                boolean creatable = expectedIndex.origin() == ManagedDatabaseSchema.IndexOrigin.CREATE_INDEX
                        && !indexNames.contains(expectedIndex.name());
                addDifference(differences, creatable ? SchemaDifferenceKind.MISSING_INDEX
                                : SchemaDifferenceKind.INDEX_DEFINITION_MISMATCH, expected.name(), null,
                        MessageBundles.get("download.db.schema.missing-index", expected.name(), expectedIndex.signature()));
            }
        }
        for (String actualIndex : actualIndexes) {
            if (!expectedIndexes.contains(actualIndex)) {
                boolean unique = actual.indexes().stream().anyMatch(index -> index.unique() && index.signature().equals(actualIndex));
                addDifference(differences, unique ? SchemaDifferenceKind.UNMANAGED_UNIQUE_INDEX
                                : SchemaDifferenceKind.UNMANAGED_INDEX, expected.name(), null,
                        MessageBundles.get("download.db.schema.unmanaged-index", expected.name(), actualIndex));
            }
        }
    }

    private static void compareColumn(String tableName,
                                      ManagedDatabaseSchema.ColumnSpec expected,
                                      ManagedDatabaseSchema.ColumnSpec actual,
                                      List<SchemaDifference> differences) {
        if (!expected.type().equals(actual.type())) {
            addDifference(differences, SchemaDifferenceKind.COLUMN_TYPE_MISMATCH, tableName, expected.name(),
                    MessageBundles.get("download.db.schema.column-type-mismatch",
                            tableName, expected.name(), expected.type(), actual.type()));
        }
        if (expected.notNull() != actual.notNull()) {
            addDifference(differences, SchemaDifferenceKind.COLUMN_NOT_NULL_MISMATCH, tableName, expected.name(),
                    MessageBundles.get("download.db.schema.column-not-null-mismatch",
                            tableName, expected.name(), expected.notNull(), actual.notNull()));
        }
        if (!java.util.Objects.equals(expected.defaultValue(), actual.defaultValue())) {
            addDifference(differences, SchemaDifferenceKind.COLUMN_DEFAULT_MISMATCH, tableName, expected.name(),
                    MessageBundles.get("download.db.schema.column-default-mismatch",
                            tableName, expected.name(), expected.defaultValue(), actual.defaultValue()));
        }
        if (expected.primaryKeyPosition() != actual.primaryKeyPosition()) {
            addDifference(differences, SchemaDifferenceKind.COLUMN_PRIMARY_KEY_MISMATCH, tableName, expected.name(),
                    MessageBundles.get("download.db.schema.column-primary-key-mismatch",
                            tableName, expected.name(), expected.primaryKeyPosition(), actual.primaryKeyPosition()));
        }
    }

    private static void addDifference(List<SchemaDifference> differences,
                                      SchemaDifferenceKind kind,
                                      String tableName,
                                      String columnName,
                                      String message) {
        differences.add(new SchemaDifference(kind, tableName, columnName, message));
    }

    public enum SchemaDifferenceKind {
        MISSING_TABLE,
        UNMANAGED_TABLE,
        MISSING_COLUMN,
        UNMANAGED_COLUMN,
        UNMANAGED_REQUIRED_COLUMN,
        MISSING_INDEX,
        INDEX_DEFINITION_MISMATCH,
        UNMANAGED_INDEX,
        UNMANAGED_UNIQUE_INDEX,
        COLUMN_TYPE_MISMATCH,
        COLUMN_NOT_NULL_MISMATCH,
        COLUMN_DEFAULT_MISMATCH,
        COLUMN_PRIMARY_KEY_MISMATCH
    }

    public record SchemaDifference(SchemaDifferenceKind kind,
                                   String tableName,
                                   String columnName,
                                   String message) {
        public boolean isHarmlessAddition() {
            return kind == SchemaDifferenceKind.UNMANAGED_TABLE
                    || kind == SchemaDifferenceKind.UNMANAGED_COLUMN
                    || kind == SchemaDifferenceKind.UNMANAGED_INDEX;
        }

        public boolean hasColumn() {
            return columnName != null && !columnName.isBlank();
        }
    }

    public record SchemaComparison(boolean matches, List<SchemaDifference> details) {
        public SchemaComparison {
            details = List.copyOf(details);
        }

        public List<String> differences() {
            return details.stream().map(SchemaDifference::message).toList();
        }

        /** 普通使用允许保留附加对象，精确迁移和 owner 发布继续检查完整差异。 */
        public SchemaComparison requiredStructure() {
            var required = details.stream().filter(difference -> !difference.isHarmlessAddition()).toList();
            return new SchemaComparison(required.isEmpty(), required);
        }

        public String summary(int limit) {
            List<String> messages = differences();
            if (messages.isEmpty()) {
                return MessageBundles.get("download.db.schema.no-difference");
            }
            int max = Math.max(limit, 1);
            List<String> visible = messages.stream().limit(max).toList();
            String summary = String.join("\n", visible);
            if (messages.size() > visible.size()) {
                summary += "\n" + MessageBundles.get("download.db.schema.more-differences",
                        messages.size() - visible.size());
            }
            return summary;
        }
    }
}
