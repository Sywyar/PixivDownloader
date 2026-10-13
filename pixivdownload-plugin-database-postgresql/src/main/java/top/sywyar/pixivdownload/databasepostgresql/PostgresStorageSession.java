package top.sywyar.pixivdownload.databasepostgresql;

import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.storage.relational.jdbc.JdbcDatabaseSession;
import java.sql.Connection;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.quote;

final class PostgresStorageSession extends JdbcDatabaseSession {
@Override protected void createCleanup(top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.Cleanup rule) throws java.sql.SQLException {
        PostgresStorageFeatures.cleanup(connection, rule);
    }
    @Override protected top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndexMode createTextIndex(
            top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndex index) throws java.sql.SQLException {
        return top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndexMode.SOURCE_QUERY;
    }
    @Override protected top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndexMode refreshIndex(
            top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndex index, Long key) throws java.sql.SQLException {
        return top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndexMode.SOURCE_QUERY;
    }
    @Override protected java.util.List<Long> searchIndex(
            top.sywyar.pixivdownload.plugin.api.storage.relational.StorageFeatures.TextIndex index, String term) throws java.sql.SQLException {
        return PostgresStorageFeatures.search(connection, index, term);
    }
    @Override protected String updateReturningStatement(String update, java.util.List<String> columns) {
        return update + " RETURNING " + String.join(",", columns);
    }
    private java.util.Optional<String> explicitIdentity(String table, java.util.Set<String> columns) {
        return schema.table(table).columns().stream().filter(c -> c.autoIncrement() && columns.contains(c.name()))
                .map(top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec::name).findFirst();
    }
    @Override protected void beforeInsert(String table, java.util.Set<String> columns) throws java.sql.SQLException {
        if (explicitIdentity(table, columns).isPresent()) {
            try (var statement = connection.createStatement()) {
                statement.execute("LOCK TABLE " + quote(table) + " IN SHARE ROW EXCLUSIVE MODE");
            }
        }
    }
    @Override protected void afterInsert(String table, java.util.Set<String> columns) throws java.sql.SQLException {
        var key = explicitIdentity(table, columns);
        if (key.isEmpty()) return;
        // 表锁阻止其它写入在读取最大身份与推进序列之间分配重复身份；回滚允许留空号。
        try (var statement = connection.prepareStatement(
                "SELECT setval(pg_get_serial_sequence(?,?), GREATEST(nextval(pg_get_serial_sequence(?,?)), "
                + "COALESCE((SELECT MAX(" + quote(key.get()) + ") FROM " + quote(table) + "),1)), true)")) {
            statement.setString(1, quote(table)); statement.setString(2, key.get());
            statement.setString(3, quote(table)); statement.setString(4, key.get());
            statement.execute();
        }
    }
    @Override protected String substring(String text, String start, String length) {
        return "substring(" + text + ",CAST(" + start + " AS INTEGER),CAST(" + length + " AS INTEGER))";
    }
    @Override protected String epochMonth(String epochMillis, String zone, boolean fixedOffset) {
        return "to_char(to_timestamp(" + epochMillis + "/1000.0) AT TIME ZONE "
                + (fixedOffset ? "CAST(" + zone + " AS INTERVAL)" : zone) + ",'YYYY-MM')";
    }
    static void initialize(Connection connection, MigrationSchema schema) throws java.sql.SQLException {
        try {
            PostgresStorageSchema.apply(connection, schema, true);
            PostgresMigration.validateObjects(connection, schema, java.util.Set.of());
            PostgresMigration.validateSchema(connection, schema);
        } catch (java.sql.SQLException failure) { throw failure; }
        catch (Exception failure) { throw new java.sql.SQLException("STORAGE_SCHEMA_MISMATCH", "0A000"); }
    }
    PostgresStorageSession(Connection connection, MigrationSchema schema) { super(connection, schema); }
    @Override protected String lowerHex(String value) { return "(" + value + " !~ '[^0-9a-f]'" + ")"; }
    @Override protected String lowerAscii(String value) { return "translate(" + value + ",\u0027ABCDEFGHIJKLMNOPQRSTUVWXYZ\u0027,\u0027abcdefghijklmnopqrstuvwxyz\u0027)"; }
    @Override protected String minimum(String left, String right) {
        return "(SELECT CASE WHEN v.a IS NULL OR v.b IS NULL THEN NULL ELSE LEAST(v.a,v.b) END FROM (VALUES (" + left + "," + right + ")) AS v(a,b))";
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
        return expression;
    }
}
