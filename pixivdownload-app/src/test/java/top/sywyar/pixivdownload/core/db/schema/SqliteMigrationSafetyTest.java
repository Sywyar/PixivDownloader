package top.sywyar.pixivdownload.core.db.schema;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_WRITE;

@DisplayName("SQLite 迁移的异常数据、结构和取消保护")
class SqliteMigrationSafetyTest {
    @TempDir Path directory;
    private final MigrationSchema schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
            new ColumnSpec("id", "INTEGER", false, null, 1),
            new ColumnSpec("body", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
    private MigrationRequest request(String backup) {
        return new MigrationRequest(schema, directory.resolve(backup), Set.of());
    }

    @ParameterizedTest @DisplayName("无法表达的约束拒绝迁出并释放源锁")
    @ValueSource(strings = {
            "body TEXT CHECK(length(body)>0)",
            "body TEXT COLLATE NOCASE",
            "body TEXT REFERENCES facts(id)",
            "body TEXT GENERATED ALWAYS AS (CAST(id AS TEXT)) STORED"
    })
    void rejectsUnrepresentedConstraints(String body) throws Exception {
        var endpoint = DatabaseProviderTestFixtures.sqlite(directory.resolve("source.db")).migrationEndpoint();
        try (var connection = endpoint.target().openConnection(READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY," + body + ")");
            assertThatThrownBy(() -> endpoint.provider().exportDatabase(endpoint.target(), request("backup.db"),
                    (table, count) -> {})).hasMessage("SCHEMA_MISMATCH");
            sql.execute("INSERT INTO facts(id) VALUES(1)");
        }
    }

    @Test @DisplayName("非法 UTF-8 文本不得替换字符后伪报无损，原始备份和源保留")
    void rejectsMalformedText() throws Exception {
        var endpoint = DatabaseProviderTestFixtures.sqlite(directory.resolve("source.db")).migrationEndpoint();
        try (var connection = endpoint.target().openConnection(READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT)");
            sql.execute("INSERT INTO facts VALUES(1,CAST(X'80' AS TEXT))");
            assertThatThrownBy(() -> {
                try (var exported = endpoint.provider().exportDatabase(endpoint.target(), request("backup.db"),
                        (table, count) -> {})) { exported.snapshot(); }
            }).hasMessage("TEXT_VALUE");
            try (var row = sql.executeQuery("SELECT hex(body) FROM facts")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("80");
            }
            assertThat(Files.size(directory.resolve("backup.db"))).isPositive();
            sql.execute("INSERT INTO facts VALUES(2,'after failure')");
        }
    }

    @ParameterizedTest @DisplayName("合法文本按 SQLite 实际编码验证，不假定源库为 UTF-8")
    @ValueSource(strings = {"UTF-8", "UTF-16le", "UTF-16be"})
    void acceptsSourceTextEncoding(String encoding) throws Exception {
        var endpoint = DatabaseProviderTestFixtures.sqlite(directory.resolve("source.db")).migrationEndpoint();
        try (var connection = endpoint.target().openConnection(READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("PRAGMA encoding='" + encoding + "'");
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT)");
            try (var write = connection.prepareStatement("INSERT INTO facts VALUES(1,?)")) {
                write.setString(1, "你\0好 🌸\0"); write.executeUpdate();
            }
            try (var exported = endpoint.provider().exportDatabase(endpoint.target(), request("backup.db"), (table, rows) -> {});
                 var rows = exported.rows("facts")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.values().get(1)).isEqualTo("你好 🌸");
            }
            try (var rows = sql.executeQuery("SELECT body FROM facts")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("你\0好 🌸\0");
            }
        }
    }

    @Test @DisplayName("真实迁出和迁入取消均释放资源且未提交的目标可重新准备")
    void cancellationReleasesBothSides() throws Exception {
        var source = DatabaseProviderTestFixtures.sqlite(directory.resolve("source.db")).migrationEndpoint();
        var target = DatabaseProviderTestFixtures.sqlite(directory.resolve("target.db")).migrationEndpoint();
        try (var connection = source.target().openConnection(READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT)");
            sql.execute("INSERT INTO facts VALUES(1,'source')");
            MigrationControl cancelled = (table, rows) -> {
                if (table.equals("facts")) throw new java.util.concurrent.CancellationException("MIGRATION_CANCELLED");
            };
            assertThatThrownBy(() -> source.provider().exportDatabase(source.target(), request("cancelled.db"), cancelled))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            sql.execute("INSERT INTO facts VALUES(2,'source still writable')");
            try (var exported = source.provider().exportDatabase(source.target(), request("retry.db"), (table, rows) -> {})) {
                assertThatThrownBy(() -> {
                    try (var imported = target.provider().importDatabase(target.target(), schema, cancelled);
                         var rows = exported.rows("facts")) { imported.write("facts", rows); }
                }).isInstanceOf(java.util.concurrent.CancellationException.class);
                try (var imported = target.provider().importDatabase(target.target(), schema, (table, rows) -> {});
                     var rows = exported.rows("facts")) {
                    imported.write("facts", rows);
                    assertThat(imported.verify(exported.snapshot()).tables().get("facts").rows()).isEqualTo(2);
                    imported.commit();
                }
            }
            sql.execute("INSERT INTO facts VALUES(3,'after release')");
        }
    }
}
