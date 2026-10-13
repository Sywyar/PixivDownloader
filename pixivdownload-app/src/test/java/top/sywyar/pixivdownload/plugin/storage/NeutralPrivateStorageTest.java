package top.sywyar.pixivdownload.plugin.storage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin;
import top.sywyar.pixivdownload.databasepostgresql.PostgresDatabasePlugin;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("真实提供者的中性私库存储行为")
class NeutralPrivateStorageTest {
    @TempDir Path directory;
    private static List<SchemaContribution> schema() {
        return List.of(new SchemaContribution(List.of(
                new TableSpec("works", List.of(
                        new ColumnSpec("id", "TEXT", true, null, 1),
                        new ColumnSpec("title", "TEXT", true, null, 0),
                        new ColumnSpec("time", "INTEGER", true, null, 0),
                        new ColumnSpec("optional", "TEXT", false, null, 0)), List.of()),
                new TableSpec("files", List.of(
                        new ColumnSpec("id", "TEXT", true, null, 1),
                        new ColumnSpec("page", "INTEGER", true, null, 2),
                        new ColumnSpec("bytes", "BLOB", false, null, 0)), List.of()),
                new TableSpec("identities", List.of(
                        new ColumnSpec("id", "INTEGER", true, null, 1, true),
                        new ColumnSpec("label", "TEXT", true, null, 0)), List.of())), List.of(), List.of()));
    }
    @Test @DisplayName("SQLite 声明建表、关联查询、冲突合并及事务隔离")
    void sqlite() throws Exception {
        exercise(new SqliteDatabasePlugin(), new DatabaseConnectionSettings("jdbc:sqlite:" + directory.resolve("main.db"), "", "", directory));
    }
    @Test @DisplayName("停机转换只升级工作副本，失败保留源库与备份")
    void offlineConversionPreservesSource() throws Exception {
        var provider = new SqliteDatabasePlugin();
        var target = provider.resolve(new DatabaseConnectionSettings("jdbc:sqlite:" + directory.resolve("source.db"), "", "", directory));
        var before = PrivateStorageSchema.merge(schema());
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE)) {
            target.initializeStorage(connection, before);
            try (var session = target.storageSession(connection, before)) {
                session.insert("works", work("a", "original", 1L), DatabaseSession.Conflict.error());
            }
        }
        var conversion = new StorageMigration("rename_title", s -> {
            s.update("works", Map.of("title", value("converted")), call(EQUAL, column("works", "id"), value("a")));
            return null;
        });
        var after = PrivateStorageSchema.merge(schema(), List.of(conversion));
        var request = new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationRequest(
                after, directory.resolve("backup.db"), Set.of(), List.of(conversion));
        try (var export = provider.exportDatabase(target, request, (t, n) -> {}); var rows = export.rows("works")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.values()).contains("converted");
        }
        var broken = new StorageMigration("broken", s -> { throw new SQLException("fixture failure"); });
        assertThatThrownBy(() -> provider.exportDatabase(target,
                new top.sywyar.pixivdownload.plugin.api.database.migration.MigrationRequest(
                        after, directory.resolve("failed-backup.db"), Set.of(), List.of(conversion, broken)), (t, n) -> {}))
                .isInstanceOf(SQLException.class);
        for (var file : List.of("source.db", "backup.db", "failed-backup.db")) {
            var preserved = provider.resolve(new DatabaseConnectionSettings("jdbc:sqlite:" + directory.resolve(file), "", "", directory));
            try (var connection = preserved.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
                 var session = preserved.storageSession(connection, before)) {
                assertThat(session.query(new Query("works", Map.of("title", column("works", "title")),
                        null, List.of(), List.of(), 1, 0)).get(0).get("title")).isEqualTo("original");
            }
        }
    }
    @Test @DisplayName("PostgreSQL 声明建表、关联查询、冲突合并及事务隔离")
    void postgres() throws Exception {
        String url = System.getenv("PIXIV_TEST_POSTGRES_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "需要隔离 PostgreSQL 服务");
        String base = url.split("\\?", 2)[0];
        String namespace = "neutral_" + UUID.randomUUID().toString().replace("-", "");
        var properties = new Properties();
        properties.setProperty("user", System.getenv("PIXIV_TEST_POSTGRES_USER"));
        properties.setProperty("password", System.getenv().getOrDefault("PIXIV_TEST_POSTGRES_PASSWORD", ""));
        try (var admin = new org.postgresql.Driver().connect(base, properties); var statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + namespace);
            try {
                exercise(new PostgresDatabasePlugin(), new DatabaseConnectionSettings(base + "?currentSchema=" + namespace,
                        properties.getProperty("user"), properties.getProperty("password"), directory.resolve("unused.db")));
            } finally { statement.execute("DROP SCHEMA " + namespace + " CASCADE"); }
        }
    }
    private void verifyRelationalQueries(AppPluginDatabase db) throws Exception {
        long explicit = 9_007_199_254_740_993L;
        db.transaction(s -> s.insert("identities", Map.of("id", explicit, "label", "retained"), DatabaseSession.Conflict.error()));
        long generated = db.transaction(s -> s.insertGenerated("identities", Map.of("label", "generated"), "id"));
        assertThat(generated).isGreaterThan(explicit);
        db.transaction(s -> s.delete("identities", call(EQUAL, column("identities", "id"), value(generated))));
        long afterDelete = db.transaction(s -> s.insertGenerated("identities", Map.of("label", "after-delete"), "id"));
        assertThat(afterDelete).isGreaterThan(generated);
        var scanned = new ArrayList<Long>();
        var scan = new Query("identities", Map.of("id", column("identities", "id")), null, List.of(),
                List.of(new Query.Order(column("identities", "id"), false, true)), Integer.MAX_VALUE, 0);
        db.read(s -> { s.scan(scan, row -> { scanned.add((Long) row.get("id")); return false; }); return null; });
        assertThat(scanned).containsExactly(explicit);
        assertThatThrownBy(() -> db.transaction(s -> {
            s.insert("identities", Map.of("label", "scan-rollback"), DatabaseSession.Conflict.error());
            s.scan(scan, row -> { throw new SQLException("fixture callback failed"); });
            return null;
        })).isInstanceOf(SQLException.class);
        long retained = db.read(s -> (Long) s.query(Query.count("identities", null)).get(0).get("count"));
        assertThat(retained).isEqualTo(2);
        assertThatThrownBy(() -> db.transaction(s -> {
            s.insert("identities", Map.of("label", "caught-scan-error"), DatabaseSession.Conflict.error());
            try { s.scan(scan, row -> { throw new AssertionError("fixture"); }); }
            catch (AssertionError expected) { /* 捕获回调错误不能恢复事务。 */ }
            return null;
        })).isInstanceOf(SQLException.class);
        long afterCaughtError = db.read(s -> (Long) s.query(Query.count("identities", null)).get(0).get("count"));
        assertThat(afterCaughtError).isEqualTo(2);
        var matched = call(AND, call(EQUAL, column("identities", "id"), value(explicit)),
                call(EQUAL, column("identities", "label"), value("retained")));
        var claimed = db.transaction(s -> s.updateReturning("identities", Map.of("label", value("claimed")),
                matched, List.of("id", "label")));
        assertThat(claimed).containsExactly(Map.of("id", explicit, "label", "claimed"));
        var stale = db.transaction(s -> s.updateReturning("identities", Map.of("label", value("stale")),
                matched, List.of("id")));
        assertThat(stale).isEmpty();
        assertThatThrownBy(() -> db.read(s -> s.updateReturning("identities", Map.of("label", value("readonly")),
                matched, List.of("id")))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> db.transaction(s -> s.insert("identities",
                Map.of("id", explicit, "label", "fixture-private-detail"), DatabaseSession.Conflict.error())))
                .isInstanceOf(SQLException.class).hasNoCause().hasMessageNotContaining("fixture-private-detail")
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23505"));
        var id = column("w", "id");
        var joined = new Query(new Query.Table("works", "w"),
                List.of(new Query.Join(Query.JoinKind.LEFT, new Query.Table("files", "f"),
                        call(EQUAL, id, column("f", "id")))),
                Map.of("id", id, "pages", call(COUNT_DISTINCT, column("f", "page"))), null,
                List.of(id), call(GREATER_EQUAL, call(COUNT), value(1)), List.of(new Query.Order(id, false, false)), false, 10, 0);
        var joinedRows = db.read(s -> s.query(joined));
        assertThat(joinedRows).containsExactly(
                Map.of("id", "a", "pages", 1L), Map.of("id", "b", "pages", 0L));
        var branch = new Query("works", Map.of("n", column("works", "time")), null, List.of(), List.of(), 10, 0);
        var union = new Query(new Query.Union(List.of(branch, branch), true, "u"), List.of(),
                Map.of("sum", call(SUM, column("u", "n"))), null, List.of(), null, List.of(), false, 1, 0);
        assertThat(db.read(s -> s.query(union)).get(0).get("sum")).isEqualTo(22L);
        var derived = new Query(new Query.Derived(branch, "d"), List.of(),
                Map.of("label", call(CONCAT, value("v"), call(TEXT, column("d", "n")))),
                call(EQUAL, column("d", "n"), value(2)), List.of(), null, List.of(), false, 1, 0);
        assertThat(db.read(s -> s.query(derived)).get(0).get("label")).isEqualTo("v2");
        var scalar = new Scalar(new Query("files", Map.of("count", call(COUNT)),
                call(EQUAL, column("files", "id"), column("works", "id")), List.of(), List.of(), 1, 0));
        var conditional = new Conditional(call(GREATER, scalar, value(0)), value("has"), value("none"));
        var selected = new Query("works", Map.of("result", conditional), call(EQUAL, column("works", "id"), value("a")),
                List.of(), List.of(), 1, 0);
        assertThat(db.read(s -> s.query(selected)).get(0).get("result")).isEqualTo("has");
        db.transaction(s -> s.update("works", Map.of("time", call(ADD, column("works", "time"), value(1))),
                call(EQUAL, column("works", "id"), value("b"))));
        assertThatThrownBy(() -> db.transaction(s -> s.update("works",
                Map.of("time", call(ADD, value(Long.MAX_VALUE), value(1))), call(EQUAL, column("works", "id"), value("a")))))
                .isInstanceOf(SQLException.class);
        db.transaction(s -> s.update("works", Map.of("time", value(2)), call(EQUAL, column("works", "id"), value("b"))));
    }
    private void exercise(DatabaseProvider provider, DatabaseConnectionSettings settings) throws Exception {
        var selected = new DatabaseProviderSelection.Result("neutral-provider", provider.databaseId(), Optional.of(provider),
                DatabaseProviderSelection.Failure.NONE);
        var target = DatabaseTarget.inspect(selected, settings);
        String installation = UUID.randomUUID().toString();
        List<String> privateSchemas = new ArrayList<>();
        try (var main = target.openPool(2)) {
            try {
                try (var db = new AppPluginDatabase("fixture-owner", target, main,
                        () -> new DatabasePrivateStorageSettings("fixture-owner", directory.resolve("one"), installation, "fixture-pass"));
                     var other = new AppPluginDatabase("other-owner", target, main,
                        () -> new DatabasePrivateStorageSettings("other-owner", directory.resolve("two"), installation, "fixture-pass"))) {
                    db.initialize(schema()); other.initialize(schema());
                    if (provider instanceof PostgresDatabasePlugin) {
                        try (var a = db.getConnection(); var o = other.getConnection()) {
                            privateSchemas.add(a.getSchema()); privateSchemas.add(o.getSchema());
                        }
                    }
                    var escaped = new AtomicReference<DatabaseSession>();
                    db.transaction(session -> {
                        escaped.set(session);
                        assertThat(session.insert("works", work("a\0", "A\0中文\0", Long.MAX_VALUE), DatabaseSession.Conflict.error())).isEqualTo(1);
                        session.insert("works", work("b", "beta", 2L), DatabaseSession.Conflict.error());
                        session.insert("files", Map.of("id", "a", "page", 0L, "bytes", new byte[]{0, -1, 42}), DatabaseSession.Conflict.error());
                        assertThat(session.insert("works", work("a", "ignored", 3L), DatabaseSession.Conflict.ignore("id"))).isZero();
                        session.insert("works", work("a", "updated", 9L),
                                new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("id"), Map.of(
                                    "title", call(COALESCE, new Incoming("optional"), column("works", "title")),
                                    "time", call(MINIMUM, column("works", "time"), new Incoming("time")))));
                        return null;
                    });
                    assertThatThrownBy(() -> escaped.get().query(Query.count("works", null))).isInstanceOf(SQLException.class);
                    var normalized = db.transaction(s -> s.updateReturning("works", Map.of("title", value("A\0中文")),
                            call(EQUAL, column("works", "id"), value("a\0")), List.of("title")));
                    assertThat(normalized).containsExactly(Map.of("title", "A中文"));
                    db.transaction(s -> s.insert("works", work("a", "ignored", 9L),
                            new DatabaseSession.Conflict(DatabaseSession.Mode.UPDATE, List.of("id"),
                                    Map.of("title", value("A中\0文")))));
                    verifyRelationalQueries(db);
                    long otherCount = other.read(s -> (Long) s.query(Query.count("works", null)).get(0).get("count"));
                    assertThat(otherCount).isZero();
                    var exists = new Exists(new Query("files", Map.of("id", column("files", "id")),
                            call(EQUAL, column("files", "id"), column("works", "id")), List.of(), List.of(), 1, 0));
                    var query = new Query("works", Map.of("title", column("works", "title"), "time", column("works", "time")),
                            call(AND, exists, call(LIKE, column("works", "title"), value("a%"))),
                            List.of(), List.of(new Query.Order(column("works", "time"), true, false)), 10, 0);
                    var found = db.read(s -> s.query(query));
                    assertThat(found).containsExactly(Map.of("title", "A中文", "time", 9L));
                    var grouped = new Query("works", Map.of("title", column("works", "title"), "count", call(COUNT)),
                            null, List.of(column("works", "title")), List.of(), 10, 0);
                    assertThat(db.read(s -> s.query(grouped)).size()).isEqualTo(2);
                    assertThatThrownBy(() -> db.read(s -> s.query(new Query("works",
                            Map.of("title", column("works", "title"), "count", call(COUNT)), null, List.of(), List.of(), 10, 0))))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> db.transaction(s -> {
                        s.insert("works", work("rolled-back", "x", 3L), DatabaseSession.Conflict.error());
                        throw new SQLException("fixture");
                    })).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> db.transaction(s -> {
                        s.insert("works", work("also-rolled-back", "x", 3L), DatabaseSession.Conflict.error());
                        try { s.insert("works", work("invalid", "x", "lossy"), DatabaseSession.Conflict.error()); }
                        catch (RuntimeException expected) { }
                        return null;
                    })).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> db.read(s -> s.delete("works", call(EQUAL, column("works", "id"), value("a")))))
                            .isInstanceOf(SQLException.class);
                    long retained = db.read(s -> (Long) s.query(Query.count("works", null)).get(0).get("count"));
                    assertThat(retained).isEqualTo(2L);
                    var evolved = new ArrayList<>(schema().get(0).tables());
                    var original = evolved.get(0);
                    var columns = new ArrayList<>(original.columns());
                    columns.add(new ColumnSpec("revision", "INTEGER", true, "0", 0));
                    evolved.set(0, new TableSpec(original.name(), columns, original.indexes()));
                    db.initialize(List.of(new SchemaContribution(evolved, List.of(), List.of())));
                    var revisions = db.read(s -> s.query(new Query("works", Map.of("revision", column("works", "revision")),
                            null, List.of(), List.of(), 10, 0)));
                    assertThat(revisions).allSatisfy(row -> assertThat(row.get("revision")).isEqualTo(0L));
                    var conversions = new java.util.concurrent.atomic.AtomicInteger();
                    var declared = List.of(new SchemaContribution(evolved, List.of(), List.of()));
                    var upgrade = new StorageMigration("set_revision", s -> {
                        conversions.incrementAndGet();
                        s.update("works", Map.of("revision", value(1L)), call(NOT, call(IS_NULL, column("works", "id"))));
                        return null;
                    });
                    db.initialize(declared, List.of(upgrade));
                    db.initialize(declared, List.of(upgrade));
                    assertThat(conversions).hasValue(1);
                    var broken = new StorageMigration("broken_conversion", s -> {
                        s.update("works", Map.of("title", value("must roll back")), call(EQUAL, column("works", "id"), value("a")));
                        throw new SQLException("fixture conversion failure");
                    });
                    assertThatThrownBy(() -> db.initialize(declared, List.of(upgrade, broken))).isInstanceOf(SQLException.class);
                    try (var check = target.privateStorage(new DatabasePrivateStorageSettings("fixture-owner",
                            directory.resolve("one"), installation, "fixture-pass")).openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
                         var statement = check.createStatement(); var rows = statement.executeQuery("SELECT title FROM works WHERE id='a'")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("A中文");
                    }
                    target.invalidate();
                    assertThatThrownBy(() -> db.transaction(s -> { throw new AssertionError("must not enter"); }))
                            .isInstanceOf(SQLException.class);
                }
            } finally {
                if (provider instanceof PostgresDatabasePlugin) {
                    // Only the fixture-owned private namespaces and roles are removed.
                    try (var c = provider.resolve(settings).openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
                         var statement = c.createStatement()) {
                        for (String name : privateSchemas) {
                            assertThat(name).matches("[a-z0-9_]+");
                            statement.execute("DROP SCHEMA " + name + " CASCADE");
                            statement.execute("DROP ROLE " + name);
                        }
                    }
                }
            }
        }
    }
    private static Map<String, Object> work(String id, String title, Object time) {
        var values = new LinkedHashMap<String, Object>();
        values.put("id", id); values.put("title", title); values.put("time", time); values.put("optional", null);
        return values;
    }
}
