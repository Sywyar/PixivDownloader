package top.sywyar.pixivdownload.databasepostgresql;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.SchemaApplyResult;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema;
import java.sql.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.*;

/** 同一声明和连接用于普通启动、owner 演进与私有存储初始化。 */
final class PostgresStorageSchema {
    private PostgresStorageSchema() {}
    static void optimize(Connection connection) throws SQLException {
        var tables = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT quote_ident(schemaname) || '.' || quote_ident(tablename)"
                + " FROM pg_catalog.pg_tables WHERE schemaname = current_schema() ORDER BY tablename")) {
            while (rows.next()) tables.add(rows.getString(1));
        }
        try (var statement = connection.createStatement()) {
            for (String table : tables) statement.execute("ANALYZE " + table);
        }
    }
    static SchemaApplyResult apply(Connection connection, MigrationSchema schema, boolean strict) throws SQLException {
        var expected = physical(schema);
        PostgresMigrationSchema.validate(contributions(schema), expected);
        var before = PostgresMigrationSchema.readActualTables(connection);
        int created = 0, added = 0, indexed = 0;
        var skipped = new ArrayList<String>();
        for (var table : schema.tables()) {
            execute(connection, PostgresMigrationSchema.renderCreateTable(table));
            var old = before.get(table.name());
            if (old == null) created++;
            else for (var column : expected.tables().get(table.name()).columns()) {
                if (old.columns().stream().anyMatch(c -> c.name().equals(column.name()))) continue;
                if (column.primaryKeyPosition() > 0 || column.notNull() && column.defaultValue() == null) {
                    if (strict) throw new SQLException("STORAGE_UNSAFE_COLUMN_CHANGE: " + table.name() + "." + column.name(), "0A000");
                    skipped.add(table.name() + "." + column.name());
                    continue;
                }
                execute(connection, PostgresMigrationSchema.renderAddColumn(table.name(), column));
                added++;
            }
            for (var index : table.indexes()) {
                if (index.origin() != top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin.CREATE_INDEX) continue;
                execute(connection, createIndex(table.name(), index));
                if (old == null || old.indexes().stream().noneMatch(i -> index.name().equals(i.name()))) indexed++;
            }
        }
        PostgresMigrationSchema.validateConstraints(connection, contributions(schema));
        if (strict) {
            var actual = new LinkedHashMap<>(PostgresMigrationSchema.readActualTables(connection));
            actual.keySet().retainAll(expected.tables().keySet());
            try { requireSchema(PostgresMigrationSchema.physicalSchema(expected), actual); }
            catch (Exception failure) { throw new SQLException("STORAGE_SCHEMA_MISMATCH", "0A000"); }

        }
        return new SchemaApplyResult(created, added, indexed, skipped);
    }
}
