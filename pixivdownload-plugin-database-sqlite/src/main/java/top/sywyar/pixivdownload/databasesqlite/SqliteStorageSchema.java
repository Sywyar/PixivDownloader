package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.SchemaApplyResult;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema;
import java.sql.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.*;

/** 同一声明和连接用于普通启动、owner 演进与私有存储初始化。 */
final class SqliteStorageSchema {
    private SqliteStorageSchema() {}
    static SchemaApplyResult apply(Connection connection, MigrationSchema schema, boolean strict) throws SQLException {
        var savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
        try {
            var result = applyInTransaction(connection, schema, strict);
            if (savepoint != null) connection.releaseSavepoint(savepoint);
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            if (savepoint != null) {
                try { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    private static SchemaApplyResult applyInTransaction(Connection connection, MigrationSchema schema, boolean strict) throws SQLException {
        schema.tables().forEach(SqliteMigrationSchema::renderCheck);
        var expected = physical(schema);

        var before = SqliteMigrationSchema.readActualTables(connection);
        int created = 0, added = 0, indexed = 0;
        var skipped = new ArrayList<String>();
        for (var table : schema.tables()) {
            execute(connection, SqliteMigrationSchema.renderCreateTable(table));
            var old = before.get(table.name());
            if (old == null) created++;
            else for (var column : expected.tables().get(table.name()).columns()) {
                if (old.columns().stream().anyMatch(c -> c.name().equals(column.name()))) continue;
                if (column.primaryKeyPosition() > 0 || column.notNull() && column.defaultValue() == null) {
                    if (strict) throw new SQLException("STORAGE_UNSAFE_COLUMN_CHANGE: " + table.name() + "." + column.name(), "0A000");
                    skipped.add(table.name() + "." + column.name());
                    continue;
                }
                execute(connection, SqliteMigrationSchema.renderAddColumn(table.name(), column));
                added++;
            }
            for (var index : table.indexes()) {
                if (index.origin() != top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin.CREATE_INDEX) continue;
                execute(connection, createIndex(table.name(), index));
                if (old == null || old.indexes().stream().noneMatch(i -> index.name().equals(i.name()))) indexed++;
            }
        }

        if (strict || skipped.isEmpty()) {
            var actual = new LinkedHashMap<>(SqliteMigrationSchema.readActualTables(connection));
            actual.keySet().retainAll(expected.tables().keySet());
            try { requireSchema(expected, actual); }
            catch (Exception failure) { throw new SQLException("STORAGE_SCHEMA_MISMATCH", "0A000"); }
            for (var table : schema.tables()) SqliteCheckMigration.apply(connection, table);
            SqliteMigrationSchema.validateConstraints(connection, schema);
        }
        return new SchemaApplyResult(created, added, indexed, skipped);
    }
}
