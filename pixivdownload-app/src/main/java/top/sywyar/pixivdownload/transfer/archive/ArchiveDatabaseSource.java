package top.sywyar.pixivdownload.transfer.archive;

import top.sywyar.pixivdownload.migration.database.MigrationSchemaJson;
import top.sywyar.pixivdownload.migration.database.MigrationSource;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 私有暂存中的中性数据库快照；归档地址和凭据不参与目标连接解析。 */
public final class ArchiveDatabaseSource implements MigrationSource {
    private static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = MigrationSchemaJson.mapper();
    private final Metadata metadata;
    private final Map<String, Path> files;
    private final String revision;

    public record Metadata(int version, MigrationSchema schema, MigrationVerification verification, List<String> rebuildObjects) {
        public Metadata {
            if (version != 1 || schema == null || verification == null || schema.tables().size() > 19_999
                    || !verification.tables().keySet().equals(schema.tables().stream().map(table -> table.name()).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalArgumentException("transfer.database-invalid");
            rebuildObjects = List.copyOf(rebuildObjects);
            rebuildObjects.forEach(MigrationSchema::identifier);
        }
    }

    public ArchiveDatabaseSource(Path directory) throws IOException {
        this(localFiles(directory));
    }
    public ArchiveDatabaseSource(Map<String, Path> files) throws IOException {
        this.files = Map.copyOf(files);
        if (!files.containsKey("metadata.json") || files.size() > 20_000) throw new IOException("transfer.database-invalid");
        Path file = metadataFile();
        PlainFilePathGuard.requirePlainRegularFile(file);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
            if (bytes.length > MAX_METADATA_BYTES) throw new IOException("transfer.database-limit");
            metadata = JSON.readValue(bytes, Metadata.class);
        }
        if (metadata == null) throw new IOException("transfer.database-invalid");
        Set<String> expected = new HashSet<>();
        expected.add("metadata.json");
        for (int i = 0; i < metadata.schema().tables().size(); i++) expected.add(i + ".rows");
        if (!files.keySet().equals(expected)) throw new IOException("transfer.database-invalid");
        revision = digest();
    }

    /** 调用方已停写并持有所有源会话；本方法只发布全新私有快照目录。 */
    public static ArchiveDatabaseSource write(Path directory, DatabaseExportSession session, MigrationControl control) throws Exception {
        return write(directory, session, control, (table, row) -> row, new Budget(TransferArchive.MAX_CONTENT_BYTES));
    }

    /** 只规范化非身份列；同时校验原始摘要，并为实际归档值重新计算摘要。 */
    public static ArchiveDatabaseSource write(Path directory, DatabaseExportSession session, MigrationControl control,
            java.util.function.BiFunction<top.sywyar.pixivdownload.plugin.api.schema.TableSpec, List<Object>, List<Object>> normalize,
            Budget budget) throws Exception {
        PlainFilePathGuard.requirePlainParent(directory, false);
        Files.createDirectory(directory);
        MigrationFiles.makePrivate(directory);
        var snapshot = session.snapshot();
        Map<String, MigrationVerification.TableDigest> tables = new LinkedHashMap<>();
        for (int i = 0; i < snapshot.schema().tables().size(); i++) {
            var table = snapshot.schema().tables().get(i);
            Path file = directory.resolve(i + ".rows");
            MigrationFiles.createPrivateFile(file);
            try (var rows = session.rows(table.name());
                 var output = new BufferedOutputStream(budget.wrap(Files.newOutputStream(file, LinkOption.NOFOLLOW_LINKS)))) {
                tables.put(table.name(), DatabaseRowsCodec.write(output, table, rows,
                        snapshot.verification().tables().get(table.name()), control, normalize));
            }
            force(file);
        }
        var metadata = new Metadata(1, snapshot.schema(), new MigrationVerification(tables,
                snapshot.verification().sequences(), snapshot.verification().referenceWarnings()), snapshot.rebuildObjects());
        byte[] bytes = JSON.writeValueAsBytes(metadata);
        if (bytes.length > MAX_METADATA_BYTES) throw new IOException("transfer.database-limit");
        Path manifest = directory.resolve("metadata.json");
        MigrationFiles.createPrivateFile(manifest);
        try (var output = budget.wrap(Files.newOutputStream(manifest, LinkOption.NOFOLLOW_LINKS))) { output.write(bytes); }
        force(manifest);
        var result = new ArchiveDatabaseSource(directory);
        result.verifyRows(control);
        return result;
    }

    /** 同一次快照所有库共用字节预算，写入前计费，不等待最终打包才发现超限。 */
    public static final class Budget {
        private long remaining;
        public Budget(long maximum) {
            if (maximum < 1 || maximum > TransferArchive.MAX_CONTENT_BYTES) throw new IllegalArgumentException("transfer.database-limit");
            remaining = maximum;
        }
        private OutputStream wrap(OutputStream output) {
            return new FilterOutputStream(output) {
                @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    Objects.checkFromIndexSize(offset, length, bytes.length);
                    reserve(length); out.write(bytes, offset, length);
                }
            };
        }
        private void reserve(int count) throws IOException {
            if (count > remaining) throw new IOException("transfer.database-limit");
            remaining -= count;
        }
    }

    public Metadata metadata() { return metadata; }
    public List<TransferArchive.Source> archiveEntries(String owner) {
        List<TransferArchive.Source> entries = new ArrayList<>();
        entries.add(entry(owner, "metadata.json"));
        for (int i = 0; i < metadata.schema().tables().size(); i++) entries.add(entry(owner, i + ".rows"));
        return List.copyOf(entries);
    }
    private TransferArchive.Source entry(String owner, String name) {
        return new TransferArchive.Source(new TransferArchive.Descriptor(owner, TransferArchive.Category.DATABASE, name, 1),
                () -> { Path file = files.get(name); PlainFilePathGuard.requirePlainRegularFile(file);
                    return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS); });
    }

    @Override public String owner() { return "archive"; }
    @Override public String description() { return "archive:" + revision; }
    @Override public DatabaseMigrationProvider.Inspection inspect(MigrationSchema schema) throws IOException {
        requireSchema(schema);
        return new DatabaseMigrationProvider.Inspection(metadata.verification().tables().values().stream().allMatch(table -> table.rows() == 0),
                Set.copyOf(metadata.rebuildObjects()));
    }
    @Override public DatabaseExportSession export(MigrationRequest request, MigrationControl control) throws Exception {
        requireSchema(request.schema());
        verifyRevision(revision);
        return new DatabaseExportSession() {
            private MigrationRows active;
            private boolean closed;
            @Override public MigrationSnapshot snapshot() {
                return new MigrationSnapshot(1, metadata.schema(), metadata.verification(), metadataFile(), revision, metadata.rebuildObjects());
            }
            @Override public MigrationRows rows(String name) throws Exception {
                if (closed || active != null) throw new IOException("transfer.database-cursor-busy");
                int index = -1;
                for (int i = 0; i < metadata.schema().tables().size(); i++) if (metadata.schema().tables().get(i).name().equals(name)) index = i;
                if (index < 0) throw new IOException("transfer.database-unknown-table");
                Path file = files.get(index + ".rows");
                PlainFilePathGuard.requirePlainRegularFile(file);
                var input = new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS));
                MigrationRows cursor;
                try { cursor = DatabaseRowsCodec.read(input, metadata.schema().tables().get(index), metadata.verification().tables().get(name), control); }
                catch (Exception failure) { try { input.close(); } catch (IOException close) { failure.addSuppressed(close); } throw failure; }
                active = new MigrationRows() {
                    private boolean cursorClosed;
                    public boolean next() throws Exception { return cursor.next(); }
                    public List<Object> values() { return cursor.values(); }
                    public void close() throws Exception {
                        if (cursorClosed) return;
                        cursorClosed = true;
                        try { cursor.close(); } finally { if (active == this) active = null; }
                    }
                };
                return active;
            }
            @Override public void close() throws Exception { closed = true; if (active != null) active.close(); }
        };
    }
    public void verifyRows(MigrationControl control) throws Exception {
        try (var session = export(new MigrationRequest(metadata.schema(), metadataFile(), Set.copyOf(metadata.rebuildObjects())), control)) {
            for (var table : metadata.schema().tables()) try (var rows = session.rows(table.name())) {
                while (rows.next()) { /* 游标验证类型、行数和逐表摘要。 */ }
            }
        }
    }
    @Override public void verifyRevision(String expected) throws IOException {
        if (!revision.equals(expected) || !revision.equals(digest())) throw new IOException("transfer.archive-changed");
    }
    private String digest() throws IOException {
        var hash = MigrationValues.newDigest();
        MigrationValues.hash(hash, MigrationFiles.digest(metadataFile()));
        for (int i = 0; i < metadata.schema().tables().size(); i++) {
            Path file = files.get(i + ".rows");
            PlainFilePathGuard.requirePlainRegularFile(file);
            MigrationValues.hash(hash, MigrationFiles.digest(file));
        }
        return MigrationValues.finish(hash);
    }
    private void requireSchema(MigrationSchema schema) throws IOException {
        if (!metadata.schema().equals(schema)) throw new IOException("transfer.database-schema-mismatch");
    }
    private Path metadataFile() { return files.get("metadata.json"); }
    private static Map<String, Path> localFiles(Path directory) throws IOException {
        MigrationFiles.requirePlain(directory, true);
        Map<String, Path> files = new LinkedHashMap<>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path file : entries) {
                if (files.size() >= 20_000) throw new IOException("transfer.database-limit");
                files.put(file.getFileName().toString(), file.toAbsolutePath().normalize());
            }
        }
        return files;
    }
    private static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
    }
}
