package top.sywyar.pixivdownload.core.db.schema;

import top.sywyar.pixivdownload.core.db.DatabaseEngine;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.sqlite.SqliteDatabase;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 停机主库迁移。调用方持有应用实例锁；这里另外持有 SQLite 写锁和目标事务锁。 */
public final class SqliteToPostgresMigration {
    private static final int BATCH_ROWS = 500;
    private static final long BATCH_BYTES = 8L * 1024 * 1024;

    private SqliteToPostgresMigration() {}

    public record TableResult(long rows, String sha256) {}
    public record Result(Path backup, Map<String, TableResult> tables, Map<String, Long> referenceWarnings,
                         List<String> rebuildObjects) {
        public Result {
            tables = Map.copyOf(tables);
            referenceWarnings = Map.copyOf(referenceWarnings);
            rebuildObjects = List.copyOf(rebuildObjects);
        }
    }

    public static Result migrate(Path source, Path backup, DatabaseTarget target,
            List<SchemaContribution> contributions, ManagedDatabaseSchema.DatabaseSchema schema,
            Set<String> rebuildObjects) throws Exception {
        if (target.engine() != DatabaseEngine.POSTGRESQL) throw failure("TARGET_ENGINE", "");
        PostgresSchema.validate(contributions, schema);
        for (var table : schema.tables().values()) {
            if (primaryKey(table).isEmpty()) throw failure("MISSING_PRIMARY_KEY", table.name());
        }
        Path sourcePath = source.toRealPath();
        Path backupPath = backup.toAbsolutePath().normalize();
        if (sourcePath.equals(backupPath) || Files.exists(backupPath)) throw failure("BACKUP_EXISTS", "");
        Files.createDirectories(backupPath.getParent());
        Path working = null;
        Path workingDirectory = null;
        try (Connection lockedSource = SqliteDatabase.openExisting(sourcePath);
             Connection destination = target.openConnection()) {
            destination.setAutoCommit(false);
            try (var lock = lockedSource.createStatement()) {
                lock.execute("BEGIN IMMEDIATE");
                try {
                    requireEmptyTarget(destination);
                    validateSourceObjects(lockedSource, schema, rebuildObjects);
                    Map<String, Long> sourceSequences = readSequences(lockedSource);
                    createPrivateFile(backupPath);
                    // 另一条只读事务连接执行 SQLite 自身的快照复制，包含已提交 WAL；源写锁保持到结束。
                    try (Connection reader = SqliteDatabase.openExisting(sourcePath);
                         var copy = reader.prepareStatement("VACUUM INTO ?")) {
                        copy.setString(1, backupPath.toString());
                        copy.execute();
                    }
                    workingDirectory = Files.createTempDirectory(backupPath.getParent(), ".migration-");
                    makePrivate(workingDirectory);
                    working = workingDirectory.resolve("working.db");
                    Files.copy(backupPath, working);
                    Map<String, TableResult> results = new LinkedHashMap<>();
                    Map<String, Long> referenceWarnings;
                    try (Connection snapshot = SqliteDatabase.openExisting(working)) {
                        requireIntegrity(snapshot);
                        DatabaseInitializer.offline(snapshot, contributions, schema).applySchema(contributions, schema);
                        requireSchema(snapshot, schema);
                        seedHistoricalFileNameTemplate(snapshot);
                        DatabaseInitializer.offline(destination, contributions, schema).applySchema(contributions, schema);
                        requireSchema(destination, schema);
                        for (var table : schema.tables().values().stream()
                                .sorted(Comparator.comparing(ManagedDatabaseSchema.TableSpec::name)).toList()) {
                            TableResult copied = transfer(snapshot, destination, table);
                            TableResult verified = digest(destination, table);
                            if (!copied.equals(verified)) throw failure("DATA_MISMATCH", table.name());
                            results.put(table.name(), verified);
                            resetSequence(destination, table, sourceSequences.getOrDefault(table.name(), 0L));
                        }
                        referenceWarnings = referenceWarnings(snapshot, schema, contributions);
                        if (!referenceWarnings.equals(referenceWarnings(destination, schema, contributions))) {
                            throw failure("REFERENCE_MISMATCH", "");
                        }
                        try (var statistics = destination.createStatement()) {
                            for (String table : results.keySet()) statistics.execute("ANALYZE " + quote(table));
                        }
                        destination.commit();
                    }
                    return new Result(backupPath, results, referenceWarnings, rebuildObjects.stream().sorted().toList());
                } catch (Exception failure) {
                    destination.rollback();
                    throw failure;
                } finally {
                    lock.execute("ROLLBACK");
                }
            }
        } catch (SQLException failure) {
            // BatchUpdateException 可能包含完整一行（包括凭据），不保留驱动正文或 cause。
            throw failure("SQLSTATE_" + failure.getSQLState(), "");
        } finally {
            if (working != null) {
                for (String suffix : List.of("-wal", "-shm", "-journal", "")) {
                    Files.deleteIfExists(Path.of(working + suffix));
                }
            }
            if (workingDirectory != null) Files.deleteIfExists(workingDirectory);
        }
    }

    private static void requireEmptyTarget(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            try (var lock = statement.executeQuery("SELECT pg_try_advisory_xact_lock("
                    + "hashtextextended(current_database() || '.' || current_schema(), 0))")) {
                if (!lock.next() || !lock.getBoolean(1)) throw failure("TARGET_BUSY", "");
            }
            try (var objects = statement.executeQuery("""
                    SELECT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                                  WHERE n.nspname=current_schema())
                        OR EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                                  WHERE n.nspname=current_schema())
                        OR EXISTS(SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
                                  WHERE n.nspname=current_schema())
                    """)) {
                if (!objects.next() || objects.getBoolean(1)) throw failure("TARGET_NOT_EMPTY", "");
            }
        }
    }

    private static void validateSourceObjects(Connection connection, ManagedDatabaseSchema.DatabaseSchema schema,
            Set<String> rebuildObjects) throws SQLException {
        Set<String> seen = new java.util.HashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name, type, sql FROM sqlite_schema WHERE substr(name,1,7) <> 'sqlite_' ORDER BY name")) {
            while (rows.next()) {
                String name = rows.getString(1);
                String type = rows.getString(2);
                String ddl = rows.getString(3);
                if (rebuildObjects.contains(name)) {
                    if (!"trigger".equals(type) && !("table".equals(type) && ddl != null
                            && ddl.toLowerCase(java.util.Locale.ROOT).contains("using fts5("))) {
                        throw failure("NOT_REGENERABLE", name);
                    }
                    seen.add(name);
                } else if ("view".equals(type) || "trigger".equals(type)) {
                    throw failure("UNDECLARED_OBJECT", name);
                }
            }
        }
        if (!seen.equals(rebuildObjects)) throw failure("REBUILD_OBJECT_MISSING", "");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_list")) {
            while (rows.next()) {
                if (!"main".equals(rows.getString("schema"))) continue;
                String name = rows.getString("name");
                if (name.startsWith("sqlite_")) continue;
                String type = rows.getString("type");
                boolean knownAuxiliary = rebuildObjects.contains(name)
                        || "shadow".equals(type) && rebuildObjects.stream().anyMatch(parent -> name.startsWith(parent + "_"));
                if (!knownAuxiliary && !schema.tables().containsKey(name)) throw failure("UNDECLARED_TABLE", name);
            }
        }
    }

    private static void requireIntegrity(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA integrity_check")) {
            if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) throw failure("SOURCE_CORRUPT", "");
        }
    }

    private static void requireSchema(Connection connection, ManagedDatabaseSchema.DatabaseSchema schema) throws SQLException {
        var comparison = DatabaseSchemaInspector.compare(connection, schema);
        if (!comparison.matches()) throw failure("SCHEMA_MISMATCH", comparison.details().get(0).tableName());
    }

    private static void seedHistoricalFileNameTemplate(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO file_name_templates(id, template) VALUES(1, ?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, PixivWorkFileNameFormatter.DEFAULT_TEMPLATE);
            statement.executeUpdate();
        }
    }

    private static List<String> primaryKey(ManagedDatabaseSchema.TableSpec table) {
        return table.columns().stream().filter(column -> column.primaryKeyPosition() > 0)
                .sorted(Comparator.comparingInt(ManagedDatabaseSchema.ColumnSpec::primaryKeyPosition))
                .map(ManagedDatabaseSchema.ColumnSpec::name).toList();
    }

    private static String select(ManagedDatabaseSchema.TableSpec table) {
        return "SELECT " + String.join(",", table.columns().stream().map(column -> quote(column.name())).toList())
                + " FROM " + quote(table.name()) + " ORDER BY "
                + String.join(",", primaryKey(table).stream().map(SqliteToPostgresMigration::quote).toList());
    }

    private static TableResult transfer(Connection source, Connection target, ManagedDatabaseSchema.TableSpec table)
            throws Exception {
        String insert = "INSERT INTO " + quote(table.name()) + " ("
                + String.join(",", table.columns().stream().map(column -> quote(column.name())).toList())
                + ") VALUES (" + String.join(",", java.util.Collections.nCopies(table.columns().size(), "?")) + ")";
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        long count = 0;
        int bufferedRows = 0;
        long bufferedBytes = 0;
        try (var read = source.prepareStatement(select(table));
             var write = target.prepareStatement(insert); var rows = read.executeQuery()) {
            while (rows.next()) {
                for (int i = 0; i < table.columns().size(); i++) {
                    Object value = value(rows, i + 1, table.columns().get(i).type(), table.name());
                    hashValue(hash, value);
                    write.setObject(i + 1, value);
                    bufferedBytes += value instanceof byte[] bytes ? bytes.length
                            : value instanceof String string ? 3L * string.length() : 8;
                }
                write.addBatch();
                count++;
                // 单行可以超过批预算，仍单独完整转移；不能因历史正文较大而截断或拒绝。
                if (++bufferedRows == BATCH_ROWS || bufferedBytes >= BATCH_BYTES) {
                    write.executeBatch();
                    write.clearBatch();
                    bufferedRows = 0;
                    bufferedBytes = 0;
                }
            }
            if (bufferedRows != 0) write.executeBatch();
        }
        return new TableResult(count, HexFormat.of().formatHex(hash.digest()));
    }

    private static TableResult digest(Connection connection, ManagedDatabaseSchema.TableSpec table) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (var statement = connection.prepareStatement(select(table))) {
            statement.setFetchSize(1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    for (int i = 0; i < table.columns().size(); i++) {
                        hashValue(hash, value(rows, i + 1, table.columns().get(i).type(), table.name()));
                    }
                    count++;
                }
            }
        }
        return new TableResult(count, HexFormat.of().formatHex(hash.digest()));
    }

    private static Object value(ResultSet rows, int index, String type, String table) throws SQLException {
        Object raw = rows.getObject(index);
        if (raw == null) return null;
        return switch (type) {
            case "INTEGER" -> {
                if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
                    throw failure("INTEGER_VALUE", table);
                }
                yield ((Number) raw).longValue();
            }
            case "REAL" -> {
                if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    throw failure("REAL_VALUE", table);
                }
                yield ((Number) raw).doubleValue();
            }
            case "TEXT" -> {
                if (!(raw instanceof String)) throw failure("TEXT_VALUE", table);
                yield raw;
            }
            case "BLOB" -> {
                if (!(raw instanceof byte[])) throw failure("BLOB_VALUE", table);
                yield raw;
            }
            default -> throw failure("COLUMN_TYPE", table);
        };
    }

    private static void hashValue(MessageDigest hash, Object value) {
        if (value == null) { hash.update((byte) 0); return; }
        byte[] bytes;
        if (value instanceof Long number) { hash.update((byte) 1); bytes = ByteBuffer.allocate(8).putLong(number).array(); }
        else if (value instanceof Double number) { hash.update((byte) 2); bytes = ByteBuffer.allocate(8).putDouble(number).array(); }
        else if (value instanceof byte[] blob) { hash.update((byte) 3); bytes = blob; }
        else { hash.update((byte) 4); bytes = value.toString().getBytes(StandardCharsets.UTF_8); }
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }

    private static Map<String, Long> readSequences(Connection source) throws SQLException {
        Map<String, Long> sequences = new LinkedHashMap<>();
        try (var statement = source.createStatement()) {
            try (var exists = statement.executeQuery("SELECT 1 FROM sqlite_schema WHERE name='sqlite_sequence'")) {
                if (!exists.next()) return sequences;
            }
            // 从持有写锁的原库读取；工作副本升级重建表时不能丢失已删除身份的高水位。
            try (var rows = statement.executeQuery("SELECT name, seq FROM sqlite_sequence")) {
                while (rows.next()) {
                    String table = rows.getString(1);
                    Long sequence = (Long) value(rows, 2, "INTEGER", table);
                    if (sequence != null) sequences.merge(table, sequence, Math::max);
                }
            }
        }
        return sequences;
    }

    private static void resetSequence(Connection connection, ManagedDatabaseSchema.TableSpec table,
                                      long sourceSequence) throws SQLException {
        for (var column : table.columns()) {
            try (var find = connection.prepareStatement("SELECT pg_get_serial_sequence(?, ?)")) {
                find.setString(1, quote(table.name()));
                find.setString(2, column.name());
                try (var sequence = find.executeQuery()) {
                    if (!sequence.next() || sequence.getString(1) == null) continue;
                    try (var reset = connection.prepareStatement(
                            "SELECT setval(?::regclass, GREATEST(high_water, 1), high_water >= 1)"
                            + " FROM (SELECT GREATEST(COALESCE(MAX(" + quote(column.name())
                            + "), 0), ?) AS high_water FROM " + quote(table.name()) + ") sequence_state")) {
                        reset.setString(1, sequence.getString(1));
                        reset.setLong(2, sourceSequence);
                        reset.execute();
                    }
                }
            }
        }
    }

    private static Map<String, Long> referenceWarnings(Connection connection, ManagedDatabaseSchema.DatabaseSchema schema,
                                                       List<SchemaContribution> contributions) throws SQLException {
        Map<String, Long> warnings = new LinkedHashMap<>();
        // 媒体页允许早于作品完成登记，旧库也可能存在悬空关系；保留并对比，不能额外强加外键。
        for (String relation : List.of("artwork_tags:artwork_id:artworks:artwork_id",
                "artwork_tags:tag_id:tags:tag_id", "artwork_media:artwork_id:artworks:artwork_id",
                "novel_tags:novel_id:novels:novel_id", "novel_tags:tag_id:tags:tag_id",
                "artworks:file_name:file_name_templates:id", "novels:file_name:file_name_templates:id")) {
            String[] parts = relation.split(":");
            if (!schema.tables().containsKey(parts[0]) || !schema.tables().containsKey(parts[2])) continue;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT count(*) FROM " + quote(parts[0]) + " c LEFT JOIN " + quote(parts[2])
                            + " p ON c." + quote(parts[1]) + " = p." + quote(parts[3])
                            + " WHERE c." + quote(parts[1]) + " IS NOT NULL AND p." + quote(parts[3]) + " IS NULL")) {
                if (rows.next() && rows.getLong(1) > 0) warnings.put(relation, rows.getLong(1));
            }
        }
        for (var pathSpec : contributions.stream().flatMap(value -> value.pathColumns().stream()).distinct().toList()) {
            for (String column : pathSpec.pathColumns()) {
                long missing = 0;
                try (var statement = connection.prepareStatement("SELECT " + quote(column) + " FROM " + quote(pathSpec.table()));
                     var prefix = connection.prepareStatement("SELECT 1 FROM path_prefixes WHERE id=?")) {
                    statement.setFetchSize(1);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            String path = rows.getString(1);
                            if (path == null || !path.startsWith("{")) continue;
                            int end = path.indexOf('}');
                            long id;
                            try { id = end > 1 ? Long.parseLong(path.substring(1, end)) : -1; }
                            catch (NumberFormatException invalid) { id = -1; }
                            if (id == 0) continue;
                            if (id < 0) { missing++; continue; }
                            prefix.setLong(1, id);
                            try (var found = prefix.executeQuery()) { if (!found.next()) missing++; }
                        }
                    }
                }
                if (missing > 0) warnings.put(pathSpec.table() + ":" + column + ":path_prefixes", missing);
            }
        }
        return warnings;
    }

    private static void createPrivateFile(Path file) throws IOException {
        Files.createFile(file);
        makePrivate(file);
    }

    private static void makePrivate(Path path) throws IOException {
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (Files.isDirectory(path)) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        }
    }

    private static String quote(String name) { return PostgresSchema.quote(name); }

    private static IllegalStateException failure(String code, String table) {
        return new IllegalStateException(MessageBundles.get("core.db.migration-failed", code, table));
    }
}
