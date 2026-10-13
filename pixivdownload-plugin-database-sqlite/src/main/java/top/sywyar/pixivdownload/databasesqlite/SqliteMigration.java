package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.*;

/**
 * SQLite 独占源锁、一致备份、工作副本和目标事务；不认识其它数据库。
 */
final class SqliteMigration {
    private SqliteMigration() {
    }

    static MigrationRows rows(Connection connection, TableSpec table) throws SQLException {
        if (table.columns().stream().anyMatch(c -> c.primaryKeyPosition() > 0 && c.type().equals("TEXT"))) {
            org.sqlite.Function.create(connection.unwrap(org.sqlite.SQLiteConnection.class), "migration_text_key", new org.sqlite.Function() {
                @Override protected void xFunc() throws SQLException {
                    if (value_type(0) == 5) { result(); return; }
                    if (value_type(0) != 3) { error("TEXT_VALUE"); return; }
                    String text = (String) MigrationValues.canonical(value_text(0), "TEXT");
                    result(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
        }
        return JdbcMigrationSupport.rows(connection, table, c -> c.type().equals("TEXT")
                ? "migration_text_key(" + quote(c.name()) + ")" : quote(c.name()));
    }

    static DatabaseMigrationProvider.Inspection inspect(DatabaseConnectionTarget target, MigrationSchema schema,
                                                        boolean source) throws Exception {
        schema.tables().forEach(SqliteMigrationSchema::renderCheck);
        Path file = target.localFile().orElseThrow();
        MigrationFiles.requirePlain(file, false);
        if (!Files.exists(file)) {
            if (source) return new DatabaseMigrationProvider.Inspection(true, Set.of());
            Path parent = file.toAbsolutePath().getParent();
            while (parent != null && !Files.exists(parent)) parent = parent.getParent();
            if (parent == null || !Files.isWritable(parent)) throw failure("TARGET_NOT_WRITABLE");
            return new DatabaseMigrationProvider.Inspection(true, Set.of());
        }
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            target.requireConnectionTarget(connection);
            Set<String> rebuild = new TreeSet<>();
            boolean empty = true;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT name,type,sql FROM sqlite_schema WHERE substr(name,1,7)<>'sqlite_'")) {
                while (rows.next()) {
                    empty = false;
                    String ddl = rows.getString(3);
                    if (source && ("trigger".equals(rows.getString(2)) || "table".equals(rows.getString(2))
                            && ddl != null && ddl.toLowerCase(Locale.ROOT).contains("using fts5(")))
                        rebuild.add(rows.getString(1));
                }
            }
            if (source) {
                validateObjects(connection, schema, rebuild);
                SqliteMigrationSchema.validateConstraints(connection, schema);
                var expected = physical(schema);
                for (var actual : SqliteMigrationSchema.readActualTables(connection).values()) {
                    var declared = expected.tables().get(actual.name());
                    if (declared == null || actual.columns().stream().anyMatch(c -> !declared.columns().contains(c))
                            || actual.indexes().stream().anyMatch(i -> !declared.indexes().contains(i)))
                        throw failure("SCHEMA_MISMATCH");
                    for (var column : declared.columns())
                        if (actual.columns().stream().noneMatch(c -> c.name().equals(column.name()))
                                && !SqliteMigrationSchema.canAddColumn(column)) throw failure("SCHEMA_MISMATCH");
                }
            } else if (!Files.isWritable(file)) throw failure("TARGET_NOT_WRITABLE");
            return new DatabaseMigrationProvider.Inspection(empty, rebuild);
        }
    }

    static void verifyImported(DatabaseConnectionTarget target, MigrationSnapshot snapshot,
                               MigrationControl control) throws Exception {
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            target.requireConnectionTarget(connection);
            connection.setAutoCommit(false);
            validateObjects(connection, snapshot.schema(), Set.of());
            requireSchema(physical(snapshot.schema()), SqliteMigrationSchema.readActualTables(connection));
            SqliteMigrationSchema.validateConstraints(connection, snapshot.schema());
            integrity(connection);
            var actual = new MigrationVerification(digests(snapshot.schema(), control, table -> SqliteMigration.rows(connection, table)),
                    sequences(connection, snapshot.schema()), references(connection, snapshot.schema(), control));
            if (!actual.equals(snapshot.verification())) throw failure("DATA_MISMATCH");
        }
    }

    static Set<String> owners(Path root) throws Exception {
        Set<String> owners = new TreeSet<>();
        MigrationFiles.requirePlain(root, true);
        if (!Files.exists(root)) return Set.of();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String owner = entry.getFileName().toString();
                if (!owner.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")) continue;
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                MigrationFiles.requirePlain(entry, true);
                Path file = entry.resolve("plugin.db");
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    MigrationFiles.requirePlain(file, false);
                    owners.add(owner);
                }
            }
        }
        return Set.copyOf(owners);
    }

    static String revision(DatabaseConnectionTarget target) throws Exception {
        Path file = target.localFile().orElseThrow(() -> failure("SOURCE_TARGET_INVALID"));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw failure("SOURCE_MISSING");
        return "sqlite-file-v1:" + MigrationFiles.digest(file) + ":" + MigrationFiles.digest(Path.of(file + "-wal"));
    }

    static DatabaseExportSession exportDatabase(DatabaseConnectionTarget target, MigrationRequest request,
                                                MigrationControl control) throws Exception {
        Connection locked = null, workingConnection = null;
        Path workingDirectory = null;
        boolean sourceLocked = false;
        try {
            control.checkpoint("", 0);
            Path source = target.localFile().orElseThrow().toRealPath();
            MigrationFiles.requirePlain(source, false);
            if (Files.exists(request.backup()) || source.equals(request.backup())) throw failure("BACKUP_EXISTS");
            locked = target.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
            target.requireConnectionTarget(locked);
            // 只 checkpoint 已提交 WAL，不修改业务内容；避免最后连接关闭改变变化凭据。
            try (var statement = locked.createStatement(); var checkpoint = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                if (!checkpoint.next() || checkpoint.getInt(1) != 0) throw failure("SOURCE_BUSY");
            }
            execute(locked, "BEGIN IMMEDIATE");
            sourceLocked = true;
            validateObjects(locked, request.schema(), request.rebuildObjects());
            SqliteMigrationSchema.validateConstraints(locked, request.schema());
            var historic = sequences(locked, request.schema());
            backup(target, request.backup());
            workingDirectory = Files.createTempDirectory(request.backup().getParent(), ".migration-");
            MigrationFiles.makePrivate(workingDirectory);
            Path workingFile = workingDirectory.resolve("working.db");
            Files.copy(request.backup(), workingFile);
            var workingTarget = new SqliteConnectionTarget(workingFile);
            workingConnection = workingTarget.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
            integrity(workingConnection);
            applySchema(workingConnection, request.schema(), false);
            validateText(workingConnection, request.schema(), control);
            seed(workingConnection, request.schema());
            workingConnection.setAutoCommit(false);
            try (var session = workingTarget.storageSession(workingConnection, request.schema())) {
                normalizeText(workingConnection, request.schema(), control);
                top.sywyar.pixivdownload.plugin.api.storage.relational.StorageMigration.apply(session, request.storageMigrations());
                workingConnection.commit();
            } finally { workingConnection.rollback(); workingConnection.setAutoCommit(true); }
            var highWater = sequences(workingConnection, request.schema());
            historic.forEach((name, value) -> highWater.merge(name, value, Math::max));
            Connection snapshotConnection = workingConnection;
            var verified = new MigrationVerification(digests(request.schema(), control, table -> rows(snapshotConnection, table)), highWater,
                    references(workingConnection, request.schema(), control));
            var snapshot = new MigrationSnapshot(MigrationSnapshot.FORMAT_VERSION, request.schema(), verified,
                    request.backup(), revision(target), request.rebuildObjects().stream().sorted().toList());
            return new Export(locked, workingConnection, workingDirectory, snapshot);
        } catch (Exception failure) {
            try {
                cleanup(locked, sourceLocked, workingConnection, workingDirectory);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** 调用方持有原库写锁；独立读连接保留已提交 WAL 的一致内容。 */
    static void backup(DatabaseConnectionTarget target, Path file) throws Exception {
        MigrationFiles.createPrivateFile(file);
        try (var reader = target.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
             var copy = reader.prepareStatement("VACUUM INTO ?")) {
            String output = file.toString();
            if (java.io.File.separatorChar == '\\')
                output = output.startsWith("\\\\") ? "\\\\?\\UNC\\" + output.substring(2) : "\\\\?\\" + output;
            copy.setString(1, output);
            copy.execute();
        }
        try (var backup = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) { backup.force(true); }
    }

    /** 仅转换可丢弃工作副本，之后按真实落盘值计算摘要；源库与原始备份不改写。 */
    private static void normalizeText(Connection connection, MigrationSchema schema, MigrationControl control) throws Exception {
        var sqlite = connection.unwrap(org.sqlite.SQLiteConnection.class);
        org.sqlite.Function.create(sqlite, "migration_normalize_text", new org.sqlite.Function() {
            @Override protected void xFunc() throws SQLException {
                if (value_type(0) == 5) { result(); return; }
                if (value_type(0) != 3) { error("TEXT_VALUE"); return; }
                result((String) top.sywyar.pixivdownload.plugin.api.storage.relational.StorageValues.normalize(value_text(0)));
            }
        }, 1, org.sqlite.Function.FLAG_DETERMINISTIC);
        try {
            for (var table : schema.tables()) {
                control.checkpoint(table.name(), 0);
                var textColumns = table.columns().stream().filter(column -> column.type().equals("TEXT")).toList();
                if (textColumns.isEmpty()) continue;
                var updates = textColumns.stream().map(column -> quote(column.name())
                        + "=migration_normalize_text(" + quote(column.name()) + ")").toList();
                var affected = textColumns.stream().map(column -> "CAST(" + quote(column.name())
                        + " AS BLOB)<>CAST(migration_normalize_text(" + quote(column.name()) + ") AS BLOB)").toList();
                execute(connection, "UPDATE " + quote(table.name()) + " SET " + String.join(",", updates)
                        + " WHERE " + String.join(" OR ", affected));
            }
        } finally { org.sqlite.Function.destroy(sqlite, "migration_normalize_text", 1); }
    }

    static DatabaseImportSession importDatabase(DatabaseConnectionTarget target, MigrationSchema schema,
                                                MigrationControl control) throws Exception {
        Connection connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
        try {
            target.requireConnectionTarget(connection);
            connection.setAutoCommit(false);
            target.prepareTransaction(connection, false);
            return importInto(connection, schema, control);
        } catch (Exception failure) {
            try { connection.rollback(); } catch (Exception rollback) { failure.addSuppressed(rollback); }
            try { connection.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    /** 调用方持有空目标的写事务；返回会话接管该连接。 */
    static DatabaseImportSession importInto(Connection connection, MigrationSchema schema,
                                            MigrationControl control) throws Exception {
        control.checkpoint("", 0);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT 1 FROM sqlite_schema WHERE substr(name,1,7)<>'sqlite_' LIMIT 1")) {
            if (rows.next()) throw failure("TARGET_NOT_EMPTY");
        }
        applySchema(connection, schema, true);
        return new JdbcImportSession(connection, schema, control) {
            @Override protected MigrationRows rows(TableSpec table) throws Exception { return SqliteMigration.rows(connection, table); }
            @Override
            protected void validateSchema() throws Exception {
                requireSchema(physical(schema), SqliteMigrationSchema.readActualTables(connection));
                integrity(connection);
            }

            @Override
            protected Map<String, Long> restoreSequences(Map<String, Long> expected) throws Exception {
                for (var table : schema.tables())
                    if (generatedKey(table).isPresent()) {
                        Long high = expected.get(table.name());
                        if (high == null || high < maximum(connection, table.name(), generatedKey(table).orElseThrow().name()))
                            throw failure("SEQUENCE_MISMATCH");
                        try (var delete = connection.prepareStatement("DELETE FROM sqlite_sequence WHERE name=?");
                             var insert = connection.prepareStatement("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)")) {
                            delete.setString(1, table.name());
                            delete.executeUpdate();
                            insert.setString(1, table.name());
                            insert.setLong(2, high);
                            insert.executeUpdate();
                        }
                    }
                return sequences(connection, schema);
            }
        };
    }

    static void applySchema(Connection connection, MigrationSchema schema, boolean importing) throws Exception {
        if (!importing) {
            SqliteStorageSchema.apply(connection, schema, true);
            requireSchema(physical(schema), SqliteMigrationSchema.readActualTables(connection));
            return;
        }
        for (var table : schema.tables()) {
            // 迁入保留全部生成键的历史高水位，避免回迁后复用已经删除的身份。
            var ddlTable = new TableSpec(table.name(), table.columns().stream().map(c ->
                            new ColumnSpec(c.name(), c.type(), c.notNull(), c.defaultValue(), c.primaryKeyPosition(),
                                    c.autoIncrement() || generatedKey(table).map(k -> k.name().equals(c.name())).orElse(false)))
                    .toList(), table.indexes(), table.checkExpression(), table.check());
            execute(connection, SqliteMigrationSchema.renderCreateTable(ddlTable));
            for (var index : table.indexes())
                if (index.origin() == IndexOrigin.CREATE_INDEX)
                    execute(connection, createIndex(table.name(), index));
        }
        requireSchema(physical(schema), SqliteMigrationSchema.readActualTables(connection));
        SqliteMigrationSchema.validateConstraints(connection, schema);
    }

    private static Map<String, Long> sequences(Connection connection, MigrationSchema schema) throws Exception {
        Map<String, Long> result = new LinkedHashMap<>();
        Map<String, Long> historical = new HashMap<>();
        try (var statement = connection.createStatement(); var exists = statement.executeQuery("SELECT 1 FROM sqlite_schema WHERE name='sqlite_sequence'")) {
            if (exists.next())
                try (var read = connection.createStatement(); var rows = read.executeQuery("SELECT name,seq FROM sqlite_sequence")) {
                    while (rows.next()) {
                        Object value = MigrationValues.canonical(rows.getObject(2), "INTEGER");
                        if (value != null) historical.put(rows.getString(1), (Long) value);
                    }
                }
        }
        var existing = SqliteMigrationSchema.readActualTables(connection);
        for (var table : schema.tables())
            if (generatedKey(table).isPresent()) {
                long maximum = existing.containsKey(table.name())
                        ? maximum(connection, table.name(), generatedKey(table).orElseThrow().name()) : 0;
                result.put(table.name(), Math.max(maximum, historical.getOrDefault(table.name(), 0L)));
            }
        return result;
    }

    private static void validateText(Connection connection, MigrationSchema schema, MigrationControl control) throws Exception {
        java.nio.charset.Charset encoding;
        try (var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA encoding")) {
            if (!row.next()) throw failure("TEXT_ENCODING");
            encoding = java.nio.charset.Charset.forName(row.getString(1));
        }
        var decoder = encoding.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        for (var table : schema.tables()) {
            var columns = table.columns().stream().filter(column -> column.type().equals("TEXT")).toList();
            if (columns.isEmpty()) continue;
            // JDBC 字符串解码可能替换非法字节；先按源库编码检查原始文本，避免错误摘要也一致。
            String fields = String.join(",", columns.stream().map(column -> "CAST(" + quote(column.name()) + " AS BLOB)").toList());
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT " + fields + " FROM " + quote(table.name()))) {
                long count = 0;
                while (rows.next()) {
                    control.checkpoint(table.name(), count++);
                    for (int i = 1; i <= columns.size(); i++) {
                        byte[] bytes = rows.getBytes(i);
                        if (bytes != null) try {
                            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
                        } catch (java.nio.charset.CharacterCodingException invalid) {
                            throw failure("TEXT_VALUE");
                        }
                    }
                }
            }
        }
    }

    private static void integrity(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA integrity_check")) {
            if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) throw failure("SOURCE_CORRUPT");
        }
    }

    static void validateObjects(Connection connection, MigrationSchema schema, Set<String> rebuild) throws SQLException {
        Set<String> seen = new HashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name,type,sql FROM sqlite_schema WHERE substr(name,1,7)<>'sqlite_'")) {
            while (rows.next()) {
                String name = rows.getString(1), type = rows.getString(2), ddl = rows.getString(3);
                if (rebuild.contains(name)) {
                    if (!"trigger".equals(type) && !("table".equals(type) && ddl != null
                            && ddl.toLowerCase(Locale.ROOT).contains("using fts5("))) throw failure("NOT_REGENERABLE");
                    seen.add(name);
                } else if ("view".equals(type) || "trigger".equals(type)) throw failure("UNDECLARED_OBJECT");
            }
        }
        if (!seen.equals(rebuild)) throw failure("REBUILD_OBJECT_MISSING");
        var names = schema.tables().stream().map(TableSpec::name).collect(java.util.stream.Collectors.toSet());
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_list")) {
            while (rows.next()) {
                if (!"main".equals(rows.getString("schema"))) continue;
                String name = rows.getString("name");
                if (name.startsWith("sqlite_")) continue;
                boolean auxiliary = rebuild.contains(name) || "shadow".equals(rows.getString("type"))
                        && rebuild.stream().anyMatch(parent -> name.startsWith(parent + "_"));
                if (!auxiliary && !names.contains(name)) throw failure("UNDECLARED_TABLE");
            }
        }
    }

    private static void cleanup(Connection locked, boolean sourceLocked, Connection working, Path directory) throws Exception {
        Exception failure = null;
        if (working != null) try {
            working.close();
        } catch (Exception e) {
            failure = e;
        }
        if (locked != null) {
            try {
                if (sourceLocked) execute(locked, "ROLLBACK");
            } catch (Exception e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
            try {
                locked.close();
            } catch (Exception e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (directory != null) try {
            for (String suffix : List.of("", "-wal", "-shm", "-journal"))
                Files.deleteIfExists(directory.resolve("working.db" + suffix));
            Files.delete(directory);
        } catch (Exception e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }

    private static final class Export implements DatabaseExportSession {
        private final Connection locked, working;
        private final Path directory;
        private final MigrationSnapshot snapshot;
        private MigrationRows cursor;
        private boolean closed;

        private Export(Connection locked, Connection working, Path directory, MigrationSnapshot snapshot) {
            this.locked = locked;
            this.working = working;
            this.directory = directory;
            this.snapshot = snapshot;
        }

        @Override
        public MigrationSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public MigrationRows rows(String table) throws Exception {
            if (closed) throw failure("MIGRATION_SESSION_CLOSED");
            if (cursor != null) cursor.close();
            cursor = SqliteMigration.rows(working, snapshot.schema().table(table));
            return cursor;
        }

        @Override
        public void close() throws Exception {
            if (closed) return;
            closed = true;
            try {
                if (cursor != null) cursor.close();
            } finally {
                cleanup(locked, true, working, directory);
            }
        }
    }
}
