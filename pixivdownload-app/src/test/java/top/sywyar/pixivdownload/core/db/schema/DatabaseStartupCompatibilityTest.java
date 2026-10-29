package top.sywyar.pixivdownload.core.db.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DisplayName("普通启动保留附加结构并拒绝受管不兼容")
class DatabaseStartupCompatibilityTest {
    @TempDir Path directory;

    @org.junit.jupiter.api.AfterEach
    void cleanDirectoryAfterConnectionsClose() throws java.io.IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("通过启动核验的受管唯一约束在冲突更新时拒绝并保留两行")
    void compatibleUniqueConstraintDoesNotReplaceRows() throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("abort.db"));
        var declared = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", false, null, 1),
                new ColumnSpec("stamp", "INTEGER", false, null, 0)),
                List.of(new IndexSpec(null, IndexOrigin.UNIQUE_CONSTRAINT, true, List.of("stamp"))))),
                List.of(), List.of());
        try (var connection = target.openConnection()) {
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,stamp INTEGER UNIQUE ON CONFLICT ABORT)");
                sql.execute("INSERT INTO facts VALUES(1,10),(2,20)");
            }
            DatabaseInitializer.offline(connection, target, JdbcMigrationSupport.contributions(declared),
                    JdbcMigrationSupport.physical(declared)).initialize();
            try (var session = target.storageSession(connection, declared)) {
                var expressions = java.util.Map.<String, top.sywyar.pixivdownload.plugin.api.storage.relational.Expression>of(
                        "stamp", top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.value(10));
                var condition = top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.call(
                        top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.EQUAL,
                        top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.column("facts", "id"),
                        top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.value(2));
                assertThatThrownBy(() -> session.update("facts", expressions, condition)).isInstanceOf(java.sql.SQLException.class);
            }
            try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT count(*),sum(stamp) FROM facts")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(2);
                assertThat(row.getInt(2)).isEqualTo(30);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "stamp INTEGER UNIQUE ON CONFLICT REPLACE",
            "stamp INTEGER, CONSTRAINT stamp_key UNIQUE(stamp) ON /* retained */ CONFLICT REPLACE",
            "stamp INTEGER, UNIQUE(stamp) ON CONFLICT IGNORE",
            "stamp INTEGER, PRIMARY KEY(id) ON CONFLICT REPLACE"
    })
    @DisplayName("普通启动拒绝受管冲突策略漂移，保留发生冲突前的全部行")
    void refusesManagedConflictPolicies(String stampDdl) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("conflict.db"));
        boolean tablePrimaryKey = stampDdl.contains("PRIMARY KEY");
        var declared = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", false, null, 1),
                new ColumnSpec("stamp", "INTEGER", false, null, 0)),
                tablePrimaryKey ? List.of() : List.of(new IndexSpec(null, IndexOrigin.UNIQUE_CONSTRAINT, true, List.of("stamp"))))),
                List.of(), List.of());
        try (var connection = target.openConnection()) {
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE facts(id INTEGER" + (tablePrimaryKey ? "" : " PRIMARY KEY") + "," + stampDdl + ")");
                sql.execute("INSERT INTO facts VALUES(1,10),(2,20)");
            }
            assertThatThrownBy(() -> DatabaseInitializer.offline(connection, target,
                    JdbcMigrationSupport.contributions(declared), JdbcMigrationSupport.physical(declared)).initialize())
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("SCHEMA_MISMATCH");
            try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT id,stamp FROM facts ORDER BY id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getInt(2)).isEqualTo(10);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
                assertThat(rows.getInt(2)).isEqualTo(20);
                assertThat(rows.next()).isFalse();
            }
        }
    }
    private final MigrationSchema schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
            new ColumnSpec("id", "INTEGER", false, null, 1),
            new ColumnSpec("body", "TEXT", false, null, 0),
            new ColumnSpec("added", "TEXT", false, null, 0)),
            List.of(new IndexSpec("managed_body", IndexOrigin.CREATE_INDEX, true, List.of("body"))))),
            List.of(), List.of());

    @ParameterizedTest
    @ValueSource(strings = {"body", "body DESC", "lower(body)", "body COLLATE NOCASE", "body) WHERE id>0 --"})
    @DisplayName("保留非空附加表列索引，初始化后受管读写可用")
    void startsWithAdditions(String indexed) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("startup.db"));
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT,extra TEXT)");
            sql.execute("INSERT INTO facts VALUES(1,'keep','keep extra')");
            sql.execute("CREATE INDEX auxiliary ON facts(" + indexed + ")");
            sql.execute("CREATE TABLE absent_plugin(id INTEGER PRIMARY KEY,body TEXT)");
            sql.execute("INSERT INTO absent_plugin VALUES(2,'plugin data')");
            sql.execute("CREATE INDEX plugin_auxiliary ON absent_plugin(lower(body))");
            var expected = JdbcMigrationSupport.physical(schema);
            DatabaseInitializer.offline(connection, target, JdbcMigrationSupport.contributions(schema), expected).initialize();
            var comparison = DatabaseSchemaInspector.compare(connection, target, expected);
            assertThat(comparison.matches()).isFalse();
            assertThat(comparison.requiredStructure().matches()).isTrue();
            assertThat(DatabaseSchemaInspector.compareTables(connection, target, expected, java.util.Set.of("facts")).matches()).isFalse();
            sql.execute("INSERT INTO facts(id,body,added) VALUES(3,'new','usable')");
            try (var row = sql.executeQuery("SELECT extra FROM facts WHERE id=1")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("keep extra");
            }
            try (var row = sql.executeQuery("SELECT body FROM absent_plugin")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("plugin data");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE facts(id INTEGER PRIMARY KEY,body INTEGER)",
            "CREATE TABLE facts(id INTEGER,body TEXT)",
            "CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT NOT NULL)",
            "CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT DEFAULT 'changed')",
            "CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT,extra TEXT NOT NULL)",
            "CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT); CREATE INDEX managed_body ON facts(body)",
            "CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT); CREATE UNIQUE INDEX managed_body ON facts(lower(body))",
            "CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT); CREATE UNIQUE INDEX additional_unique ON facts(id,body)",
            "CREATE TABLE facts(old_id INTEGER PRIMARY KEY,body TEXT)"
    })
    @DisplayName("类型主键非空默认值唯一性漂移仍失败并撤回本轮补列")
    void refusesManagedMismatch(String ddl) throws Exception {
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("mismatch.db"));
        try (var connection = target.openConnection(); var sql = connection.createStatement()) {
            for (String statement : ddl.split(";")) sql.execute(statement);
            var before = target.readSchema(connection);
            assertThatThrownBy(() -> DatabaseInitializer.offline(connection, target,
                    JdbcMigrationSupport.contributions(schema), JdbcMigrationSupport.physical(schema)).initialize())
                    .isInstanceOf(RuntimeException.class);
            assertThat(target.readSchema(connection)).isEqualTo(before);
        }
    }
}
