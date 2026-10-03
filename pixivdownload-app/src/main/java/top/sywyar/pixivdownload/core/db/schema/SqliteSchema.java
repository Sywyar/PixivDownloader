package top.sywyar.pixivdownload.core.db.schema;

import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SQLite 物理结构的渲染与读取；不取得连接、不提交事务，也不发布 schema。 */
final class SqliteSchema {

    private static final Set<String> VIRTUAL_TABLE_SHADOW_SUFFIXES = Set.of(
            "_content", "_data", "_idx", "_docsize", "_config",
            "_segments", "_segdir", "_stat");

    private SqliteSchema() {}

    static Set<String> existingColumnNames(JdbcTemplate jdbcTemplate, String tableName) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT lower(name) FROM pragma_table_info(?)", String.class, tableName));
    }

    static Set<String> existingIndexNames(JdbcTemplate jdbcTemplate) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT lower(name) FROM sqlite_master WHERE type = 'index'", String.class));
    }

    static boolean canAddColumn(ManagedDatabaseSchema.ColumnSpec column) {
        return column.primaryKeyPosition() <= 0 && (!column.notNull() || column.defaultValue() != null);
    }

    static Set<String> existingTableNames(JdbcTemplate jdbcTemplate) {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table'", String.class);
        Set<String> normalized = new HashSet<>();
        for (String name : names) {
            normalized.add(ManagedDatabaseSchema.normalizeIdentifier(name));
        }
        return normalized;
    }

    static String renderAddColumn(String tableName, ManagedDatabaseSchema.ColumnSpec column) {
        StringBuilder ddl = new StringBuilder("ALTER TABLE ")
                .append(quote(tableName))
                .append(" ADD COLUMN ")
                .append(quote(column.name()))
                .append(' ')
                .append(column.type());
        if (column.notNull()) {
            ddl.append(" NOT NULL");
        }
        if (column.defaultValue() != null) {
            ddl.append(" DEFAULT ").append(column.defaultValue());
        }
        return ddl.toString();
    }

    static String renderCreateIndex(String tableName, ManagedDatabaseSchema.IndexSpec index) {
        StringBuilder ddl = new StringBuilder("CREATE ");
        if (index.unique()) {
            ddl.append("UNIQUE ");
        }
        ddl.append("INDEX IF NOT EXISTS ")
                .append(quote(index.name()))
                .append(" ON ")
                .append(quote(tableName))
                .append(" (")
                .append(String.join(", ", index.columns().stream().map(SqliteSchema::quote).toList()))
                .append(')');
        return ddl.toString();
    }

    static String renderCreateTable(TableSpec table) {
        List<ColumnSpec> primaryKeyColumns = table.columns().stream()
                .filter(column -> column.primaryKeyPosition() > 0)
                .sorted(Comparator.comparingInt(ColumnSpec::primaryKeyPosition))
                .toList();
        boolean inlinePrimaryKey = primaryKeyColumns.size() == 1;
        List<String> parts = new ArrayList<>();
        for (ColumnSpec column : table.columns()) {
            StringBuilder part = new StringBuilder(quote(column.name()))
                    .append(' ')
                    .append(column.type());
            if (column.notNull()) {
                part.append(" NOT NULL");
            }
            if (column.defaultValue() != null) {
                part.append(" DEFAULT ").append(column.defaultValue());
            }
            if (inlinePrimaryKey && column.primaryKeyPosition() == 1) {
                part.append(" PRIMARY KEY");
                if (column.autoIncrement()) {
                    part.append(" AUTOINCREMENT");
                }
            }
            parts.add(part.toString());
        }
        if (!inlinePrimaryKey && !primaryKeyColumns.isEmpty()) {
            parts.add("PRIMARY KEY ("
                    + String.join(", ", primaryKeyColumns.stream()
                            .map(column -> quote(column.name())).toList())
                    + ')');
        }
        for (var index : table.indexes()) {
            if (index.origin() == IndexOrigin.UNIQUE_CONSTRAINT) {
                parts.add("UNIQUE ("
                        + String.join(", ", index.columns().stream()
                                .map(SqliteSchema::quote).toList())
                        + ')');
            }
        }
        if (table.checkExpression() != null && !table.checkExpression().isBlank()) {
            parts.add("CHECK (" + table.checkExpression() + ')');
        }
        return "CREATE TABLE IF NOT EXISTS " + quote(table.name())
                + " (" + String.join(", ", parts) + ')';
    }

    private static String quote(String identifier) {
        return '"' + identifier.trim() + '"';
    }

    static Map<String, ManagedDatabaseSchema.TableSpec> readActualTables(Connection connection) throws SQLException {
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

}
