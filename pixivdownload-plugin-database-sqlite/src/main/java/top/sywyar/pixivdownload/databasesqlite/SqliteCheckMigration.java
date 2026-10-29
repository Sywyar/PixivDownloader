package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSnapshot;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema;
import java.sql.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.normalizeIdentifier;

/** 在调用方写事务内重建已知旧约束，复制期间不执行业务触发器。 */
final class SqliteCheckMigration {
    private SqliteCheckMigration() {}

    static void apply(Connection connection, TableSpec table) throws SQLException {
        if (table.previousChecks().isEmpty()) return;
        var actual = SqliteMigrationSchema.readChecks(connection, table, false).orElseThrow(SqliteCheckMigration::unsupported);
        String current = SqliteMigrationSchema.normalizeCheck(SqliteMigrationSchema.renderCheck(table));
        if (actual.contains(current)) return;
        boolean known = table.previousChecks().stream().anyMatch(previous -> actual.equals(List.of(
                SqliteMigrationSchema.normalizeCheck(SqliteMigrationSchema.renderCheck(table.withCheck(previous))))));
        if (!known || connection.getAutoCommit()) throw unsupported();

        // 重建只复制声明列；完整核验避免兼容启动把附加数据或约束丢掉。
        SqliteMigrationSchema.readChecks(connection, table);
        var schema = new MigrationSchema(MigrationSnapshot.FORMAT_VERSION, List.of(table), List.of(), List.of());
        var found = SqliteMigrationSchema.readActualTables(connection, true, Set.of(table.name())).get(table.name());
        var expected = physical(schema).tables().get(table.name());
        var origin = PhysicalDatabaseSchema.IndexOrigin.UNIQUE_CONSTRAINT;
        requireSchema(new PhysicalDatabaseSchema.DatabaseSchema(
                Map.of(table.name(), new PhysicalDatabaseSchema.TableSpec(
                        table.name(), expected.columns(), expected.indexes().stream().filter(i -> i.origin() == origin).toList()))),
                Map.of(table.name(), new PhysicalDatabaseSchema.TableSpec(
                        table.name(), found.columns(), found.indexes().stream().filter(i -> i.origin() == origin).toList())));

        // 外键 DROP 行为可能级联；不通过关闭完整性检查来重建被引用的表。
        try (var sql = connection.createStatement(); var tables = sql.executeQuery("PRAGMA table_list")) {
            while (tables.next()) {
                if (!"main".equals(tables.getString("schema"))) continue;
                String name = normalizeIdentifier(tables.getString("name"));
                if (name.equalsIgnoreCase(table.name()) && (tables.getInt("wr") != 0 || tables.getInt("strict") != 0)) throw unsupported();
                try (var read = connection.createStatement(); var keys = read.executeQuery("PRAGMA foreign_key_list(" + quote(name) + ")")) {
                    while (keys.next()) if (table.name().equalsIgnoreCase(keys.getString("table"))) throw unsupported();
                }
            }
        }

        var objects = new ArrayList<String>();
        boolean autoIncrement;
        try (var read = connection.prepareStatement("SELECT type,sql FROM sqlite_schema WHERE lower(tbl_name)=? AND sql IS NOT NULL ORDER BY type,name")) {
            read.setString(1, table.name());
            autoIncrement = false;
            try (var rows = read.executeQuery()) {
                while (rows.next()) {
                    if (rows.getString(1).equals("table")) {
                        autoIncrement = SqliteMigrationSchema.usesAutoIncrement(rows.getString(2));
                    } else if (Set.of("index", "trigger").contains(rows.getString(1))) objects.add(rows.getString(2));
                }
            }
        }
        Long sequence = null;
        if (autoIncrement) {
            try (var read = connection.prepareStatement("SELECT seq FROM sqlite_sequence WHERE lower(name)=?")) {
                read.setString(1, table.name());
                try (var rows = read.executeQuery()) { if (rows.next()) sequence = rows.getLong(1); }
            }
        }
        // 跨库迁入可能已提升 INTEGER 主键为 AUTOINCREMENT，不能丢掉历史高水位。
        var ddlTable = table;
        if (autoIncrement && table.columns().stream().noneMatch(c -> c.autoIncrement())) {
            ddlTable = new TableSpec(table.name(), table.columns().stream().map(c ->
                    new top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec(c.name(), c.type(), c.notNull(),
                            c.defaultValue(), c.primaryKeyPosition(), c.primaryKeyPosition() == 1 && c.type().equals("INTEGER")))
                    .toList(), table.indexes(), table.checkExpression(), table.check());
        }
        String temporary = "check_migration_" + UUID.randomUUID().toString().replace("-", "");
        String rowid = List.of("rowid", "_rowid_", "oid").stream()
                .filter(alias -> table.columns().stream().noneMatch(c -> c.name().equalsIgnoreCase(alias))).findFirst().orElseThrow(SqliteCheckMigration::unsupported);
        String columns = quote(rowid) + ", " + String.join(", ", table.columns().stream().map(c -> quote(c.name())).toList());
        execute(connection, SqliteMigrationSchema.renderCreateTable(ddlTable, temporary));
        try {
            execute(connection, "INSERT INTO " + quote(temporary) + " (" + columns + ") SELECT " + columns + " FROM " + quote(table.name()));
        } catch (SQLException failure) { throw new SQLException("STORAGE_CHECK_MIGRATION_FAILED", failure.getSQLState()); }

        int legacyAlter;
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("PRAGMA legacy_alter_table")) {
            rows.next(); legacyAlter = rows.getInt(1);
        }
        // 保持外部视图 / 触发器的原表引用，表名只在同一事务内短暂缺席。
        execute(connection, "PRAGMA legacy_alter_table=ON");
        try {
            execute(connection, "DROP TABLE " + quote(table.name()));
            execute(connection, "ALTER TABLE " + quote(temporary) + " RENAME TO " + quote(table.name()));
        } finally { execute(connection, "PRAGMA legacy_alter_table=" + legacyAlter); }
        for (String ddl : objects) execute(connection, ddl);
        if (sequence != null) {
            try (var delete = connection.prepareStatement("DELETE FROM sqlite_sequence WHERE name=?");
                 var insert = connection.prepareStatement("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)")) {
                delete.setString(1, table.name()); delete.executeUpdate();
                insert.setString(1, table.name()); insert.setLong(2, sequence); insert.executeUpdate();
            }
        }
    }

    private static SQLException unsupported() { return new SQLException("STORAGE_SCHEMA_MISMATCH", "0A000"); }
}
