package top.sywyar.pixivdownload.databasesqlite;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.sql.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@DisplayName("SQLite 已知 CHECK 约束事务迁移")
class SqliteCheckMigrationTest {
    private TableSpec table() {
        return new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", true, null, 1, true),
                new ColumnSpec("active", "INTEGER", true, "1", 0),
                new ColumnSpec("body", "BLOB", false, null, 0)),
                List.of(new IndexSpec("idx_facts_active", IndexOrigin.CREATE_INDEX, false, List.of("active"))))
                .withCheck(call(AND, call(GREATER, column("facts", "id"), value(0)),
                        call(IN, column("facts", "active"), value(0), value(1))))
                .withPreviousChecks(List.of(call(GREATER, column("facts", "id"), value(0))));
    }

    private MigrationSchema schema() { return new MigrationSchema(1, List.of(table()), List.of(), List.of()); }

    private void create(Connection connection, String check, int active) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, active INTEGER NOT NULL DEFAULT 1, body BLOB, CHECK(" + check + "))");
            sql.execute("CREATE INDEX idx_facts_active ON facts(active)");
            sql.execute("INSERT INTO facts VALUES(1," + active + ",X'0001FF'),(200,1,NULL)");
            sql.execute("DELETE FROM facts WHERE id=200");
        }
    }

    private long scalar(Connection connection, String query) throws Exception {
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(query)) {
            assertThat(rows.next()).isTrue(); return rows.getLong(1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @DisplayName("升级保留数据、索引、触发器、视图和已删除生成键高水位，重复执行无变化")
    void migratesKnownCheckWithoutDataLoss(boolean strict) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = connection.createStatement()) {
            create(connection, "id>0", 1);
            sql.execute("CREATE TABLE audit(id INTEGER)");
            sql.execute("CREATE TRIGGER facts_insert AFTER INSERT ON facts BEGIN INSERT INTO audit VALUES(new.id); END");
            sql.execute("CREATE VIEW facts_view AS SELECT * FROM facts");
            connection.setAutoCommit(false);
            SqliteStorageSchema.apply(connection, schema(), strict);
            SqliteMigrationSchema.validateConstraints(connection, schema());
            long version = scalar(connection, "PRAGMA schema_version");
            SqliteStorageSchema.apply(connection, schema(), strict);
            assertThat(scalar(connection, "PRAGMA schema_version")).isEqualTo(version);
            assertThat(scalar(connection, "SELECT count(*) FROM audit")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM facts_view WHERE hex(body)='0001FF'")).isEqualTo(1);
            sql.execute("INSERT INTO facts(active) VALUES(0)");
            assertThat(scalar(connection, "SELECT max(id) FROM facts")).isEqualTo(201);
            assertThat(scalar(connection, "SELECT count(*) FROM audit")).isEqualTo(1);
            assertThatThrownBy(() -> sql.execute("INSERT INTO facts(active) VALUES(2)")).isInstanceOf(SQLException.class);
            connection.commit();
        }
    }

    @Test @DisplayName("不合规数据与未知旧约束均拒绝并完整保留原库")
    void refusesInvalidDataAndUnknownChecks() throws Exception {
        for (String check : List.of("id>0", "id>=0")) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = connection.createStatement()) {
                create(connection, check, 2);
                connection.setAutoCommit(false);
                assertThatThrownBy(() -> SqliteStorageSchema.apply(connection, schema(), true)).isInstanceOfAny(SQLException.class, IllegalStateException.class);
                assertThat(scalar(connection, "SELECT active FROM facts")).isEqualTo(2);
                assertThat(scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='facts'")).isEqualTo(200);
                assertThat(scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%'")).isEqualTo(1);
                sql.execute("INSERT INTO facts(active) VALUES(2)");
                connection.rollback();
            }
        }
    }

    @Test @DisplayName("外层事务回滚恢复旧约束，迁移不能隐式提交调用方写入")
    void participatesInCallerRollback() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = connection.createStatement()) {
            create(connection, "id>0", 1);
            connection.setAutoCommit(false);
            sql.execute("UPDATE facts SET active=0");
            SqliteStorageSchema.apply(connection, schema(), true);
            connection.rollback();
            assertThat(scalar(connection, "SELECT active FROM facts")).isEqualTo(1);
            sql.execute("INSERT INTO facts(active) VALUES(2)");
            connection.rollback();
        }
    }

    @Test @DisplayName("被外键引用的表拒绝重建，保留引用数据且不触发级联删除")
    void refusesIncomingForeignKeys() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = connection.createStatement()) {
            sql.execute("PRAGMA foreign_keys=ON");
            create(connection, "id>0", 1);
            sql.execute("CREATE TABLE child(parent INTEGER REFERENCES facts(id) ON DELETE CASCADE)");
            sql.execute("INSERT INTO child VALUES(1)");
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> SqliteStorageSchema.apply(connection, schema(), true)).isInstanceOfAny(SQLException.class, IllegalStateException.class);
            assertThat(scalar(connection, "SELECT count(*) FROM child")).isEqualTo(1);
            connection.rollback();
        }
    }

    @Test @DisplayName("文本主键保留稀疏 rowid，字面量不应被误认成 AUTOINCREMENT")
    void preservesRowIdsAndLiteralTokens() throws Exception {
        var previous = call(GREATER, call(LENGTH, column("facts", "id")), value(0));
        var table = new TableSpec("facts", List.of(new ColumnSpec("id", "TEXT", true, null, 1),
                new ColumnSpec("active", "INTEGER", true, "1", 0),
                new ColumnSpec("body", "TEXT", false, "'AUTOINCREMENT'", 0)), List.of())
                .withCheck(call(AND, previous, call(IN, column("facts", "active"), value(0), value(1))))
                .withPreviousChecks(List.of(previous));
        var schema = new MigrationSchema(1, List.of(table), List.of(), List.of());
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id TEXT NOT NULL PRIMARY KEY, active INTEGER NOT NULL DEFAULT 1, body TEXT DEFAULT 'AUTOINCREMENT', CHECK(length(id)>0))");
            sql.execute("INSERT INTO facts(rowid,id) VALUES(98,'a')");
            connection.setAutoCommit(false);
            SqliteStorageSchema.apply(connection, schema, true);
            assertThat(scalar(connection, "SELECT rowid FROM facts")).isEqualTo(98);
            connection.commit();
        }
    }

    @Test @DisplayName("后续表校验失败撤回前表重建和补列，调用方已有写入仍可自行回滚")
    void rollsBackEntireSchemaApplication() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = connection.createStatement()) {
            create(connection, "id>0", 1);
            sql.execute("CREATE TABLE later(id INTEGER NOT NULL PRIMARY KEY, CHECK(id>0))");
            var later = new TableSpec("later", List.of(new ColumnSpec("id", "INTEGER", true, null, 1),
                    new ColumnSpec("added", "TEXT", false, null, 0)), List.of())
                    .withCheck(call(GREATER, column("later", "id"), value(1)));
            connection.setAutoCommit(false);
            sql.execute("UPDATE facts SET active=0");
            assertThatThrownBy(() -> SqliteStorageSchema.apply(connection,
                    new MigrationSchema(1, List.of(table(), later), List.of(), List.of()), true)).hasMessage("SCHEMA_MISMATCH");
            assertThat(scalar(connection, "SELECT active FROM facts")).isZero();
            assertThat(scalar(connection, "SELECT count(*) FROM pragma_table_info('later')")).isEqualTo(1);
            sql.execute("INSERT INTO facts(active) VALUES(2)");
            connection.rollback();
            assertThat(scalar(connection, "SELECT active FROM facts")).isEqualTo(1);
        }
    }
}
