package top.sywyar.pixivdownload.databasesqlite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DisplayName("SQLite 普通结构读取与严格迁移分离")
class SqliteRuntimeSchemaTest {
    @TempDir Path directory;

    @org.junit.jupiter.api.AfterEach
    void cleanDirectoryAfterResourcesClose() throws java.io.IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"body TEXT COLLATE NOCASE", "\"body\" TEXT COLLATE RTRIM",
            "body TEXT NOT NULL ON CONFLICT REPLACE DEFAULT 'fallback'"})
    @DisplayName("无 CHECK 声明的受管列仍须保留比较与冲突语义")
    void refusesManagedWriteSemantics(String definition) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY," + definition + ")");
            var schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                    new ColumnSpec("id", "INTEGER", false, null, 1),
                    new ColumnSpec("body", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
            assertThatThrownBy(() -> SqliteStorageSchema.apply(connection, schema, false))
                    .isInstanceOf(IllegalStateException.class).hasMessage("SCHEMA_MISMATCH");
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("引号注释与默认值中的约束文字不应误判，附加列策略保持不变")
    void preservesQuotedAdditionalDefinitions() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts([id] INTEGER PRIMARY KEY, \"body\" TEXT,"
                    + " \"extra,field\" TEXT COLLATE NOCASE NOT NULL ON CONFLICT IGNORE"
                    + " DEFAULT 'ON CONFLICT REPLACE, COLLATE NOCASE', CHECK(length(body)>=0))");
            var schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                    new ColumnSpec("id", "INTEGER", false, null, 1),
                    new ColumnSpec("body", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
            SqliteStorageSchema.apply(connection, schema, false);
            sql.execute("INSERT INTO facts(id,body) VALUES(1,'kept')");
            try (var row = sql.executeQuery("SELECT \"extra,field\" FROM facts")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("ON CONFLICT REPLACE, COLLATE NOCASE");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"body DESC", "lower(body)", "body COLLATE NOCASE", "body) WHERE id>0 --"})
    @DisplayName("附加物理索引可读取和保留，但严格迁移仍拒绝")
    void preservesAdditionalIndexes(String indexed) throws Exception {
        var target = new SqliteConnectionTarget(directory.resolve("source.db"));
        var schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", false, null, 1),
                new ColumnSpec("body", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
        try (var connection = target.openConnection(OpenMode.READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT,extra TEXT)");
            sql.execute("INSERT INTO facts VALUES(1,'keep','extra data')");
            sql.execute("CREATE INDEX auxiliary ON facts(" + indexed + ")");
            sql.execute("CREATE TABLE absent_plugin(id INTEGER PRIMARY KEY,body TEXT)");
            sql.execute("CREATE INDEX plugin_auxiliary ON absent_plugin(lower(body))");
            sql.execute("INSERT INTO absent_plugin VALUES(2,'plugin data')");
            assertThat(target.readSchema(connection).get("facts").indexes())
                    .anySatisfy(index -> {
                        assertThat(index.name()).isEqualTo("auxiliary");
                        assertThat(index.columns()).isEmpty();
                    });
            connection.setAutoCommit(false);
            target.applySchema(connection, schema, false);
            connection.commit();
            assertThatThrownBy(() -> new SqliteDatabasePlugin().inspectMigration(target, schema, true))
                    .isInstanceOf(IllegalStateException.class);
            try (var row = sql.executeQuery("SELECT extra FROM facts")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("extra data");
            }
            try (var row = sql.executeQuery("SELECT body FROM absent_plugin")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("plugin data");
            }
            assertThat(target.readSchema(connection).get("facts").indexes()).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ", extra TEXT CHECK(length(extra)>0)",
            ", extra INTEGER REFERENCES other(id)",
            ", extra TEXT GENERATED ALWAYS AS (body) VIRTUAL",
            ", extra TEXT COLLATE NOCASE",
            ", extra TEXT NOT NULL ON CONFLICT IGNORE DEFAULT 'value'"
    })
    @DisplayName("普通补齐不将附加约束视为迁出准入，严格约束核验仍拒绝")
    void preservesAdditionalConstraints(String extra) throws Exception {
        var target = new SqliteConnectionTarget(directory.resolve("source.db"));
        var schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", false, null, 1),
                new ColumnSpec("body", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
        try (var connection = target.openConnection(OpenMode.READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT" + extra + ")");
            sql.execute("INSERT INTO facts(id,body) VALUES(1,'keep')");
            connection.setAutoCommit(false);
            target.applySchema(connection, schema, false);
            connection.commit();
            assertThat(target.readSchema(connection)).containsKey("facts");
            assertThatThrownBy(() -> SqliteMigrationSchema.validateConstraints(connection, schema))
                    .hasMessage("SCHEMA_MISMATCH");
            try (var row = sql.executeQuery("SELECT body FROM facts")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("keep");
            }
        }
    }
}
