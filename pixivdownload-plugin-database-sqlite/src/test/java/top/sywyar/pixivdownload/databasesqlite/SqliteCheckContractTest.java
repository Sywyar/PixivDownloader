package top.sywyar.pixivdownload.databasesqlite;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

@DisplayName("SQLite 中性 CHECK 与旧字符串入口")
class SqliteCheckContractTest {
    private TableSpec table() {
        return new TableSpec("facts", List.of(new ColumnSpec("id", "INTEGER", true, null, 1),
                new ColumnSpec("body", "TEXT", false, null, 0)), List.of());
    }
    private MigrationSchema schema(TableSpec table) {
        return new MigrationSchema(1, List.of(table), List.of(), List.of());
    }

    @Test @DisplayName("中性十六进制约束保留 NULL 与空串语义，并识别既有小写 SQL")
    void validatesNeutralAndExistingChecks() throws Exception {
        var table = table().withCheck(call(AND, call(GREATER, column("facts", "id"), value(0)),
                call(LOWER_HEX, column("facts", "body"))));
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER NOT NULL PRIMARY KEY, body TEXT, CHECK(id>0 and body not glob '*[^0-9a-f]*'))");
            SqliteMigrationSchema.validateConstraints(connection, schema(table));
            sql.execute("DROP TABLE facts");
            SqliteStorageSchema.apply(connection, schema(table), true);
            sql.execute("INSERT INTO facts VALUES(1,NULL),(2,''),(3,'0123456789abcdef')");
            for (String invalid : List.of("A", "g", "é", "🌸", "0\n", "a'")) {
                try (var write = connection.prepareStatement("INSERT INTO facts VALUES(4,?)")) {
                    write.setString(1, invalid);
                    assertThatThrownBy(write::executeUpdate).isInstanceOf(SQLException.class);
                }
            }
            assertThatThrownBy(() -> sql.execute("INSERT INTO facts VALUES(0,'a')")).isInstanceOf(SQLException.class);
            sql.execute("DROP TABLE facts");
            sql.execute("CREATE TABLE facts(id INTEGER NOT NULL PRIMARY KEY, body TEXT, CHECK(id>0))");
            assertThatThrownBy(() -> SqliteMigrationSchema.validateConstraints(connection, schema(table))).hasMessage("SCHEMA_MISMATCH");
        }
    }

    @Test @DisplayName("DDL 注释不改变默认冲突策略，CHECK 字面量中的注释符必须保留")
    void preservesLiteralCommentMarkers() throws Exception {
        var table = table().withCheck(call(EQUAL, column("facts", "body"), value("/* keep */ -- $1")));
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER NOT NULL ON /* default */ CONFLICT ABORT PRIMARY KEY, "
                    + "body TEXT, CHECK(body = '/* keep */ -- $1'))");
            SqliteMigrationSchema.validateConstraints(connection, schema(table));
            sql.execute("INSERT INTO facts VALUES(1,'/* keep */ -- $1')");
            assertThatThrownBy(() -> sql.execute("INSERT INTO facts VALUES(2,'wrong')")).isInstanceOf(SQLException.class);
        }
    }

    @ParameterizedTest @DisplayName("旧字符串入口在任何建表前拒绝注入和未声明函数")
    @ValueSource(strings = {"id=1); DROP TABLE facts; --", "secret_function(id)=1", "id=1 --comment", "body(body)"})
    void rejectsLegacySqlBeforeDdl(String expression) throws Exception {
        var first = new TableSpec("before_table", table().columns(), List.of());
        var invalid = new TableSpec("facts", table().columns(), List.of(), expression);
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertThatThrownBy(() -> SqliteStorageSchema.apply(connection,
                    new MigrationSchema(1, List.of(first, invalid), List.of(), List.of()), true))
                    .hasMessage("DATABASE_SCHEMA_UNSUPPORTED");
            try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM sqlite_schema")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
            }
        }
    }
}
