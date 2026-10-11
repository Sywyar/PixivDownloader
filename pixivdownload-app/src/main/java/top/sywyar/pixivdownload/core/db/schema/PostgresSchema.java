package top.sywyar.pixivdownload.core.db.schema;

import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.i18n.MessageBundles;
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
final class PostgresSchema {
    private PostgresSchema() {}

    static void validate(List<SchemaContribution> contributions, ManagedDatabaseSchema.DatabaseSchema schema) {
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
                index.columns().forEach(PostgresSchema::quote);
            });
        }
        for (var contribution : contributions) {
            for (var table : contribution.tables()) {
                renderCheck(table);
            }
        }
    }

    static String physicalType(String type) {
        return switch (ManagedDatabaseSchema.normalizeType(type)) {
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

    static ManagedDatabaseSchema.DatabaseSchema physicalSchema(ManagedDatabaseSchema.DatabaseSchema schema) {
        Map<String, ManagedDatabaseSchema.TableSpec> tables = new LinkedHashMap<>();
        schema.tables().forEach((name, table) -> tables.put(name, new ManagedDatabaseSchema.TableSpec(
                name, table.columns().stream().map(column -> new ManagedDatabaseSchema.ColumnSpec(
                        column.name(), physicalType(column.type()),
                        column.notNull() || column.primaryKeyPosition() > 0,
                        generatedKey(table, column) ? "<identity:d>" : column.defaultValue(),
                        column.primaryKeyPosition())).toList(), table.indexes())));
        return new ManagedDatabaseSchema.DatabaseSchema(tables);
    }

    private static boolean generatedKey(ManagedDatabaseSchema.TableSpec table, ManagedDatabaseSchema.ColumnSpec column) {
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
                    statement.setString(1, ManagedDatabaseSchema.normalizeIdentifier(table.name()));
                    List<String> actual = new ArrayList<>();
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            actual.add("c".equals(rows.getString(1)) && rows.getBoolean(3)
                                    ? rows.getString(2) : "<unsupported>");
                        }
                    }
                    List<String> expected = table.checkExpression() == null || table.checkExpression().isBlank()
                            ? List.of() : List.of(canonicalCheck(connection, table));
                    if (!actual.equals(expected)) {
                        throw new IllegalStateException(MessageBundles.get("core.db.schema-mismatch", table.name() + ".CHECK"));
                    }
                }
            }
        }
    }

    private static String canonicalCheck(Connection connection, TableSpec table) throws SQLException {
        // 由 PostgreSQL 自己解析与规范化表达式，避免在宿主复制 SQL 语法树。
        String temporary = "schema_check_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var columns = table.columns().stream()
                .map(column -> quote(column.name()) + " " + columnType(column.type())).toList();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE " + quote(temporary) + " ("
                    + String.join(", ", columns) + ", CHECK (" + renderCheck(table) + "))");
            String expression;
            try (var rows = statement.executeQuery("SELECT pg_get_expr(conbin, conrelid)"
                    + " FROM pg_catalog.pg_constraint WHERE conrelid = 'pg_temp." + temporary + "'::regclass")) {
                if (!rows.next()) throw new SQLException("Missing temporary CHECK");
                expression = rows.getString(1);
            }
            statement.execute("DROP TABLE pg_temp." + quote(temporary));
            return expression;
        }
    }

    private static String renderCheck(TableSpec table) {
        if (table.checkExpression() == null || table.checkExpression().isBlank()) return "";
        // 现有 schema 的十六进制字符校验；不把任意 GLOB 模式当作正则表达式。
        String expression = table.checkExpression().replace("NOT GLOB '*[^0-9a-f]*'", "!~ '[^0-9a-f]'");
        Set<String> identifiers = new HashSet<>(List.of("and", "or", "not", "in", "is", "null", "length", "trim"));
        table.columns().forEach(column -> identifiers.add(ManagedDatabaseSchema.normalizeIdentifier(column.name())));
        var tokens = java.util.regex.Pattern.compile(
                "\\s+|'(?:[^']|'')*'|[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+|!~|<>|<=|>=|[()=<>+,.-]").matcher(expression);
        int end = 0;
        while (tokens.find()) {
            String token = tokens.group();
            if (tokens.start() != end || (Character.isLetter(token.charAt(0)) || token.charAt(0) == '_')
                    && !identifiers.contains(token.toLowerCase(java.util.Locale.ROOT))) {
                throw unsupported(table.name() + ".CHECK");
            }
            end = tokens.end();
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
                        .map(PostgresSchema::quote).toList()) + ")");
            }
        }
        if (table.checkExpression() != null && !table.checkExpression().isBlank()) {
            parts.add("CHECK (" + renderCheck(table) + ")");
        }
        return "CREATE TABLE IF NOT EXISTS " + quote(table.name()) + " (" + String.join(", ", parts) + ")";
    }

    static String renderAddColumn(String table, ManagedDatabaseSchema.ColumnSpec column) {
        return "ALTER TABLE " + quote(table) + " ADD COLUMN " + quote(column.name())
                + " " + columnType(column.type()) + (column.notNull() ? " NOT NULL" : "")
                + (column.defaultValue() == null ? "" : " DEFAULT " + column.defaultValue());
    }

    static Set<String> existingTableNames(JdbcTemplate jdbc) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = current_schema()", String.class));
    }

    static Set<String> existingColumnNames(JdbcTemplate jdbc, String table) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ?
                """, String.class, table));
    }

    static Set<String> existingIndexNames(JdbcTemplate jdbc) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT indexname FROM pg_catalog.pg_indexes WHERE schemaname = current_schema()", String.class));
    }

    static Map<String, ManagedDatabaseSchema.TableSpec> readActualTables(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        if (schema == null) throw new SQLException(MessageBundles.get("core.db.unsupported-product"), "0A000");
        Map<String, ManagedDatabaseSchema.TableSpec> result = new LinkedHashMap<>();
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
                List<ManagedDatabaseSchema.ColumnSpec> columns = new ArrayList<>();
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
                        columns.add(new ManagedDatabaseSchema.ColumnSpec(column, type,
                                cols.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNoNulls,
                                defaultValue, primary.getOrDefault(column, 0)));
                    }
                }
                result.put(name, new ManagedDatabaseSchema.TableSpec(name, columns, readIndexes(connection, schema, name)));
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

    private static List<ManagedDatabaseSchema.IndexSpec> readIndexes(Connection connection, String schema,
                                                                    String table) throws SQLException {
        List<ManagedDatabaseSchema.IndexSpec> result = new ArrayList<>();
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
                ManagedDatabaseSchema.IndexOrigin origin = null;
                List<String> columns = new ArrayList<>();
                while (rows.next()) {
                    String next = rows.getString(1);
                    if (name != null && !name.equals(next)) {
                        result.add(new ManagedDatabaseSchema.IndexSpec(name, origin, unique, columns));
                        columns = new ArrayList<>();
                    }
                    name = next;
                    unique = rows.getBoolean(2);
                    origin = "u".equals(rows.getString(3)) ? ManagedDatabaseSchema.IndexOrigin.UNIQUE_CONSTRAINT
                            : ManagedDatabaseSchema.IndexOrigin.CREATE_INDEX;
                    columns.add(rows.getBoolean(6) ? "<unsupported>" : rows.getString(4));
                }
                if (name != null) result.add(new ManagedDatabaseSchema.IndexSpec(name, origin, unique, columns));
            }
        }
        return result;
    }

    static String quote(String identifier) {
        String name = ManagedDatabaseSchema.normalizeIdentifier(identifier);
        if (!name.matches("[a-z_][a-z0-9_]{0,62}")) throw unsupported(identifier);
        return '"' + name + '"';
    }

    private static IllegalArgumentException unsupported(String object) {
        return new IllegalArgumentException(MessageBundles.get("core.db.unsupported-schema", object));
    }
}
