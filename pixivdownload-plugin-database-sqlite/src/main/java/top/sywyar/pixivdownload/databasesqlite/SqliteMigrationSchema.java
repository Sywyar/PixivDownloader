package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcSchemaCheck;

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
final class SqliteMigrationSchema {

    private static final Set<String> VIRTUAL_TABLE_SHADOW_SUFFIXES = Set.of(
            "_content", "_data", "_idx", "_docsize", "_config",
            "_segments", "_segdir", "_stat");

    private SqliteMigrationSchema() {}



    static void validateConstraints(Connection connection, top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema schema) throws SQLException {
        for (var table : schema.tables()) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_list(" + quote(table.name()) + ")")) {
                if (rows.next()) throw new IllegalStateException("SCHEMA_MISMATCH");
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_xinfo(" + quote(table.name()) + ")")) {
                while (rows.next()) if (rows.getInt("hidden") != 0) throw new IllegalStateException("SCHEMA_MISMATCH");
            }
            try (var statement = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE type='table' AND lower(name)=?")) {
                statement.setString(1, table.name());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) continue;
                    String ddl = withoutComments(rows.getString(1));
                    var checks = new ArrayList<String>();
                    var tokens = java.util.regex.Pattern.compile("(?is)'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|\\bCHECK\\s*\\(|\\bCOLLATE\\s+([^\\s,)]+)|\\bON\\s+CONFLICT\\s+(\\w+)").matcher(ddl);
                    while (tokens.find()) {
                        if (tokens.group().startsWith("'") || tokens.group().startsWith("\"")) continue;
                        if (tokens.group(2) != null) {
                            if (!tokens.group(2).equalsIgnoreCase("ABORT")) throw new IllegalStateException("SCHEMA_MISMATCH");
                            continue;
                        }
                        if (tokens.group(1) != null) {
                            if (!tokens.group(1).replace("\"", "").equalsIgnoreCase("BINARY")) throw new IllegalStateException("SCHEMA_MISMATCH");
                            continue;
                        }
                        int start = tokens.end(), end = start, depth = 1;
                        boolean literal = false;
                        for (; end < ddl.length(); end++) {
                            char c = ddl.charAt(end);
                            if (c == '\'') {
                                if (literal && end + 1 < ddl.length() && ddl.charAt(end + 1) == '\'') { end++; continue; }
                                literal = !literal;
                            }
                            if (!literal && c == '(') depth++;
                            if (!literal && c == ')' && --depth == 0) break;
                        }
                        if (depth != 0) throw new IllegalStateException("SCHEMA_MISMATCH");
                        checks.add(normalizeCheck(ddl.substring(start, end)));
                        tokens.region(end + 1, ddl.length());
                    }
                    String check = renderCheck(table);
                    var expected = check.isEmpty() ? List.<String>of() : List.of(normalizeCheck(check));
                    if (!checks.equals(expected)) throw new IllegalStateException("SCHEMA_MISMATCH");
                }
            }
        }
    }
    private static String withoutComments(String ddl) {
        var tokens = java.util.regex.Pattern.compile(
                "(?s)'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|`(?:[^`]|``)*`|\\[[^]]*\\]|/\\*.*?\\*/|--[^\\r\\n]*").matcher(ddl);
        return tokens.replaceAll(match -> match.group().startsWith("/*") || match.group().startsWith("--")
                ? " " : java.util.regex.Matcher.quoteReplacement(match.group()));
    }

    private static String normalizeCheck(String value) {
        var tokens = java.util.regex.Pattern.compile(
                "'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+(?:\\.[0-9]+)?|<>|<=|>=|\\S").matcher(value);
        var keywords = Set.of("and", "or", "not", "in", "is", "null", "length", "trim", "glob");
        var result = new java.util.StringJoiner(" ");
        while (tokens.find()) {
            String token = tokens.group();
            if (token.startsWith("'")) result.add(token);
            else if (token.startsWith("\"")) result.add("identifier:" + token.substring(1, token.length() - 1).toLowerCase(java.util.Locale.ROOT));
            else {
                token = token.toLowerCase(java.util.Locale.ROOT);
                result.add((Character.isLetter(token.charAt(0)) || token.charAt(0) == '_') && !keywords.contains(token)
                        ? "identifier:" + token : token);
            }
        }
        return result.toString();
    }

    static boolean canAddColumn(PhysicalDatabaseSchema.ColumnSpec column) {
        return column.primaryKeyPosition() <= 0 && (!column.notNull() || column.defaultValue() != null);
    }


    static String renderAddColumn(String tableName, PhysicalDatabaseSchema.ColumnSpec column) {
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

    static String renderCreateIndex(String tableName, PhysicalDatabaseSchema.IndexSpec index) {
        StringBuilder ddl = new StringBuilder("CREATE ");
        if (index.unique()) {
            ddl.append("UNIQUE ");
        }
        ddl.append("INDEX IF NOT EXISTS ")
                .append(quote(index.name()))
                .append(" ON ")
                .append(quote(tableName))
                .append(" (")
                .append(String.join(", ", index.columns().stream().map(SqliteMigrationSchema::quote).toList()))
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
                                .map(SqliteMigrationSchema::quote).toList())
                        + ')');
            }
        }
        if (!renderCheck(table).isEmpty()) {
            parts.add("CHECK (" + renderCheck(table) + ')');
        }
        return "CREATE TABLE IF NOT EXISTS " + quote(table.name())
                + " (" + String.join(", ", parts) + ')';
    }

    static String renderCheck(TableSpec table) {
        return table.check() == null ? JdbcSchemaCheck.legacy(table, Set.of("glob"))
                : JdbcSchemaCheck.render(table, text -> text + " NOT GLOB '*[^0-9a-f]*'");
    }

    private static String quote(String identifier) {
        return '"' + identifier.trim().replace("\"", "\"\"") + '"';
    }

    static Map<String, PhysicalDatabaseSchema.TableSpec> readActualTables(Connection connection) throws SQLException {
        // 第一遍：收集虚拟表名（如 FTS5 的 novels_fts），它们的内部存储不纳入受管 schema。
        List<String> virtualTableNames = new ArrayList<>();
        LinkedHashMap<String, Boolean> candidateTables = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                String tableName = PhysicalDatabaseSchema.normalizeIdentifier(rs.getString("name"));
                String sql = rs.getString("sql");
                boolean virtual = sql != null
                        && sql.replaceAll("\\s+", " ").trim().toUpperCase().startsWith("CREATE VIRTUAL TABLE");
                if (virtual) {
                    virtualTableNames.add(tableName);
                }
                candidateTables.put(tableName, virtual);
            }
        }

        LinkedHashMap<String, PhysicalDatabaseSchema.TableSpec> tables = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : candidateTables.entrySet()) {
            String tableName = entry.getKey();
            // 跳过虚拟表本身，以及它的影子表（FTS5 的 *_data / *_idx / *_docsize / *_config 等）。
            if (entry.getValue() || isShadowTable(tableName, virtualTableNames)) {
                continue;
            }
            tables.put(tableName, new PhysicalDatabaseSchema.TableSpec(
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

    private static List<PhysicalDatabaseSchema.ColumnSpec> readColumns(Connection connection, String tableName) throws SQLException {
        List<PhysicalDatabaseSchema.ColumnSpec> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                columns.add(new PhysicalDatabaseSchema.ColumnSpec(
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

    private static List<PhysicalDatabaseSchema.IndexSpec> readIndexes(Connection connection, String tableName) throws SQLException {
        List<PhysicalDatabaseSchema.IndexSpec> indexes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA index_list(" + tableName + ")")) {
            while (rs.next()) {
                String originCode = rs.getString("origin");
                if (rs.getInt("partial") != 0) throw new IllegalStateException("SCHEMA_MISMATCH");
                String actualName = rs.getString("name");
                List<String> columns = readIndexColumns(connection, actualName);
                if ("pk".equalsIgnoreCase(originCode)) {
                    continue;
                }

                PhysicalDatabaseSchema.IndexOrigin origin = switch (originCode) {
                    case "c" -> PhysicalDatabaseSchema.IndexOrigin.CREATE_INDEX;
                    case "u" -> PhysicalDatabaseSchema.IndexOrigin.UNIQUE_CONSTRAINT;
                    default -> null;
                };
                if (origin == null) {
                    continue;
                }

                indexes.add(new PhysicalDatabaseSchema.IndexSpec(
                        origin == PhysicalDatabaseSchema.IndexOrigin.CREATE_INDEX ? actualName : null,
                        origin,
                        rs.getInt("unique") != 0,
                        columns
                ));
            }
        }
        return indexes;
    }

    private static List<String> readIndexColumns(Connection connection, String indexName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA index_xinfo(" + quote(indexName) + ")")) {
            while (rs.next()) {
                if (rs.getInt("key") == 0) continue;
                if (rs.getInt("cid") < 0 || rs.getInt("desc") != 0 || !"BINARY".equalsIgnoreCase(rs.getString("coll")))
                    throw new IllegalStateException("SCHEMA_MISMATCH");
                columns.add(PhysicalDatabaseSchema.normalizeIdentifier(rs.getString("name")));
            }
        }
        return columns;
    }

}
