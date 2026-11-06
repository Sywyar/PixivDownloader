package top.sywyar.pixivdownload.databasesqlite;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.sql.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DisplayName("SQLite 联合主键扩展")
class SqlitePrimaryKeyMigrationTest {
    private MigrationSchema schema() {
        var table = new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", true, null, 1),
                new ColumnSpec("lang", "TEXT", true, null, 2),
                new ColumnSpec("body", "TEXT", true, null, 0),
                new ColumnSpec("engine", "TEXT", true, "'unknown'", 3)), List.of())
                .withPreviousPrimaryKey(List.of("id", "lang"));
        return new MigrationSchema(1, List.of(table), List.of(), List.of());
    }

    private void create(Statement sql) throws SQLException {
        sql.execute("CREATE TABLE facts(id INTEGER NOT NULL,lang TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(id,lang))");
        sql.execute("INSERT INTO facts(rowid,id,lang,body) VALUES(91,7,'en','原文😀')");
    }

    @Test @DisplayName("升级保留旧内容、rowid、视图与触发器，新键可并存且重复升级无变化")
    void preservesRowsAndDependentObjects() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = c.createStatement()) {
            create(sql);
            sql.execute("CREATE TABLE audit(id INTEGER)");
            sql.execute("CREATE TRIGGER facts_insert AFTER INSERT ON facts BEGIN INSERT INTO audit VALUES(new.id); END");
            sql.execute("CREATE VIEW fact_view AS SELECT * FROM facts");
            c.setAutoCommit(false);
            SqliteStorageSchema.apply(c, schema(), false);
            SqliteStorageSchema.apply(c, schema(), true);
            try (var rows = sql.executeQuery("SELECT rowid,body,engine FROM facts")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(91);
                assertThat(rows.getString(2)).isEqualTo("原文😀");
                assertThat(rows.getString(3)).isEqualTo("unknown");
            }
            sql.execute("INSERT INTO facts VALUES(7,'en','new','provider')");
            try (var rows = sql.executeQuery("SELECT count(*) FROM fact_view")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM audit")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
            c.commit();
        }
    }

    @Test @DisplayName("调用方回滚恢复旧主键和列，自动提交时在写入前拒绝")
    void rollsBackAndRequiresTransaction() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = c.createStatement()) {
            create(sql);
            assertThatThrownBy(() -> SqliteStorageSchema.apply(c, schema(), true)).isInstanceOf(SQLException.class);
            c.setAutoCommit(false);
            SqliteStorageSchema.apply(c, schema(), true);
            c.rollback();
            try (var rows = sql.executeQuery("SELECT count(*) FROM pragma_table_info('facts')")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(3);
            }
            assertThatThrownBy(() -> sql.execute("INSERT INTO facts VALUES(7,'en','duplicate')")).isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    @Test @DisplayName("外键依赖阻止升级时回滚补列并保留所有引用行")
    void rejectsForeignKeyWithoutLosingRows() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = c.createStatement()) {
            sql.execute("PRAGMA foreign_keys=ON");
            create(sql);
            sql.execute("CREATE TABLE child(id INTEGER,lang TEXT,FOREIGN KEY(id,lang) REFERENCES facts(id,lang) ON DELETE CASCADE)");
            sql.execute("INSERT INTO child VALUES(7,'en')");
            c.setAutoCommit(false);
            assertThatThrownBy(() -> SqliteStorageSchema.apply(c, schema(), true)).isInstanceOfAny(SQLException.class, IllegalStateException.class);
            try (var rows = sql.executeQuery("SELECT count(*) FROM child")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM pragma_table_info('facts')")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(3);
            }
            c.rollback();
        }
    }
}
