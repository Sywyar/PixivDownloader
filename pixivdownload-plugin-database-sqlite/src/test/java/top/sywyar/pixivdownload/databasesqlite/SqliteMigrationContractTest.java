package top.sywyar.pixivdownload.databasesqlite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;

import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_WRITE;

@DisplayName("SQLite 迁移结构保真与跨编码行序")
class SqliteMigrationContractTest {
    @TempDir Path directory;

    @org.junit.jupiter.api.AfterEach
    void cleanDirectoryAfterResourcesClose() throws java.io.IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
    }
    private final SqliteDatabasePlugin provider = new SqliteDatabasePlugin();
    private final MigrationControl control = (table, count) -> {};

    private DatabaseConnectionTarget target(String name) throws Exception {
        return provider.resolve(new DatabaseConnectionSettings("jdbc:sqlite:" + directory.resolve(name), "", "", directory));
    }

    @ParameterizedTest @DisplayName("合同不能表达的索引在预检和迁出时拒绝，源数据及写锁可恢复")
    @ValueSource(strings = {"body COLLATE NOCASE", "body COLLATE RTRIM", "body DESC", "lower(body)", "body) WHERE id>0 --"})
    void rejectsUnrepresentedIndex(String indexed) throws Exception {
        var source = target("source.db");
        var schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", false, null, 1),
                new ColumnSpec("body", "TEXT", false, null, 0)),
                List.of(new IndexSpec("body_u", IndexOrigin.CREATE_INDEX, true, List.of("body"))))), List.of(), List.of());
        try (var connection = source.openConnection(READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT)");
            sql.execute("CREATE UNIQUE INDEX body_u ON facts(" + indexed + ")");
            sql.execute("INSERT INTO facts VALUES(1,'A')");
            assertThatThrownBy(() -> provider.inspectMigration(source, schema, true)).hasMessage("SCHEMA_MISMATCH");
            assertThatThrownBy(() -> provider.exportDatabase(source,
                    new MigrationRequest(schema, directory.resolve("backup.db"), Set.of()), control)).hasMessage("SCHEMA_MISMATCH");
            try (var row = sql.executeQuery("SELECT body FROM facts")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("A");
            }
            sql.execute("INSERT INTO facts VALUES(2,'still writable')");
            assertThat(directory.resolve("backup.db")).doesNotExist();
        }
    }

    @ParameterizedTest @DisplayName("未声明的非默认冲突策略不能当作普通非空约束迁出")
    @ValueSource(strings = {"ON CONFLICT IGNORE", "ON CONFLICT REPLACE", "ON CONFLICT FAIL", "ON CONFLICT ROLLBACK",
            "ON CONFLICT /* policy */ IGNORE", "ON /* policy */ CONFLICT IGNORE", "ON -- policy\n CONFLICT IGNORE"})
    void rejectsUnrepresentedConflictPolicy(String policy) throws Exception {
        var source = target("source.db");
        var schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", false, null, 1),
                new ColumnSpec("body", "TEXT", true, null, 0)), List.of())), List.of(), List.of());
        try (var connection = source.openConnection(READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT NOT NULL " + policy + ")");
            sql.execute("INSERT INTO facts VALUES(1,'keep')");
            assertThatThrownBy(() -> provider.inspectMigration(source, schema, true)).hasMessage("SCHEMA_MISMATCH");
            assertThatThrownBy(() -> provider.exportDatabase(source,
                    new MigrationRequest(schema, directory.resolve("backup.db"), Set.of()), control)).hasMessage("SCHEMA_MISMATCH");
            sql.execute("INSERT INTO facts VALUES(2,'still writable')");
        }
    }

    @ParameterizedTest @DisplayName("非 ASCII 与补充平面文本主键跨编码迁移和回读摘要一致")
    @ValueSource(strings = {"UTF-8", "UTF-16le", "UTF-16be"})
    void migratesTextKeysAcrossEncodings(String encoding) throws Exception {
        var source = target("source.db");
        var destination = target("target.db");
        var schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "TEXT", true, null, 1),
                new ColumnSpec("body", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
        var keys = List.of("A", "\u0100", "\uE000", "\uD83C\uDF38");
        try (var connection = source.openConnection(READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("PRAGMA encoding='" + encoding + "'");
            sql.execute("CREATE TABLE facts(id TEXT NOT NULL PRIMARY KEY,body TEXT)");
            try (var write = connection.prepareStatement("INSERT INTO facts VALUES(?,?)")) {
                for (String key : keys) { write.setString(1, key); write.setString(2, "正文 " + key); write.executeUpdate(); }
            }
        }
        MigrationSnapshot snapshot;
        var backup = directory.resolve("backup.db");
        try (var exported = provider.exportDatabase(source, new MigrationRequest(schema, backup, Set.of()), control)) {
            snapshot = exported.snapshot();
            try (var rows = exported.rows("facts")) {
                var ordered = new ArrayList<Object>();
                while (rows.next()) ordered.add(rows.values().get(0));
                assertThat(ordered).containsExactlyElementsOf(keys);
            }
            try (var imported = provider.importDatabase(destination, schema, control); var rows = exported.rows("facts")) {
                imported.write("facts", rows);
                assertThat(imported.verify(snapshot)).isEqualTo(snapshot.verification());
                imported.commit();
            }
        }
        provider.verifyImported(destination, snapshot, control);
        for (var preserved : List.of(source, target("backup.db"))) {
            try (var connection = preserved.openConnection(READ_WRITE); var sql = connection.createStatement()) {
                try (var rows = sql.executeQuery("PRAGMA encoding")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualToIgnoringCase(encoding);
                }
                try (var rows = sql.executeQuery("SELECT count(*) FROM facts")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(4);
                }
            }
        }
        try (var connection = destination.openConnection(READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("UPDATE facts SET body='changed' WHERE id='A'");
        }
        assertThatThrownBy(() -> provider.verifyImported(destination, snapshot, control)).hasMessage("DATA_MISMATCH");
    }
}
