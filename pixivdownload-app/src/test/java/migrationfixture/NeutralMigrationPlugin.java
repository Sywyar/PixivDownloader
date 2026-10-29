package migrationfixture;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 独立行文件格式的迁移夹具；不使用 JDBC 方言，未实现的业务 JDBC 明确拒绝。 */
public final class NeutralMigrationPlugin extends Plugin implements PixivPluginProvider {
    @Override public PixivFeaturePlugin featurePlugin() { return new Provider(); }
    public static final class Provider implements PixivFeaturePlugin, DatabaseProvider {
        @Override public List<top.sywyar.pixivdownload.plugin.api.storage.EnvironmentFileContribution> environmentFiles() {
            return List.of(new top.sywyar.pixivdownload.plugin.api.storage.EnvironmentFileContribution() {
                @Override public Definition definition() {
                    return new Definition("preferences", Root.STATE, "", "preferences\\.txt", 1, Kind.STATE, 1, 16 * 1024, false);
                }
                @Override public void copy(String name, InputStream source, InputStream existing, OutputStream output, boolean credentials) throws IOException {
                    source.transferTo(output);
                }
            });
        }
        @Override public String id() { return "neutral-migration"; }
        @Override public String displayName() { return "fixture.name"; }
        @Override public String description() { return "fixture.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public String databaseId() { return "ledger-v1"; }
        @Override public List<DatabaseConnectionField> connectionFields() {
            return List.of(new DatabaseConnectionField("database.jdbc-url", top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType.STRING));
        }
        @Override public void prepareStorage(DatabaseConnectionSettings settings) { resolve(settings); }
        @Override public DatabaseConnectionSettings localFileSettings(DatabaseConnectionSettings settings, Path file) {
            return new DatabaseConnectionSettings("ledger:" + file, "", "", settings.dataDirectory());
        }
        @Override public DatabaseConnectionSettings normalizeConfiguration(DatabaseConnectionSettings settings, boolean selectionChanged) { return settings; }
        @Override public DatabaseConnectionTarget resolve(DatabaseConnectionSettings settings) {
            if (!settings.jdbcUrl().startsWith("ledger:")) throw new IllegalArgumentException("FIXTURE_ADDRESS");
            return new Target(Path.of(settings.jdbcUrl().substring(7)));
        }
        @Override public Map<String, DatabaseConnectionTarget> retainedLocalPrivateDatabases(Path data, String installation) throws Exception {
            Map<String, DatabaseConnectionTarget> found = new TreeMap<>();
            if (Files.exists(data)) try (var entries = Files.newDirectoryStream(data)) {
                for (Path entry : entries) if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve("facts.ledger")))
                    found.put(entry.getFileName().toString(), new Target(entry.resolve("facts.ledger")));
            }
            return Map.copyOf(found);
        }
        @Override public Set<String> privateDatabaseOwners(DatabaseConnectionTarget main, Path data, String installation) throws Exception {
            return retainedLocalPrivateDatabases(data, installation).keySet();
        }
        @Override public DatabaseExportSession exportDatabase(DatabaseConnectionTarget source, MigrationRequest request,
                                                               MigrationControl control) throws Exception {
            var path = ((Target) source).path;
            var channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                var lock = channel.tryLock();
                if (lock == null) throw new IllegalStateException("FIXTURE_BUSY");
                var data = Archive.read(path, request.schema());
                if (!request.rebuildObjects().isEmpty()) throw new IllegalStateException("REBUILD_OBJECT_MISSING");
                MigrationFiles.createPrivateFile(request.backup());
                Files.copy(path, request.backup(), StandardCopyOption.REPLACE_EXISTING);
                try (var backup = FileChannel.open(request.backup(), StandardOpenOption.WRITE)) { backup.force(true); }
                var snapshot = new MigrationSnapshot(1, request.schema(), data.verify(control), request.backup(),
                        "ledger-v1:" + MigrationFiles.digest(path), List.of());
                return new DatabaseExportSession() {
                    @Override public MigrationSnapshot snapshot() { return snapshot; }
                    @Override public MigrationRows rows(String table) { return cursor(data.rows.get(request.schema().table(table).name())); }
                    @Override public void close() throws Exception { channel.close(); }
                };
            } catch (Exception error) { channel.close(); throw error; }
        }
        @Override public Inspection inspectMigration(DatabaseConnectionTarget target, MigrationSchema schema, boolean source) throws Exception {
            Path path = ((Target) target).path;
            MigrationFiles.requirePlain(path, false);
            boolean empty = !Files.exists(path) || Files.size(path) == 0;
            if (source) Archive.read(path, schema);
            return new Inspection(empty, Set.of());
        }
        @Override public void verifyImported(DatabaseConnectionTarget target, MigrationSnapshot snapshot, MigrationControl control) throws Exception {
            if (!Archive.read(((Target) target).path, snapshot.schema()).verify(control).equals(snapshot.verification()))
                throw new IllegalStateException("DATA_MISMATCH");
        }
        @Override public DatabaseImportSession importDatabase(DatabaseConnectionTarget target, MigrationSchema schema,
                                                               MigrationControl control) throws Exception {
            var path = ((Target) target).path;
            Files.createDirectories(path.getParent());
            var channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                var lock = channel.tryLock();
                if (lock == null || Files.exists(path) && Files.size(path) != 0) throw new IllegalStateException("TARGET_NOT_EMPTY");
                var data = new Archive(schema);
                return new DatabaseImportSession() {
                    private boolean verified, finished;
                    @Override public void write(String table, MigrationRows input) throws Exception {
                        if (verified || finished || data.rows.containsKey(table)) throw new IllegalStateException("FIXTURE_STATE");
                        var spec = schema.table(table);
                        List<List<Object>> records = new ArrayList<>();
                        while (input.next()) {
                            control.checkpoint(table, records.size());
                            var row = input.values();
                            if (row.size() != spec.columns().size() || records.size() >= 10000) throw new IllegalStateException("FIXTURE_LIMIT");
                            List<Object> copy = new ArrayList<>();
                            for (int i = 0; i < row.size(); i++) copy.add(MigrationValues.canonical(row.get(i), spec.columns().get(i).type()));
                            records.add(copy);
                        }
                        data.rows.put(table, records);
                    }
                    @Override public MigrationVerification verify(MigrationSnapshot snapshot) throws Exception {
                        if (finished || !snapshot.schema().equals(schema)) throw new IllegalStateException("FIXTURE_STATE");
                        data.sequences.putAll(snapshot.verification().sequences());
                        var actual = data.verify(control);
                        if (!actual.equals(snapshot.verification())) throw new IllegalStateException("DATA_MISMATCH");
                        verified = true;
                        return actual;
                    }
                    @Override public void commit() throws Exception {
                        if (!verified || finished) throw new IllegalStateException("FIXTURE_STATE");
                        data.save(path);
                        try (var output = FileChannel.open(path, StandardOpenOption.WRITE)) { output.force(true); }
                        finished = true;
                    }
                    @Override public void abort() { if (!finished) { data.rows.clear(); finished = true; } }
                    @Override public void close() throws Exception { if (!finished) abort(); channel.close(); }
                };
            } catch (Exception error) { channel.close(); throw error; }
        }
        @Override public void verifySource(DatabaseConnectionTarget source, String revision) throws Exception {
            if (!revision.equals("ledger-v1:" + MigrationFiles.digest(((Target) source).path)))
                throw new IllegalStateException("MIGRATION_SOURCE_CHANGED");
        }
    }
    private record Target(Path path) implements DatabaseConnectionTarget {
        @Override public void optimize(Connection connection) throws SQLException {
            throw new SQLException("RUNTIME_STORAGE_UNSUPPORTED", "0A000");
        }
        @Override public SchemaApplyResult applySchema(Connection connection, MigrationSchema schema, boolean strict) throws SQLException {
            throw new SQLException("RUNTIME_STORAGE_UNSUPPORTED", "0A000");
        }
        @Override public top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema physicalSchema(
                top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.DatabaseSchema schema) {
            throw new UnsupportedOperationException("RUNTIME_STORAGE_UNSUPPORTED");
        }
        @Override public java.util.Map<String, top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.PhysicalDatabaseSchema.TableSpec>
                readSchema(Connection connection) throws SQLException {
            throw new SQLException("RUNTIME_STORAGE_UNSUPPORTED", "0A000");
        }
        @Override public void initializeStorage(Connection connection, MigrationSchema schema) throws SQLException {
            throw new SQLException("RUNTIME_STORAGE_UNSUPPORTED", "0A000");
        }
        @Override public top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession storageSession(
                Connection connection, MigrationSchema schema) throws SQLException {
            throw new SQLException("RUNTIME_STORAGE_UNSUPPORTED", "0A000");
        }
        private Target { path = path.toAbsolutePath().normalize(); }
        @Override public String description() { return "ledger:" + path; }
        @Override public Optional<Path> localFile() { return Optional.of(path); }
        @Override public Connection openConnection(OpenMode mode) throws SQLException { throw new SQLException("JDBC_UNSUPPORTED", "0A000"); }
        @Override public void checkConnection() throws SQLException { if (!Files.isRegularFile(path)) throw new SQLException("FILE_MISSING", "08001"); }
        @Override public void requireConnectionTarget(Connection connection) throws SQLException { throw new SQLException("JDBC_UNSUPPORTED", "0A000"); }
        @Override public void prepareTransaction(Connection connection, boolean readOnly) throws SQLException { throw new SQLException("JDBC_UNSUPPORTED", "0A000"); }
        @Override public DatabaseConnectionTarget privateStorage(DatabasePrivateStorageSettings settings) { return new Target(settings.directory().resolve("facts.ledger")); }
    }
    private static MigrationRows cursor(List<List<Object>> records) {
        return new MigrationRows() {
            private int position = -1;
            @Override public boolean next() { return ++position < records.size(); }
            @Override public List<Object> values() { return records.get(position); }
            @Override public void close() { position = records.size(); }
        };
    }
    private static final class Archive {
        private final MigrationSchema schema;
        private final Map<String, List<List<Object>>> rows = new TreeMap<>();
        private final Map<String, Long> sequences = new TreeMap<>();
        private Archive(MigrationSchema schema) { this.schema = schema; }
        private MigrationVerification verify(MigrationControl control) throws Exception {
            Map<String, MigrationVerification.TableDigest> digests = new TreeMap<>();
            for (var table : schema.tables()) {
                var records = Objects.requireNonNull(rows.get(table.name()));
                digests.put(table.name(), MigrationValues.digest(cursor(records), control, table.name()));
                for (int index = 0; index < table.columns().size(); index++) {
                    var column = table.columns().get(index);
                    if (MigrationSchema.primaryKey(table).size() == 1 && column.primaryKeyPosition() == 1
                            && column.type().equals("INTEGER") && column.defaultValue() == null) {
                        long maximum = 0;
                        for (var row : records) maximum = Math.max(maximum, (Long) row.get(index));
                        if (!sequences.containsKey(table.name()) || sequences.get(table.name()) < maximum)
                            throw new IllegalStateException("SEQUENCE_MISMATCH");
                    }
                }
            }
            Map<String, Long> warnings = new TreeMap<>();
            for (var ref : schema.references()) {
                int sourceIndex = column(ref.table(), ref.column()), targetIndex = column(ref.targetTable(), ref.targetColumn());
                Set<Object> keys = new HashSet<>();
                rows.get(ref.targetTable()).forEach(row -> keys.add(row.get(targetIndex)));
                long missing = 0;
                for (var row : rows.get(ref.table())) {
                    Object key = row.get(sourceIndex);
                    if (key == null) continue;
                    if (ref.encoding() == MigrationSchema.Encoding.BRACED_PREFIX) {
                        String path = (String) key;
                        if (!path.startsWith("{")) continue;
                        try { key = Long.parseLong(path.substring(1, path.indexOf('}'))); }
                        catch (RuntimeException invalid) { key = -1L; }
                        if (key.equals(0L)) continue;
                    }
                    if (!keys.contains(key)) missing++;
                }
                if (missing > 0) warnings.put(ref.key(), missing);
            }
            return new MigrationVerification(digests, sequences, warnings);
        }
        private int column(String table, String name) {
            var columns = schema.table(table).columns();
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).name().equals(name)) return i;
            throw new IllegalArgumentException("COLUMN_MISSING");
        }
        private void save(Path path) throws Exception {
            try (var out = new DataOutputStream(Files.newOutputStream(path))) {
                out.writeInt(1); out.writeUTF(schema.tables().toString());
                for (var table : schema.tables()) {
                    out.writeLong(sequences.getOrDefault(table.name(), -1L));
                    out.writeInt(rows.get(table.name()).size());
                    for (var row : rows.get(table.name())) for (Object value : row) {
                        if (value == null) out.writeByte(0);
                        else if (value instanceof Long number) { out.writeByte(1); out.writeLong(number); }
                        else if (value instanceof Double number) { out.writeByte(2); out.writeDouble(number); }
                        else if (value instanceof String text) { out.writeByte(3); out.writeUTF(text); }
                        else if (value instanceof byte[] bytes) { out.writeByte(4); out.writeInt(bytes.length); out.write(bytes); }
                        else throw new IllegalStateException("VALUE_TYPE");
                    }
                }
            }
        }
        private static Archive read(Path path, MigrationSchema schema) throws Exception {
            if (Files.size(path) > 16 * 1024 * 1024) throw new IllegalStateException("FIXTURE_LIMIT");
            var archive = new Archive(schema);
            try (var input = new DataInputStream(Files.newInputStream(path))) {
                if (input.readInt() != 1 || !input.readUTF().equals(schema.tables().toString())) throw new IllegalStateException("FIXTURE_FORMAT");
                for (var table : schema.tables()) {
                    long high = input.readLong(); if (high >= 0) archive.sequences.put(table.name(), high);
                    int count = input.readInt();
                    if (count < 0 || count > 10000) throw new IllegalStateException("FIXTURE_LIMIT");
                    List<List<Object>> rows = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        List<Object> row = new ArrayList<>();
                        for (var column : table.columns()) row.add(MigrationValues.canonical(switch (input.readByte()) {
                            case 0 -> null; case 1 -> input.readLong(); case 2 -> input.readDouble(); case 3 -> input.readUTF();
                            case 4 -> { int length = input.readInt(); if (length < 0 || length > 65536) throw new IOException("FIXTURE_LIMIT");
                                byte[] bytes = new byte[length]; input.readFully(bytes); yield bytes; }
                            default -> throw new IOException("FIXTURE_FORMAT");
                        }, column.type()));
                        rows.add(row);
                    }
                    archive.rows.put(table.name(), rows);
                }
                if (input.read() != -1) throw new IOException("FIXTURE_FORMAT");
            }
            return archive;
        }
    }
}
