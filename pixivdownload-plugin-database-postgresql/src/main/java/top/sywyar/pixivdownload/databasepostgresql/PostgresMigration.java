package top.sywyar.pixivdownload.databasepostgresql;

import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.*;

/** PostgreSQL 目录、快照、事务和序列语义；没有对其它源或目标的适配分支。 */
final class PostgresMigration {
    private PostgresMigration() {}
    static MigrationRows rows(Connection connection, TableSpec table) throws SQLException {
        return JdbcMigrationSupport.rows(connection, table, c -> quote(c.name())
                + (c.type().equals("TEXT") ? " COLLATE \"C\"" : ""));
    }

    static DatabaseMigrationProvider.Inspection inspect(DatabaseConnectionTarget target, MigrationSchema schema,
                                                        boolean source) throws Exception {
        PostgresMigrationSchema.validate(contributions(schema), physical(schema));
        if (!source && target instanceof PostgresPrivateStorage privateTarget)
            return new DatabaseMigrationProvider.Inspection(privateTarget.inspectMigrationTarget(), Set.of());
        try (var connection = target instanceof PostgresPrivateStorage privateTarget
                ? privateTarget.openInspection() : ((PostgresConnectionTarget) target).openInspection()) {
            target.requireConnectionTarget(connection);
            Set<String> rebuild = new TreeSet<>();
            if (source) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                        SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                        JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND NOT t.tgisinternal
                        UNION ALL SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                        WHERE n.nspname=current_schema()
                        """)) { while (rows.next()) rebuild.add(rows.getString(1)); }
                validateObjects(connection, schema, rebuild);
                validateSchema(connection, schema);
            } else try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT has_schema_privilege(current_schema(),'CREATE')")) {
                if (!rows.next() || !rows.getBoolean(1)) throw failure("TARGET_NOT_WRITABLE");
            }
            return new DatabaseMigrationProvider.Inspection(empty(connection, connection.getSchema()), rebuild);
        }
    }
    static boolean empty(Connection connection, String schema) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=?)
                  AND NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=?)
                  AND NOT EXISTS(SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname=?)
                """)) {
            for (int i = 1; i <= 3; i++) statement.setString(i, schema);
            try (var rows = statement.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }
    static void verifyImported(DatabaseConnectionTarget target, MigrationSnapshot snapshot,
                               MigrationControl control) throws Exception {
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY)) {
            target.requireConnectionTarget(connection);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            validateObjects(connection, snapshot.schema(), Set.of());
            validateSchema(connection, snapshot.schema());
            var actual = new MigrationVerification(digests(snapshot.schema(), control, table -> PostgresMigration.rows(connection, table)),
                    sequences(connection, snapshot.schema()), references(connection, snapshot.schema(), control));
            if (!actual.equals(snapshot.verification())) throw failure("DATA_MISMATCH");
        }
    }
    static Set<String> owners(PostgresConnectionTarget main, String installation) throws Exception {
        var properties = main.connectionProperties();
        String prefix = "pixivdownload/private/" + installation + "/";
        String suffix = "/" + properties.getProperty("PGDBNAME") + "/" + properties.getProperty("currentSchema");
        Set<String> result = new TreeSet<>();
        try (var connection = main.openConnection(DatabaseConnectionTarget.OpenMode.READ_ONLY);
             var statement = connection.createStatement(); var rows = statement.executeQuery(
                     "SELECT nspname,obj_description(oid,'pg_namespace') FROM pg_namespace")) {
            while (rows.next()) {
                String comment = rows.getString(2);
                if (comment == null || !comment.startsWith(prefix) || !comment.endsWith(suffix)) continue;
                String owner = comment.substring(prefix.length(), comment.length() - suffix.length());
                if (!owner.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*") || !result.add(owner)) throw failure("PRIVATE_OWNER_INVALID");
                var expected = new PostgresPrivateStorage(main, new DatabasePrivateStorageSettings(owner,
                        Path.of("."), installation, "unused-for-identity"));
                if (!expected.schemaName().equals(rows.getString(1))) throw failure("PRIVATE_OWNER_INVALID");
            }
        }
        return Set.copyOf(result);
    }
    static DatabaseExportSession exportDatabase(DatabaseConnectionTarget target, MigrationRequest request,
                                                 MigrationControl control) throws Exception {
        PostgresMigrationSchema.validate(contributions(request.schema()), physical(request.schema()));
        if (Files.exists(request.backup())) throw failure("BACKUP_EXISTS");
        Connection connection = target.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
        try {
            target.requireConnectionTarget(connection);
            // 会话锁在事务外取得；先锁表，再由首次查询固定快照，避免锁前提交的数据漏入备份。
            try (var statement = connection.createStatement(); var lock = statement.executeQuery(
                    "SELECT pg_try_advisory_lock(hashtextextended(current_database() || '.' || current_schema(),0))")) {
                if (!lock.next() || !lock.getBoolean(1)) throw failure("DATABASE_BUSY");
            }
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            control.checkpoint("", 0);
            for (var table : request.schema().tables())
                execute(connection, "LOCK TABLE " + quote(table.name()) + " IN SHARE MODE NOWAIT");
            validateObjects(connection, request.schema(), request.rebuildObjects());
            validateSchema(connection, request.schema());
            try (var session = target.storageSession(connection, request.schema())) {
                top.sywyar.pixivdownload.plugin.api.storage.relational.StorageMigration.requireApplied(session, request.storageMigrations());
            }
            var sequences = sequences(connection, request.schema());
            String revision = revision(connection, control);
            PostgresMigrationBackup.write(connection, request, sequences, control);
            normalizeSeeds(connection, request.schema());
            for (var table : request.schema().tables()) if (generatedKey(table).isPresent())
                sequences.merge(table.name(), maximum(connection, table.name(), generatedKey(table).orElseThrow().name()), Math::max);
            var verified = new MigrationVerification(digests(request.schema(), control, table -> PostgresMigration.rows(connection, table)), sequences,
                    references(connection, request.schema(), control));
            var snapshot = new MigrationSnapshot(MigrationSnapshot.FORMAT_VERSION, request.schema(), verified,
                    request.backup(), revision, request.rebuildObjects().stream().sorted().toList());
            return new Export(connection, snapshot);
        } catch (Exception failure) {
            try { connection.rollback(); } catch (Exception rollback) { failure.addSuppressed(rollback); }
            try { connection.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    static DatabaseImportSession importDatabase(DatabaseConnectionTarget target, MigrationSchema schema,
                                                 MigrationControl control) throws Exception {
        PostgresMigrationSchema.validate(contributions(schema), physical(schema));
        var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
        try {
            target.requireConnectionTarget(connection);
            connection.setAutoCommit(false);
            lock(connection);
            control.checkpoint("", 0);
            try (var statement = connection.createStatement(); var objects = statement.executeQuery("""
                    SELECT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema())
                      OR EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=current_schema())
                      OR EXISTS(SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname=current_schema())
                    """)) {
                if (!objects.next() || objects.getBoolean(1)) throw failure("TARGET_NOT_EMPTY");
            }
            for (var table : schema.tables()) {
                execute(connection, PostgresMigrationSchema.renderCreateTable(table));
                for (var index : table.indexes()) if (index.origin() == IndexOrigin.CREATE_INDEX)
                    execute(connection, createIndex(table.name(), index));
            }
            validateSchema(connection, schema);
            return new JdbcImportSession(connection, schema, control) {
                @Override protected MigrationRows rows(TableSpec table) throws Exception { return PostgresMigration.rows(connection, table); }
                @Override protected void validateSchema() throws Exception { PostgresMigration.validateSchema(connection, schema); }
                @Override protected Map<String, Long> restoreSequences(Map<String, Long> expected) throws Exception {
                    for (var table : schema.tables()) if (generatedKey(table).isPresent()) {
                        String column = generatedKey(table).orElseThrow().name();
                        Long high = expected.get(table.name());
                        if (high == null || high < maximum(connection, table.name(), column)) throw failure("SEQUENCE_MISMATCH");
                        String sequence = sequence(connection, table.name(), column);
                        try (var reset = connection.prepareStatement("SELECT setval(?::regclass,?,?)")) {
                            reset.setString(1, sequence); reset.setLong(2, Math.max(high, 1)); reset.setBoolean(3, high >= 1);
                            reset.execute();
                        }
                    }
                    return sequences(connection, schema);
                }
            };
        } catch (Exception failure) {
            try { connection.rollback(); } catch (Exception rollback) { failure.addSuppressed(rollback); }
            try { connection.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    private static void lock(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var lock = statement.executeQuery(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(current_database() || '.' || current_schema(),0))")) {
            if (!lock.next() || !lock.getBoolean(1)) throw failure("DATABASE_BUSY");
        }
    }
    static void validateSchema(Connection connection, MigrationSchema schema) throws Exception {
        requireSchema(PostgresMigrationSchema.physicalSchema(physical(schema)), PostgresMigrationSchema.readActualTables(connection));
        PostgresMigrationSchema.validateConstraints(connection, contributions(schema));
    }
    private static void normalizeSeeds(Connection connection, MigrationSchema schema) throws SQLException {
        Set<String> copied = new HashSet<>();
        String sourceSchema = connection.getSchema();
        for (var seed : schema.seeds()) {
            var keys = MigrationSchema.primaryKey(schema.table(seed.table()));
            boolean missing;
            try (var query = connection.prepareStatement("SELECT 1 FROM " + quote(seed.table()) + " WHERE "
                    + String.join(" AND ", keys.stream().map(k -> quote(k) + "=?").toList()))) {
                for (int i = 0; i < keys.size(); i++) query.setObject(i + 1, seed.values().get(keys.get(i)));
                try (var rows = query.executeQuery()) { missing = !rows.next(); }
            }
            if (missing && copied.add(seed.table())) {
                // 临时同名表只遮蔽本连接的查询；原表、原序列和原始备份均不播种。
                String original = quote(sourceSchema) + "." + quote(seed.table());
                execute(connection, "CREATE TEMP TABLE " + quote(seed.table()) + " (LIKE " + original + " INCLUDING ALL) ON COMMIT DROP");
                execute(connection, "INSERT INTO pg_temp." + quote(seed.table()) + " SELECT * FROM " + original);
            }
        }
        seed(connection, schema);
    }
    static void validateObjects(Connection connection, MigrationSchema schema, Set<String> rebuild) throws Exception {
        var tables = schema.tables().stream().map(TableSpec::name).collect(java.util.stream.Collectors.toSet());
        Set<String> seen = new HashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=current_schema() AND (c.relrowsecurity OR c.relforcerowsecurity OR c.relispartition
                  OR EXISTS(SELECT 1 FROM pg_rewrite r WHERE r.ev_class=c.oid)) LIMIT 1
                """)) { if (rows.next()) throw failure("UNDECLARED_OBJECT"); }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=current_schema() ORDER BY c.relname
                """)) {
            while (rows.next()) {
                String name = rows.getString(1), kind = rows.getString(2);
                if ("r".equals(kind) && tables.contains(name) || "i".equals(kind) || "S".equals(kind)) continue;
                throw failure("UNDECLARED_OBJECT");
            }
        }
        // 只允许显式再生的触发器及其函数；普通表和独立类型不能作为辅助对象略过。
        Set<String> seenObjects = new HashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT t.tgname,'trigger' FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND NOT t.tgisinternal
                UNION ALL SELECT p.proname,'function' FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=current_schema()
                """)) {
            while (rows.next()) {
                String name = rows.getString(1);
                if (!rebuild.contains(name) || !seenObjects.add(rows.getString(2) + ":" + name))
                    throw failure("UNDECLARED_OBJECT");
                seen.add(name);
            }
        }
        if (!seen.equals(rebuild)) throw failure("REBUILD_OBJECT_MISSING");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
                WHERE n.nspname=current_schema() AND t.typrelid=0 AND t.typelem=0 LIMIT 1
                """)) { if (rows.next()) throw failure("UNDECLARED_OBJECT"); }
        Set<String> allowedSequences = new HashSet<>();
        for (var table : schema.tables()) if (generatedKey(table).isPresent())
            allowedSequences.add(sequence(connection, table.name(), generatedKey(table).orElseThrow().name()));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT c.oid::regclass::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=current_schema() AND c.relkind='S'
                """)) { while (rows.next()) if (!allowedSequences.contains(rows.getString(1))) throw failure("UNDECLARED_OBJECT"); }
    }
    private static String sequence(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_get_serial_sequence(?,?)::regclass::text")) {
            statement.setString(1, quote(table)); statement.setString(2, column);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw failure("SEQUENCE_MISSING");
                String sequence = rows.getString(1);
                try (var properties = connection.prepareStatement("""
                        SELECT seqincrement=1 AND seqmin=1 AND seqmax=9223372036854775807
                               AND seqstart=1 AND NOT seqcycle FROM pg_sequence WHERE seqrelid=?::regclass
                        """)) {
                    properties.setString(1, sequence);
                    try (var values = properties.executeQuery()) {
                        if (!values.next() || !values.getBoolean(1)) throw failure("SEQUENCE_UNSUPPORTED");
                    }
                }
                return sequence;
            }
        }
    }
    private static Map<String, Long> sequences(Connection connection, MigrationSchema schema) throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        for (var table : schema.tables()) if (generatedKey(table).isPresent()) {
            String column = generatedKey(table).orElseThrow().name();
            String sequence = sequence(connection, table.name(), column);
            // 名称只来自已验证的系统目录 regclass，不能由用户输入拼接。
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT last_value,is_called FROM " + sequence)) {
                if (!rows.next()) throw failure("SEQUENCE_MISSING");
                long high = rows.getBoolean(2) ? rows.getLong(1) : 0;
                result.put(table.name(), Math.max(high, maximum(connection, table.name(), column)));
            }
        }
        return result;
    }
    static String revision(DatabaseConnectionTarget target) throws Exception {
        try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING)) {
            target.requireConnectionTarget(connection);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try { return revision(connection, (table, rows) -> {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("MIGRATION_CANCELLED");
            }); }
            finally { connection.rollback(); }
        }
    }
    private static String revision(Connection connection, MigrationControl control) throws Exception {
        var digest = MigrationValues.newDigest();
        for (var table : PostgresMigrationSchema.readActualTables(connection).values().stream()
                .sorted(Comparator.comparing(PhysicalDatabaseSchema.TableSpec::name)).toList()) {
            MigrationValues.hash(digest, table.toString());
            var columns = table.columns().stream().map(c -> new ColumnSpec(c.name(), switch (c.type()) {
                case "BIGINT" -> "INTEGER"; case "DOUBLE PRECISION" -> "REAL"; case "BYTEA" -> "BLOB";
                case "TEXT" -> "TEXT"; default -> throw failure("COLUMN_TYPE");
            }, c.notNull(), c.defaultValue() != null && c.defaultValue().startsWith("<identity:") ? null : c.defaultValue(),
                    c.primaryKeyPosition())).toList();
            var logical = new TableSpec(table.name(), columns, List.of());
            try (var rows = PostgresMigration.rows(connection, logical)) {
                MigrationValues.hash(digest, MigrationValues.digest(rows, control, table.name()).toString());
            }
            for (var column : table.columns()) if ("<identity:d>".equals(column.defaultValue())) {
                String sequence = sequence(connection, table.name(), column.name());
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT last_value,is_called FROM " + sequence)) {
                    if (!rows.next()) throw failure("SEQUENCE_MISSING");
                    MigrationValues.hash(digest, rows.getLong(1)); MigrationValues.hash(digest, rows.getBoolean(2) ? 1L : 0L);
                }
            }
        }
        for (var entry : objectDefinitions(connection).entrySet()) {
            MigrationValues.hash(digest, entry.getKey()); MigrationValues.hash(digest, entry.getValue());
        }
        return "postgresql-content-v1:" + MigrationValues.finish(digest);
    }
    static Map<String, String> objectDefinitions(Connection connection) throws SQLException {
        Map<String, String> definitions = new TreeMap<>();
        for (String query : List.of(
                "SELECT 'constraint:'||t.relname||':'||c.conname,pg_get_constraintdef(c.oid) FROM pg_constraint c "
                        + "JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=current_schema()",
                "SELECT 'trigger:'||c.relname||':'||t.tgname,pg_get_triggerdef(t.oid) FROM pg_trigger t "
                        + "JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace "
                        + "WHERE n.nspname=current_schema() AND NOT t.tgisinternal",
                "SELECT 'function:'||p.oid::regprocedure::text,pg_get_functiondef(p.oid) FROM pg_proc p "
                        + "JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=current_schema()",
                "SELECT 'sequence:'||c.relname,concat_ws(',',s.seqtypid::regtype,s.seqstart,s.seqincrement,s.seqmax,s.seqmin,s.seqcache,s.seqcycle) "
                        + "FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid "
                        + "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema()")) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
                while (rows.next()) definitions.put(rows.getString(1), rows.getString(2));
            }
        }
        return definitions;
    }
    private static final class Export implements DatabaseExportSession {
        private final Connection connection;
        private final MigrationSnapshot snapshot;
        private MigrationRows cursor;
        private boolean closed;
        private Export(Connection connection, MigrationSnapshot snapshot) { this.connection = connection; this.snapshot = snapshot; }
        @Override public MigrationSnapshot snapshot() { return snapshot; }
        @Override public MigrationRows rows(String table) throws Exception {
            if (closed) throw failure("MIGRATION_SESSION_CLOSED");
            if (cursor != null) cursor.close();
            cursor = PostgresMigration.rows(connection, snapshot.schema().table(table));
            return cursor;
        }
        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            try { if (cursor != null) cursor.close(); }
            finally { try { connection.rollback(); } finally { connection.close(); } }
        }
    }
}
