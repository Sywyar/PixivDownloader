package top.sywyar.pixivdownload.download.db;

import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads the on-disk SQLite schema and compares it with {@link ManagedDatabaseSchema#SPEC}.
 */
public final class DatabaseSchemaInspector {

    private DatabaseSchemaInspector() {}

    public static SchemaComparison compare(Path databasePath) throws SQLException {
        return compare(databasePath, ManagedDatabaseSchema.SPEC);
    }

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
        List<String> differences = new ArrayList<>();

        for (Map.Entry<String, ManagedDatabaseSchema.TableSpec> expectedEntry : expectedSchema.tables().entrySet()) {
            String tableName = expectedEntry.getKey();
            ManagedDatabaseSchema.TableSpec actualTable = actualTables.get(tableName);
            if (actualTable == null) {
                differences.add("缺少表: " + tableName);
                continue;
            }
            compareTable(expectedEntry.getValue(), actualTable, differences);
        }

        for (String actualTable : actualTables.keySet()) {
            if (!expectedSchema.tables().containsKey(actualTable)) {
                differences.add("存在未受管表: " + actualTable);
            }
        }

        return new SchemaComparison(differences.isEmpty(), List.copyOf(differences));
    }

    private static Map<String, ManagedDatabaseSchema.TableSpec> readActualTables(Connection connection) throws SQLException {
        LinkedHashMap<String, ManagedDatabaseSchema.TableSpec> tables = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                String tableName = ManagedDatabaseSchema.normalizeIdentifier(rs.getString("name"));
                tables.put(tableName, new ManagedDatabaseSchema.TableSpec(
                        tableName,
                        readColumns(connection, tableName),
                        readIndexes(connection, tableName)
                ));
            }
        }
        return tables;
    }

    private static List<ManagedDatabaseSchema.ColumnSpec> readColumns(Connection connection, String tableName) throws SQLException {
        List<ManagedDatabaseSchema.ColumnSpec> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + quoteIdentifier(tableName) + ")")) {
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
             ResultSet rs = statement.executeQuery("PRAGMA index_list(" + quoteIdentifier(tableName) + ")")) {
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
             ResultSet rs = statement.executeQuery("PRAGMA index_info(" + quoteIdentifier(indexName) + ")")) {
            while (rs.next()) {
                columns.add(ManagedDatabaseSchema.normalizeIdentifier(rs.getString("name")));
            }
        }
        return columns;
    }

    private static void compareTable(ManagedDatabaseSchema.TableSpec expected,
                                     ManagedDatabaseSchema.TableSpec actual,
                                     List<String> differences) {
        Map<String, ManagedDatabaseSchema.ColumnSpec> expectedColumns = expected.columns().stream()
                .collect(Collectors.toMap(ManagedDatabaseSchema.ColumnSpec::name, column -> column, (left, right) -> left, LinkedHashMap::new));
        Map<String, ManagedDatabaseSchema.ColumnSpec> actualColumns = actual.columns().stream()
                .collect(Collectors.toMap(ManagedDatabaseSchema.ColumnSpec::name, column -> column, (left, right) -> left, LinkedHashMap::new));

        for (Map.Entry<String, ManagedDatabaseSchema.ColumnSpec> expectedEntry : expectedColumns.entrySet()) {
            String columnName = expectedEntry.getKey();
            ManagedDatabaseSchema.ColumnSpec actualColumn = actualColumns.get(columnName);
            if (actualColumn == null) {
                differences.add("表 " + expected.name() + " 缺少列: " + columnName);
                continue;
            }
            compareColumn(expected.name(), expectedEntry.getValue(), actualColumn, differences);
        }

        for (String actualColumn : actualColumns.keySet()) {
            if (!expectedColumns.containsKey(actualColumn)) {
                differences.add("表 " + expected.name() + " 存在未受管列: " + actualColumn);
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
                differences.add("表 " + expected.name() + " 缺少索引/唯一约束: " + expectedIndex);
            }
        }
        for (String actualIndex : actualIndexes) {
            if (!expectedIndexes.contains(actualIndex)) {
                differences.add("表 " + expected.name() + " 存在未受管索引/唯一约束: " + actualIndex);
            }
        }
    }

    private static void compareColumn(String tableName,
                                      ManagedDatabaseSchema.ColumnSpec expected,
                                      ManagedDatabaseSchema.ColumnSpec actual,
                                      List<String> differences) {
        if (!expected.type().equals(actual.type())) {
            differences.add("表 " + tableName + " 列 " + expected.name() + " 类型不一致: expected="
                    + expected.type() + ", actual=" + actual.type());
        }
        if (expected.notNull() != actual.notNull()) {
            differences.add("表 " + tableName + " 列 " + expected.name() + " NOT NULL 不一致: expected="
                    + expected.notNull() + ", actual=" + actual.notNull());
        }
        if (!java.util.Objects.equals(expected.defaultValue(), actual.defaultValue())) {
            differences.add("表 " + tableName + " 列 " + expected.name() + " 默认值不一致: expected="
                    + expected.defaultValue() + ", actual=" + actual.defaultValue());
        }
        if (expected.primaryKeyPosition() != actual.primaryKeyPosition()) {
            differences.add("表 " + tableName + " 列 " + expected.name() + " 主键顺序不一致: expected="
                    + expected.primaryKeyPosition() + ", actual=" + actual.primaryKeyPosition());
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public record SchemaComparison(boolean matches, List<String> differences) {
        public SchemaComparison {
            differences = List.copyOf(differences);
        }

        public String summary(int limit) {
            if (differences.isEmpty()) {
                return "无差异";
            }
            int max = Math.max(limit, 1);
            List<String> visible = differences.stream().limit(max).toList();
            String summary = String.join("\n", visible);
            if (differences.size() > visible.size()) {
                summary += "\n... 另有 " + (differences.size() - visible.size()) + " 项差异";
            }
            return summary;
        }
    }
}
