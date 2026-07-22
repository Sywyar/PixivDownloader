package top.sywyar.pixivdownload.core.db.schema;

import org.sqlite.SQLiteConfig;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

/**
 * 读取磁盘上的 SQLite schema，并与期望的 {@link ManagedDatabaseSchema.DatabaseSchema}
 * （通常为 {@code plugin.DatabaseSchemaRegistry} 的合并结果）比对。
 */
public final class DatabaseSchemaInspector {

    private static final Set<String> VIRTUAL_TABLE_SHADOW_SUFFIXES = Set.of(
            "_content", "_data", "_idx", "_docsize", "_config",
            "_segments", "_segdir", "_stat");

    private DatabaseSchemaInspector() {}

    public static SchemaComparison compare(Path databasePath,
                                           ManagedDatabaseSchema.DatabaseSchema expectedSchema) throws SQLException {
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databasePath, sqliteConfig.toProperties())) {
            return compare(connection, expectedSchema);
        }
    }

    public static SchemaComparison compare(Connection connection,
                                           ManagedDatabaseSchema.DatabaseSchema expectedSchema) throws SQLException {
        Map<String, ManagedDatabaseSchema.TableSpec> actualTables = readActualTables(connection);
        return compareTables(expectedSchema, actualTables, expectedSchema.tables().keySet(), true);
    }

    /**
     * 在调用方提供的同一连接上精确比较指定 owner 的受管表；其它 owner 或未受管表不参与本次判断。
     * 运行期插件 schema 在事务提交前使用此入口，保证 DDL 与验证观察同一事务连接。
     */
    public static SchemaComparison compareTables(
            Connection connection,
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
        return compareTables(expectedSchema, readActualTables(connection), normalizedTableNames, false);
    }

    private static SchemaComparison compareTables(
            ManagedDatabaseSchema.DatabaseSchema expectedSchema,
            Map<String, ManagedDatabaseSchema.TableSpec> actualTables,
            Set<String> tableNames,
            boolean includeUnmanagedTables) {
        List<SchemaDifference> differences = new ArrayList<>();

        for (String tableName : tableNames) {
            ManagedDatabaseSchema.TableSpec expectedTable = expectedSchema.tables().get(tableName);
            ManagedDatabaseSchema.TableSpec actualTable = actualTables.get(tableName);
            if (actualTable == null) {
                addDifference(differences, SchemaDifferenceKind.MISSING_TABLE, tableName, null,
                        MessageBundles.get("download.db.schema.missing-table", tableName));
                continue;
            }
            compareTable(expectedTable, actualTable, differences);
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

    private static Map<String, ManagedDatabaseSchema.TableSpec> readActualTables(Connection connection) throws SQLException {
        // 第一遍：收集虚拟表名（如 FTS5 的 novels_fts），它们的内部存储不纳入受管 schema。
        List<String> virtualTableNames = new ArrayList<>();
        LinkedHashMap<String, Boolean> candidateTables = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                String tableName = ManagedDatabaseSchema.normalizeIdentifier(rs.getString("name"));
                String sql = rs.getString("sql");
                boolean virtual = sql != null
                        && sql.replaceAll("\\s+", " ").trim().toUpperCase().startsWith("CREATE VIRTUAL TABLE");
                if (virtual) {
                    virtualTableNames.add(tableName);
                }
                candidateTables.put(tableName, virtual);
            }
        }

        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> tables = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : candidateTables.entrySet()) {
            String tableName = entry.getKey();
            // 跳过虚拟表本身，以及它的影子表（FTS5 的 *_data / *_idx / *_docsize / *_config 等）。
            if (entry.getValue() || isShadowTable(tableName, virtualTableNames)) {
                continue;
            }
            tables.put(tableName, new ManagedDatabaseSchema.TableSpec(
                    tableName,
                    readColumns(connection, tableName),
                    readIndexes(connection, tableName)
            ));
        }
        return tables;
    }

    private static boolean isShadowTable(String tableName, List<String> virtualTableNames) {
        for (String virtual : virtualTableNames) {
            for (String suffix : VIRTUAL_TABLE_SHADOW_SUFFIXES) {
                if (tableName.equals(virtual + suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<ManagedDatabaseSchema.ColumnSpec> readColumns(Connection connection, String tableName) throws SQLException {
        List<ManagedDatabaseSchema.ColumnSpec> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                columns.add(new ManagedDatabaseSchema.ColumnSpec(
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getInt("notnull") != 0,
                        rs.getString("dflt_value"),
                        rs.getInt("pk")
                ));
            }
        }
        return columns;
    }

    private static List<ManagedDatabaseSchema.IndexSpec> readIndexes(Connection connection, String tableName) throws SQLException {
        List<ManagedDatabaseSchema.IndexSpec> indexes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA index_list(" + tableName + ")")) {
            while (rs.next()) {
                String originCode = rs.getString("origin");
                if ("pk".equalsIgnoreCase(originCode)) {
                    continue;
                }

                ManagedDatabaseSchema.IndexOrigin origin = switch (originCode) {
                    case "c" -> ManagedDatabaseSchema.IndexOrigin.CREATE_INDEX;
                    case "u" -> ManagedDatabaseSchema.IndexOrigin.UNIQUE_CONSTRAINT;
                    default -> null;
                };
                if (origin == null) {
                    continue;
                }

                String actualName = rs.getString("name");
                indexes.add(new ManagedDatabaseSchema.IndexSpec(
                        origin == ManagedDatabaseSchema.IndexOrigin.CREATE_INDEX ? actualName : null,
                        origin,
                        rs.getInt("unique") != 0,
                        readIndexColumns(connection, actualName)
                ));
            }
        }
        return indexes;
    }

    private static List<String> readIndexColumns(Connection connection, String indexName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA index_info(" + indexName + ")")) {
            while (rs.next()) {
                columns.add(ManagedDatabaseSchema.normalizeIdentifier(rs.getString("name")));
            }
        }
        return columns;
    }

    private static void compareTable(ManagedDatabaseSchema.TableSpec expected,
                                     ManagedDatabaseSchema.TableSpec actual,
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
                addDifference(differences, SchemaDifferenceKind.UNMANAGED_COLUMN, expected.name(), actualColumn,
                        MessageBundles.get("download.db.schema.unmanaged-column", expected.name(), actualColumn));
            }
        }

        Set<String> expectedIndexes = expected.indexes().stream()
                .map(ManagedDatabaseSchema.IndexSpec::signature)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> actualIndexes = actual.indexes().stream()
                .map(ManagedDatabaseSchema.IndexSpec::signature)
                .collect(Collectors.toCollection(TreeSet::new));

        for (String expectedIndex : expectedIndexes) {
            if (!actualIndexes.contains(expectedIndex)) {
                addDifference(differences, SchemaDifferenceKind.MISSING_INDEX, expected.name(), null,
                        MessageBundles.get("download.db.schema.missing-index", expected.name(), expectedIndex));
            }
        }
        for (String actualIndex : actualIndexes) {
            if (!expectedIndexes.contains(actualIndex)) {
                addDifference(differences, SchemaDifferenceKind.UNMANAGED_INDEX, expected.name(), null,
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
        MISSING_INDEX,
        UNMANAGED_INDEX,
        COLUMN_TYPE_MISMATCH,
        COLUMN_NOT_NULL_MISMATCH,
        COLUMN_DEFAULT_MISMATCH,
        COLUMN_PRIMARY_KEY_MISMATCH
    }

    public record SchemaDifference(SchemaDifferenceKind kind,
                                   String tableName,
                                   String columnName,
                                   String message) {
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
