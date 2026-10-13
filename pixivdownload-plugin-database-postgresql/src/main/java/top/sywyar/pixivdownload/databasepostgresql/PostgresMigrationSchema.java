package top.sywyar.pixivdownload.databasepostgresql;

import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcSchemaCheck;

import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将已有宿主 schema 声明映射为 PostgreSQL 的物理结构，不管理连接或事务。 */
final class PostgresMigrationSchema {
    private PostgresMigrationSchema() {}

    static void validate(List<SchemaContribution> contributions, PhysicalDatabaseSchema.DatabaseSchema schema) {
        // 先完整验证，再执行任何 DDL，避免不受支持的声明留下部分新表。
        for (var table : schema.tables().values()) {
            quote(table.name());
            for (var column : table.columns()) {
                quote(column.name());
                try {
                    physicalType(column.type());
                    if (column.defaultValue() != null && !column.defaultValue().matches(
                            "(?is)NULL|[-+]?[0-9]+(?:\\.[0-9]+)?|'(?:[^']|'')*'")) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException failure) {
                    throw unsupported(table.name() + "." + column.name());
                }
            }
            table.indexes().forEach(index -> {
                if (index.name() != null) quote(index.name());
                index.columns().forEach(PostgresMigrationSchema::quote);
            });
        }
        for (var contribution : contributions) {
            for (var table : contribution.tables()) {
                renderCheck(table);
            }
        }
    }

    static String physicalType(String type) {
        return switch (PhysicalDatabaseSchema.normalizeType(type)) {
            case "INTEGER" -> "BIGINT";
            case "REAL" -> "DOUBLE PRECISION";
            case "TEXT" -> "TEXT";
            case "BLOB" -> "BYTEA";
            default -> throw unsupported(type);
        };
    }

    private static String columnType(String type) {
        String physical = physicalType(type);
        return "TEXT".equals(physical) ? "TEXT COLLATE \"C\"" : physical;
    }

    static PhysicalDatabaseSchema.DatabaseSchema physicalSchema(PhysicalDatabaseSchema.DatabaseSchema schema) {
        Map<String, PhysicalDatabaseSchema.TableSpec> tables = new LinkedHashMap<>();
        schema.tables().forEach((name, table) -> tables.put(name, new PhysicalDatabaseSchema.TableSpec(
                name, table.columns().stream().map(column -> new PhysicalDatabaseSchema.ColumnSpec(
                        column.name(), physicalType(column.type()),
                        column.notNull() || column.primaryKeyPosition() > 0,
                        generatedKey(table, column) ? "<identity:d>" : column.defaultValue(),
                        column.primaryKeyPosition())).toList(), table.indexes())));
        return new PhysicalDatabaseSchema.DatabaseSchema(tables);
    }

    private static boolean generatedKey(PhysicalDatabaseSchema.TableSpec table, PhysicalDatabaseSchema.ColumnSpec column) {
        return column.primaryKeyPosition() == 1 && column.type().equals("INTEGER") && column.defaultValue() == null
                && table.columns().stream().filter(candidate -> candidate.primaryKeyPosition() > 0).count() == 1;
    }

    static void validateConstraints(Connection connection, List<SchemaContribution> contributions) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT c.contype, pg_get_expr(c.conbin, c.conrelid) AS expression,
                       c.convalidated AND NOT c.connoinherit AS valid
                FROM pg_catalog.pg_constraint c
                JOIN pg_catalog.pg_class t ON t.oid = c.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = current_schema() AND t.relname = ? AND c.contype IN ('c', 'f', 'x')
                """)) {
            for (var contribution : contributions) {
                for (var table : contribution.tables()) {
                    statement.setString(1, PhysicalDatabaseSchema.normalizeIdentifier(table.name()));
                    List<String> actual = new ArrayList<>();
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            actual.add("c".equals(rows.getString(1)) && rows.getBoolean(3)
                                    ? rows.getString(2) : "<unsupported>");
                        }
                    }
                    boolean hasCheck = !renderCheck(table).isEmpty();
                    if (actual.size() != (hasCheck ? 1 : 0) || hasCheck && (actual.contains("<unsupported>")
                            || !canonicalCheck(connection, table, renderCheck(table)).equals(
                                    canonicalCheck(connection, table, validateCheck(table, actual.get(0), true))))) {
                        throw new IllegalStateException("SCHEMA_MISMATCH");
                    }
                }
            }
        }
    }

    private static String canonicalCheck(Connection connection, TableSpec table, String expression) throws SQLException {
        // 两侧都经规划器规范化，统一 IN 数组的常量折叠；只读核验不创建临时表。
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("EXPLAIN (VERBOSE, COSTS OFF) SELECT "
                     + expression + " FROM ONLY " + quote(table.name()))) {
            while (rows.next()) {
                String line = rows.getString(1).stripLeading();
                if (line.startsWith("Output: ")) return line.substring("Output: ".length());
            }
        }
        throw new SQLException("SCHEMA_CHECK_UNAVAILABLE");
    }

    static String renderCheck(TableSpec table) {
        if (table.check() != null) return JdbcSchemaCheck.render(table, text -> text + " !~ '[^0-9a-f]'");
        if (table.checkExpression() == null || table.checkExpression().isBlank()) return "";
        return validateCheck(table, table.checkExpression(), false);
    }

    private static String validateCheck(TableSpec table, String expression, boolean stored) {
        Set<String> identifiers = new HashSet<>(List.of("and", "or", "not", "in", "is", "null", "length", "trim"));
        if (stored) identifiers.addAll(List.of("any", "array", "btrim", "both", "from", "text", "bigint", "double", "precision"));
        table.columns().forEach(column -> identifiers.add(PhysicalDatabaseSchema.normalizeIdentifier(column.name())));
        var tokens = java.util.regex.Pattern.compile(
                "\\s+|'(?:[^']|'')*'|\"[a-z_][a-z0-9_]*\"|[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+(?:\\.[0-9]+)?|!~|<>|<=|>=|::|[()\\[\\]=<>+,-]").matcher(expression);
        int end = 0;
        String previous = "";
        while (tokens.find()) {
            String token = tokens.group();
            boolean identifier = Character.isLetter(token.charAt(0)) || token.charAt(0) == '_' || token.charAt(0) == '"';
            String name = token.replace("\"", "").toLowerCase(java.util.Locale.ROOT);
            boolean call = expression.substring(tokens.end()).stripLeading().startsWith("(");
            if (tokens.start() != end || identifier && (!identifiers.contains(name)
                    || call && (token.startsWith("\"") || !Set.of("and", "or", "not", "in", "any", "length", "trim", "btrim").contains(name)))
                    || previous.equals("::") && !token.isBlank() && !Set.of("text", "bigint", "double").contains(token)
                    || token.equals("-") && expression.startsWith("--", tokens.start())
                    || !stored && (token.equals("::") || token.equals("[") || token.equals("]") || token.startsWith("\""))) {
                throw unsupported(table.name() + ".CHECK");
            }
            end = tokens.end();
            if (!token.isBlank()) previous = token;
        }
        if (end != expression.length()) throw unsupported(table.name() + ".CHECK");
        return expression;
    }

    static String renderCreateTable(TableSpec table) {
        var primary = table.columns().stream().filter(column -> column.primaryKeyPosition() > 0)
                .sorted(Comparator.comparingInt(ColumnSpec::primaryKeyPosition)).toList();
        List<String> parts = new ArrayList<>();
        for (var column : table.columns()) {
            String part = quote(column.name()) + " " + columnType(column.type());
            // SQLite 的单列 INTEGER PRIMARY KEY 即使没有 AUTOINCREMENT 也能生成键。
            if (primary.size() == 1 && column.primaryKeyPosition() == 1
                    && "INTEGER".equalsIgnoreCase(column.type()) && column.defaultValue() == null) {
                part += " GENERATED BY DEFAULT AS IDENTITY";
            }
            if (column.notNull()) part += " NOT NULL";
            if (column.defaultValue() != null) part += " DEFAULT " + column.defaultValue();
            parts.add(part);
        }
        if (!primary.isEmpty()) {
            parts.add("PRIMARY KEY (" + String.join(", ",
                    primary.stream().map(column -> quote(column.name())).toList()) + ")");
        }
        for (var index : table.indexes()) {
            if (index.origin() == IndexOrigin.UNIQUE_CONSTRAINT) {
                parts.add("UNIQUE (" + String.join(", ", index.columns().stream()
                        .map(PostgresMigrationSchema::quote).toList()) + ")");
            }
        }
        if (!renderCheck(table).isEmpty()) {
            parts.add("CHECK (" + renderCheck(table) + ")");
        }
        return "CREATE TABLE IF NOT EXISTS " + quote(table.name()) + " (" + String.join(", ", parts) + ")";
    }

    static String renderAddColumn(String table, PhysicalDatabaseSchema.ColumnSpec column) {
        return "ALTER TABLE " + quote(table) + " ADD COLUMN " + quote(column.name())
                + " " + columnType(column.type()) + (column.notNull() ? " NOT NULL" : "")
                + (column.defaultValue() == null ? "" : " DEFAULT " + column.defaultValue());
    }




    static Map<String, PhysicalDatabaseSchema.TableSpec> readActualTables(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        if (schema == null) throw new SQLException("DATABASE_PRODUCT_UNSUPPORTED", "0A000");
        Map<String, PhysicalDatabaseSchema.TableSpec> result = new LinkedHashMap<>();
        var metadata = connection.getMetaData();
        String escapedSchema = metadataPattern(schema, metadata.getSearchStringEscape());
        try (var tables = metadata.getTables(connection.getCatalog(), escapedSchema, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                Set<String> nonBinaryText = nonBinaryTextColumns(connection, schema, name);
                Map<String, String> identities = identityModes(connection, schema, name);
                Map<String, Integer> primary = new LinkedHashMap<>();
                try (var keys = metadata.getPrimaryKeys(connection.getCatalog(), schema, name)) {
                    while (keys.next()) primary.put(keys.getString("COLUMN_NAME"), keys.getInt("KEY_SEQ"));
                }
                List<PhysicalDatabaseSchema.ColumnSpec> columns = new ArrayList<>();
                try (var cols = metadata.getColumns(connection.getCatalog(), escapedSchema,
                        metadataPattern(name, metadata.getSearchStringEscape()), "%")) {
                    while (cols.next()) {
                        String column = cols.getString("COLUMN_NAME");
                        String type = switch (cols.getString("TYPE_NAME")) {
                            case "int8" -> "BIGINT";
                            case "float8" -> "DOUBLE PRECISION";
                            default -> nonBinaryText.contains(column) ? "<unsupported collation>" : cols.getString("TYPE_NAME");
                        };
                        String defaultValue = cols.getString("COLUMN_DEF");
                        if (identities.containsKey(column)) {
                            defaultValue = "<identity:" + identities.get(column) + ">";
                        } else if (defaultValue != null) {
                            defaultValue = defaultValue.replaceFirst("::(?:text|bigint|double precision)$", "");
                        }
                        columns.add(new PhysicalDatabaseSchema.ColumnSpec(column, type,
                                cols.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNoNulls,
                                defaultValue, primary.getOrDefault(column, 0)));
                    }
                }
                result.put(name, new PhysicalDatabaseSchema.TableSpec(name, columns, readIndexes(connection, schema, name)));
            }
        }
        return result;
    }

    private static Map<String, String> identityModes(Connection connection, String schema, String table) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT a.attname, a.attidentity FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class t ON t.oid = a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname = ? AND NOT a.attisdropped AND a.attidentity <> ''
                """)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), rows.getString(2));
            }
        }
        return result;
    }

    private static String metadataPattern(String name, String escape) {
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    private static Set<String> nonBinaryTextColumns(Connection connection, String schema, String table) throws SQLException {
        Set<String> result = new HashSet<>();
        try (var statement = connection.prepareStatement("""
                SELECT a.attname FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class t ON t.oid = a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_catalog.pg_collation c ON c.oid = a.attcollation
                JOIN pg_catalog.pg_namespace cn ON cn.oid = c.collnamespace
                WHERE n.nspname = ? AND t.relname = ? AND NOT a.attisdropped
                  AND a.atttypid = 'pg_catalog.text'::regtype
                  AND NOT (cn.nspname = 'pg_catalog' AND c.collname = 'C')
                """)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return result;
    }

    private static List<PhysicalDatabaseSchema.IndexSpec> readIndexes(Connection connection, String schema,
                                                                    String table) throws SQLException {
        List<PhysicalDatabaseSchema.IndexSpec> result = new ArrayList<>();
        String sql = """
                SELECT idx.relname, i.indisunique, c.contype, a.attname, k.ordinality,
                       i.indpred IS NOT NULL OR i.indexprs IS NOT NULL
                         OR i.indnkeyatts <> i.indnatts OR i.indoption[k.ordinality - 1] <> 0 AS special
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
                LEFT JOIN pg_catalog.pg_constraint c ON c.conindid = i.indexrelid AND c.contype = 'u'
                CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum, ordinality)
                LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                WHERE n.nspname = ? AND t.relname = ? AND NOT i.indisprimary
                ORDER BY idx.relname, k.ordinality
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                String name = null;
                boolean unique = false;
                PhysicalDatabaseSchema.IndexOrigin origin = null;
                List<String> columns = new ArrayList<>();
                while (rows.next()) {
                    String next = rows.getString(1);
                    if (name != null && !name.equals(next)) {
                        result.add(new PhysicalDatabaseSchema.IndexSpec(name, origin, unique, columns));
                        columns = new ArrayList<>();
                    }
                    name = next;
                    unique = rows.getBoolean(2);
                    origin = "u".equals(rows.getString(3)) ? PhysicalDatabaseSchema.IndexOrigin.UNIQUE_CONSTRAINT
                            : PhysicalDatabaseSchema.IndexOrigin.CREATE_INDEX;
                    columns.add(rows.getBoolean(6) ? "<unsupported>" : rows.getString(4));
                }
                if (name != null) result.add(new PhysicalDatabaseSchema.IndexSpec(name, origin, unique, columns));
            }
        }
        return result;
    }

    static String quote(String identifier) {
        String name = PhysicalDatabaseSchema.normalizeIdentifier(identifier);
        if (!name.matches("[a-z_][a-z0-9_]{0,62}")) throw unsupported(identifier);
        return '"' + name + '"';
    }

    private static IllegalArgumentException unsupported(String object) {
        return new IllegalArgumentException("DATABASE_SCHEMA_UNSUPPORTED");
    }
}
