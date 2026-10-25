package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.*;

/** 暂存核验后在旧文件的同一写事务内替换，避免文件换名与 WAL 脱离造成不确定恢复。 */
final class SqliteReplacement implements DatabaseImportSession {
    private final Connection locked;
    private final MigrationSchema schema;
    private final MigrationControl control;
    private final Path directory, stagedFile;
    private final DatabaseImportSession staged;
    private DatabaseImportSession replacement;
    private MigrationSnapshot snapshot;
    private boolean commitAttempted, closed, aborted;

    private SqliteReplacement(Connection locked, MigrationSchema schema, MigrationControl control,
                              Path directory, DatabaseImportSession staged) {
        this.locked = locked; this.schema = schema; this.control = control;
        this.directory = directory; this.stagedFile = directory.resolve("staged.db"); this.staged = staged;
    }

    static DatabaseImportSession open(DatabaseConnectionTarget target, MigrationSchema schema,
            MigrationReplacement request, MigrationControl control) throws Exception {
        control.checkpoint("", 0);
        SqliteMigration.inspect(target, schema, true);
        if (!SqliteMigration.revision(target).equals(request.revision())) throw failure("MIGRATION_TARGET_CHANGED");
        Connection locked = target.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
        Path directory = null;
        try {
            target.requireConnectionTarget(locked);
            locked.setAutoCommit(false);
            target.prepareTransaction(locked, false);
            if (!SqliteMigration.revision(target).equals(request.revision())) throw failure("MIGRATION_TARGET_CHANGED");
            SqliteMigration.backup(target, request.backup());
            directory = Files.createTempDirectory(request.backup().getParent(), ".replacement-");
            MigrationFiles.makePrivate(directory);
            var staged = SqliteMigration.importDatabase(new SqliteConnectionTarget(directory.resolve("staged.db")), schema, control);
            return new SqliteReplacement(locked, schema, control, directory, staged);
        } catch (Exception failure) {
            try { locked.rollback(); } catch (Exception e) { failure.addSuppressed(e); }
            try { locked.close(); } catch (Exception e) { failure.addSuppressed(e); }
            if (directory != null) try { cleanup(directory); } catch (Exception e) { failure.addSuppressed(e); }
            throw failure;
        }
    }

    @Override public void write(String table, MigrationRows rows) throws Exception {
        requireActive();
        staged.write(table, rows);
    }
    @Override public MigrationVerification verify(MigrationSnapshot expected) throws Exception {
        requireActive();
        var result = staged.verify(expected);
        snapshot = expected;
        return result;
    }
    @Override public void commit() throws Exception {
        requireActive();
        if (snapshot == null) throw failure("MIGRATION_NOT_VERIFIED");
        control.checkpoint("", 0);
        staged.commit();
        staged.close();
        // 旧库的触发器和 FTS 随所属表在事务中移除，不能在复制期间执行旧业务触发器。
        var triggers = new ArrayList<String>();
        var tables = new ArrayList<String>();
        try (var sql = locked.createStatement(); var rows = sql.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type='trigger'")) {
            while (rows.next()) triggers.add(rows.getString(1));
        }
        try (var sql = locked.createStatement(); var rows = sql.executeQuery("PRAGMA table_list")) {
            while (rows.next()) {
                String name = rows.getString("name"), type = rows.getString("type");
                if ("main".equals(rows.getString("schema")) && !name.startsWith("sqlite_")
                        && ("table".equals(type) || "virtual".equals(type))) tables.add(name);
            }
        }
        for (String trigger : triggers) execute(locked, "DROP TRIGGER " + quote(trigger));
        for (String table : tables) execute(locked, "DROP TABLE " + quote(table));
        replacement = SqliteMigration.importInto(locked, schema, control);
        var stagedTarget = new SqliteConnectionTarget(stagedFile);
        try (var reader = stagedTarget.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            for (var table : schema.tables()) {
                try (var rows = SqliteMigration.rows(reader, table)) { replacement.write(table.name(), rows); }
            }
        }
        replacement.verify(snapshot);
        commitAttempted = true;
        replacement.commit();
    }
    @Override public void abort() throws Exception {
        if (commitAttempted) throw failure("MIGRATION_COMMIT_OUTCOME_UNKNOWN");
        if (!closed && !aborted) {
            try { locked.rollback(); }
            finally { staged.close(); }
            aborted = true;
        }
    }
    @Override public void close() throws Exception {
        if (closed) return;
        Exception failure = null;
        try {
            if (!commitAttempted && !aborted) abort();
        } catch (Exception e) { failure = e; }
        closed = true;
        for (AutoCloseable resource : new AutoCloseable[]{staged, replacement == null ? locked : replacement}) {
            try { resource.close(); }
            catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        try { cleanup(directory); }
        catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
    private void requireActive() {
        if (closed || aborted || commitAttempted) throw failure("MIGRATION_SESSION_CLOSED");
    }
    private static void cleanup(Path directory) throws Exception {
        for (String suffix : List.of("", "-wal", "-shm", "-journal"))
            Files.deleteIfExists(directory.resolve("staged.db" + suffix));
        Files.delete(directory);
    }
}
