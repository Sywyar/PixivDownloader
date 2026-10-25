package top.sywyar.pixivdownload.core.db.schema;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.migration.database.MigrationEndpoint;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.*;

@DisplayName("SQLite 非空迁入目标的一致备份与事务替换")
class SqliteReplacementTest {
    @TempDir Path directory;
    private final MigrationControl control = (table, rows) -> {};
    private final MigrationSchema schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
            new ColumnSpec("id", "INTEGER", false, null, 1),
            new ColumnSpec("body", "TEXT", false, null, 0)), List.of())), List.of(), List.of());
    private MigrationEndpoint source, target;
    @BeforeEach void initialize() throws Exception {
        source = create("source.db", "new 🌸");
        target = create("target.db", "old\0retained");
    }
    private MigrationEndpoint create(String file, String body) throws Exception {
        var endpoint = DatabaseProviderTestFixtures.sqlite(directory.resolve(file)).migrationEndpoint();
        try (var connection = endpoint.target().openConnection(READ_WRITE); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,body TEXT)");
            try (var insert = connection.prepareStatement("INSERT INTO facts VALUES(1,?)")) {
                insert.setString(1, body); insert.executeUpdate();
            }
        }
        return endpoint;
    }
    private String body(MigrationEndpoint endpoint) throws Exception {
        try (var connection = endpoint.target().openConnection(READ_ONLY); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT body FROM facts")) {
            assertThat(rows.next()).isTrue(); return rows.getString(1);
        }
    }
    private MigrationReplacement replacement() throws Exception {
        return new MigrationReplacement(target.provider().inspectReplacement(target.target(), schema), directory.resolve("old.backup"));
    }
    private DatabaseExportSession export() throws Exception {
        return source.provider().exportDatabase(source.target(),
                new MigrationRequest(schema, directory.resolve("source.backup"), Set.of()), control);
    }
    private void copy(DatabaseExportSession source, DatabaseImportSession destination) throws Exception {
        try (var rows = source.rows("facts")) { destination.write("facts", rows); }
        destination.verify(source.snapshot());
    }
    @Test @DisplayName("预检只读，暂存核验不改变旧库，提交后原路径更新且备份保留原文与辅助对象")
    void retainsOldDatabaseUntilVerifiedCommit() throws Exception {
        try (var connection = target.target().openConnection(EXISTING); var sql = connection.createStatement()) {
            sql.execute("CREATE VIRTUAL TABLE facts_fts USING fts5(body)");
            sql.execute("INSERT INTO facts_fts VALUES('searchable')");
            sql.execute("CREATE TRIGGER old_cleanup AFTER UPDATE ON facts BEGIN DELETE FROM facts_fts; END");
        }
        var request = replacement();
        assertThat(request.backup()).doesNotExist();
        assertThat(body(target)).isEqualTo("old\0retained");
        try (var exported = export();
             var imported = target.provider().replaceDatabase(target.target(), schema, request, control)) {
            copy(exported, imported);
            assertThat(body(target)).isEqualTo("old\0retained");
            imported.commit();
            assertThatThrownBy(imported::abort).hasMessage("MIGRATION_COMMIT_OUTCOME_UNKNOWN");
            assertThatThrownBy(imported::commit).hasMessage("MIGRATION_SESSION_CLOSED");
        }
        assertThat(body(target)).isEqualTo("new 🌸");
        assertThat(body(DatabaseProviderTestFixtures.sqlite(request.backup()).migrationEndpoint())).isEqualTo("old\0retained");
        try (var connection = DatabaseProviderTestFixtures.openExisting(request.backup()); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name IN ('facts_fts','old_cleanup')")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2);
        }
    }
    @Test @DisplayName("确认后目标变化时拒绝，不生成备份或覆盖新内容")
    void rejectsChangedTarget() throws Exception {
        var request = replacement();
        try (var connection = target.target().openConnection(EXISTING); var sql = connection.createStatement()) {
            sql.execute("UPDATE facts SET body='changed after preview'");
        }
        assertThatThrownBy(() -> target.provider().replaceDatabase(target.target(), schema, request, control))
                .hasMessage("MIGRATION_TARGET_CHANGED");
        assertThat(request.backup()).doesNotExist();
        assertThat(body(target)).isEqualTo("changed after preview");
    }
    @Test @DisplayName("未经确认的普通迁入继续拒绝非空目标")
    void ordinaryImportStillRejectsNonemptyTarget() {
        assertThatThrownBy(() -> target.provider().importDatabase(target.target(), schema, control)).hasMessage("TARGET_NOT_EMPTY");
    }
    @Test @DisplayName("未知旧表不能通过替换绕过结构保护")
    void rejectsUnknownTargetObjects() throws Exception {
        try (var connection = target.target().openConnection(EXISTING); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE unknown_history(id INTEGER PRIMARY KEY)");
        }
        assertThatThrownBy(this::replacement).hasMessage("UNDECLARED_TABLE");
        assertThat(body(target)).isEqualTo("old\0retained");
    }
    @Test @DisplayName("替换提交阶段取消时回滚旧库结构与内容，备份仍可读取")
    void failedReplacementCommitRestoresOldContent() throws Exception {
        var request = replacement();
        var cancel = new java.util.concurrent.atomic.AtomicBoolean();
        MigrationControl cancelling = (table, rows) -> {
            if (cancel.get() && table.equals("facts")) throw new java.util.concurrent.CancellationException();
        };
        try (var exported = export();
             var imported = target.provider().replaceDatabase(target.target(), schema, request, cancelling)) {
            copy(exported, imported);
            cancel.set(true);
            assertThatThrownBy(imported::commit).isInstanceOf(java.util.concurrent.CancellationException.class);
        }
        assertThat(body(target)).isEqualTo("old\0retained");
        assertThat(body(DatabaseProviderTestFixtures.sqlite(request.backup()).migrationEndpoint())).isEqualTo("old\0retained");
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).noneMatch(name -> name.startsWith(".replacement-"));
        }
    }
    @Test @DisplayName("未核验不能提交，关闭后旧库和备份均保留")
    void closingUnverifiedImportKeepsTarget() throws Exception {
        var request = replacement();
        try (var imported = target.provider().replaceDatabase(target.target(), schema, request, control)) {
            assertThatThrownBy(imported::commit).hasMessage("MIGRATION_NOT_VERIFIED");
        }
        assertThat(body(target)).isEqualTo("old\0retained");
        assertThat(request.backup()).isRegularFile();
    }
    @Test @DisplayName("旧目标备份包含已提交 WAL，迁入期间其它写者被阻止，关闭后释放")
    void backsUpCommittedWalAndHoldsWriteLock() throws Exception {
        try (var writer = target.target().openConnection(READ_WRITE); var sql = writer.createStatement()) {
            sql.execute("UPDATE facts SET body='committed WAL'");
            sql.execute("PRAGMA busy_timeout=1");
            var request = replacement();
            try (var imported = target.provider().replaceDatabase(target.target(), schema, request, control)) {
                assertThat(body(DatabaseProviderTestFixtures.sqlite(request.backup()).migrationEndpoint())).isEqualTo("committed WAL");
                assertThatThrownBy(() -> sql.execute("UPDATE facts SET body='concurrent writer'")).isInstanceOf(java.sql.SQLException.class);
            }
            sql.execute("UPDATE facts SET body='writer released'");
            assertThat(body(target)).isEqualTo("writer released");
        }
    }
    @Test @DisplayName("已有备份绝不覆盖，准备失败也释放旧目标锁")
    void preservesExistingBackup() throws Exception {
        var request = replacement();
        Files.writeString(request.backup(), "protected backup");
        assertThatThrownBy(() -> target.provider().replaceDatabase(target.target(), schema, request, control))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(request.backup()).hasContent("protected backup");
        assertThat(body(target)).isEqualTo("old\0retained");
        try (var connection = target.target().openConnection(EXISTING); var sql = connection.createStatement()) {
            sql.execute("UPDATE facts SET body='writer released'");
        }
    }
}
