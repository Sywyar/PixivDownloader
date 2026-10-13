package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.storage.relational.jdbc.JdbcDatabaseSession;
import java.sql.Connection;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.quote;

final class SqliteStorageSession extends JdbcDatabaseSession {
@Override protected void createCleanup(top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.Cleanup rule) throws java.sql.SQLException {
        SqliteStorageFeatures.cleanup(connection, rule);
    }
    @Override protected top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndexMode createTextIndex(
            top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndex index) throws java.sql.SQLException {
        return SqliteStorageFeatures.initialize(connection, index);
    }
    @Override protected top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndexMode refreshIndex(
            top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndex index, Long key) throws java.sql.SQLException {
        return SqliteStorageFeatures.refresh(connection, index, key);
    }
    @Override protected java.util.List<Long> searchIndex(
            top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndex index, String term) throws java.sql.SQLException {
        return SqliteStorageFeatures.search(connection, index, term);
    }
    @Override protected String updateReturningStatement(String update, java.util.List<String> columns) {
        return update + " RETURNING " + String.join(",", columns);
    }
    @Override protected java.sql.SQLException storageFailure(java.sql.SQLException failure) {
        if (!(failure instanceof org.sqlite.SQLiteException sqlite)) return super.storageFailure(failure);
        String state = switch (sqlite.getResultCode()) {
            case SQLITE_CONSTRAINT_UNIQUE, SQLITE_CONSTRAINT_PRIMARYKEY -> "23505";
            case SQLITE_CONSTRAINT_NOTNULL -> "23502";
            case SQLITE_CONSTRAINT_CHECK -> "23514";
            case SQLITE_CONSTRAINT_FOREIGNKEY -> "23503";
            case SQLITE_BUSY, SQLITE_BUSY_SNAPSHOT, SQLITE_LOCKED -> "40001";
            default -> "HY000";
        };
        return new java.sql.SQLException("STORAGE_OPERATION_FAILED", state, failure.getErrorCode());
    }
    // SQLite 在写入显式自增主键时原生维护 sqlite_sequence。
    @Override protected void beforeInsert(String table, java.util.Set<String> columns) {}
    @Override protected void afterInsert(String table, java.util.Set<String> columns) {}
    @Override protected String substring(String text, String start, String length) {
        return "substr(" + text + "," + start + "," + length + ")";
    }
    @Override protected String epochMonth(String epochMillis, String zone, boolean fixedOffset) {
        return "storage_epoch_month(" + epochMillis + "," + zone + ")";
    }
    SqliteStorageSession(Connection connection, MigrationSchema schema) { super(connection, schema); }
    @Override protected String lowerHex(String value) { return "(" + value + " NOT GLOB '*[^0-9a-f]*'" + ")"; }
    @Override protected String lowerAscii(String value) { return "lower(" + value + ")"; }
    @Override protected String minimum(String left, String right) {
        return "min(" + left + "," + right + ")";
    }
    @Override protected String conflictClause(String table, Conflict conflict, String updates) {
        if (conflict.mode() == Mode.ERROR) return "";
        String keys = conflict.keys().isEmpty() ? "" : " (" + String.join(",", conflict.keys().stream()
                .map(top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport::quote).toList()) + ")";
        return " ON CONFLICT" + keys + (conflict.mode() == Mode.IGNORE ? " DO NOTHING" : " DO UPDATE SET " + updates);
    }
    @Override protected String generatedInsertStatement(String insert, String key) {
        return insert + " RETURNING " + quote(key);
    }
    @Override protected String insertFromStatement(String table, java.util.List<String> columns, String select,
                                                  Conflict conflict, String updates) {
        return "INSERT INTO " + quote(table) + " (" + String.join(",", columns) + ") SELECT "
                + String.join(",", columns) + " FROM (" + select + ") AS incoming WHERE 1=1"
                + conflictClause(table, conflict, updates);
    }
    @Override protected String arithmetic(top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator operator,
                                          String left, String right, boolean integral) {
        String symbol = switch (operator) { case ADD -> "+"; case SUBTRACT -> "-"; case MULTIPLY -> "*"; default -> throw new IllegalArgumentException("STORAGE_OPERATION_INVALID"); };
        String expression = "(" + left + symbol + right + ")";
        String valid = integral ? "typeof(v)='integer'"
                : "typeof(v)='real' AND v>=-1.7976931348623157e308 AND v<=1.7976931348623157e308";
        return "(SELECT CASE WHEN v IS NULL OR (" + valid + ") THEN v ELSE abs(-9223372036854775808) END FROM (SELECT "
                + expression + " AS v))";
    }
}
