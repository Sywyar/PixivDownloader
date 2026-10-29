package top.sywyar.pixivdownload.transfer.archive;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget.OpenMode.READ_WRITE;

@DisplayName("中性数据库归档的实际迁出与迁入")
class ArchiveDatabaseSourceTest {
    @TempDir Path root;

    @AfterEach
    void cleanDirectory() throws IOException {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(root);
    }

    private final MigrationControl control = (table, rows) -> {};
    private final MigrationSchema schema = new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
            new ColumnSpec("id", "INTEGER", false, null, 1, true),
            new ColumnSpec("body", "TEXT", false, null, 0), new ColumnSpec("score", "REAL", false, null, 0),
            new ColumnSpec("payload", "BLOB", false, null, 0), new ColumnSpec("optional", "TEXT", false, null, 0)), List.of())), List.of(), List.of());

    @Test @DisplayName("真实 SQLite 经加密归档和既有迁移编排恢复，保留大整数、文本、BLOB、NULL 与序列")
    void roundTripsProviderThroughEncryptedArchive() throws Exception {
        var from = DatabaseProviderTestFixtures.sqlite(root.resolve("source.db")).migrationEndpoint();
        try (var connection = from.target().openConnection(READ_WRITE)) {
            from.target().applySchema(connection, schema, true);
            try (var insert = connection.prepareStatement("INSERT INTO facts VALUES(?,?,?,?,NULL)")) {
                insert.setLong(1, 9007199254740993L); insert.setString(2, "你好 🌸\nline");
                insert.setDouble(3, -0.125); insert.setBytes(4, new byte[]{0, -1, 42}); insert.executeUpdate();
            }
            try (var sql = connection.createStatement()) {
                sql.execute("INSERT INTO facts(id) VALUES(9007199254741000)");
                sql.execute("DELETE FROM facts WHERE id=9007199254741000");
            }
        }
        var request = new MigrationRequest(schema, root.resolve("source.backup"), Set.of());
        ArchiveDatabaseSource archived;
        try (var session = from.export(request, control)) { archived = ArchiveDatabaseSource.write(root.resolve("database"), session, control); }
        var archive = new TransferArchive();
        Path bundle = root.resolve("database.pdbackup");
        char[] password = "fixture password".toCharArray();
        var preview = archive.write(bundle, password, "fixture", archived.archiveEntries("host"));
        Path extracted = root.resolve("extracted");
        archive.extract(bundle, password, extracted, preview);
        Map<String, Path> files = new LinkedHashMap<>();
        for (int i = 0; i < preview.items().size(); i++) files.put(preview.items().get(i).descriptor().path(), extracted.resolve(Integer.toString(i)));
        var restored = new ArchiveDatabaseSource(files);
        var to = DatabaseProviderTestFixtures.sqlite(root.resolve("target.db")).migrationEndpoint();
        var report = ProviderDatabaseMigration.migrate(List.of(new ProviderDatabaseMigration.Store("main", restored, to, request)),
                ignored -> {}, (store, snapshot) -> {}, control);
        assertThat(report.status()).isEqualTo("DATA_VERIFIED");
        try (var connection = to.target().openConnection(READ_WRITE); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT id,body,score,payload,optional FROM facts")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(9007199254740993L);
                assertThat(row.getString(2)).isEqualTo("你好 🌸\nline");
                assertThat(row.getDouble(3)).isEqualTo(-0.125);
                assertThat(row.getBytes(4)).containsExactly(0, -1, 42);
                assertThat(row.getString(5)).isNull();
                assertThat(row.next()).isFalse();
            }
            try (var row = sql.executeQuery("INSERT INTO facts(body) VALUES('next') RETURNING id")) {
                assertThat(row.next()).isTrue(); assertThat(row.getLong(1)).isEqualTo(9007199254741001L);
            }
        }
        String original = restored.description();
        Files.write(files.get("0.rows"), new byte[]{1}, StandardOpenOption.APPEND);
        assertThatThrownBy(() -> restored.verifyRevision(original.substring("archive:".length()))).hasMessage("transfer.archive-changed");
    }

    @Test @DisplayName("畸形类型、过大长度、截断、尾随内容和错误摘要在读取时拒绝")
    void rejectsMalformedRowsBeforeAcceptingThem() throws Exception {
        TableSpec table = new TableSpec("facts", List.of(new ColumnSpec("id", "TEXT", false, null, 1)), List.of());
        var hash = MigrationValues.newDigest(); MigrationValues.hash(hash, "ok");
        var expected = new MigrationVerification.TableDigest(1, MigrationValues.finish(hash));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x50445231); out.writeInt(1); out.writeLong(1); out.writeByte(4); out.writeInt(2); out.write(new byte[]{'o', 'k'});
        }
        byte[] valid = bytes.toByteArray();
        try (var rows = DatabaseRowsCodec.read(new ByteArrayInputStream(valid), table, expected, control)) {
            assertThat(rows.next()).isTrue(); assertThat(rows.values()).containsExactly("ok"); assertThat(rows.next()).isFalse();
        }
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(Arrays.copyOf(valid, valid.length - 1)); invalid.add(Arrays.copyOf(valid, valid.length + 1));
        byte[] badTag = valid.clone(); badTag[16] = 99; invalid.add(badTag);
        byte[] oversized = valid.clone(); java.nio.ByteBuffer.wrap(oversized).putInt(17, Integer.MAX_VALUE); invalid.add(oversized);
        byte[] utf8 = valid.clone(); utf8[21] = -128; invalid.add(utf8);
        byte[] changed = valid.clone(); changed[21] = 'x'; invalid.add(changed);
        for (byte[] input : invalid) assertThatThrownBy(() -> {
            try (var rows = DatabaseRowsCodec.read(new ByteArrayInputStream(input), table, expected, control)) { while (rows.next()) {} }
        }).isInstanceOf(IOException.class);
    }

    @Test @DisplayName("累计快照预算在写入时拒绝，单库成功不会重置其它库已消耗的额度")
    void sharesSnapshotBudgetAcrossStores() throws Exception {
        var source = DatabaseProviderTestFixtures.sqlite(root.resolve("source.db")).migrationEndpoint();
        try (var connection = source.target().openConnection(READ_WRITE)) { source.target().applySchema(connection, schema, true); }
        try (var session = source.export(new MigrationRequest(schema, root.resolve("backup"), Set.of()), control)) {
            assertThatThrownBy(() -> ArchiveDatabaseSource.write(root.resolve("too-small"), session, control,
                    (table, row) -> row, new ArchiveDatabaseSource.Budget(15))).hasMessage("transfer.database-limit");
        }
        try (var session = source.export(new MigrationRequest(schema, root.resolve("backup2"), Set.of()), control)) {
            var first = ArchiveDatabaseSource.write(root.resolve("baseline"), session, control);
            long size = 0;
            for (var entry : first.archiveEntries("host")) try (var input = entry.content().open()) { size += input.transferTo(OutputStream.nullOutputStream()); }
            var budget = new ArchiveDatabaseSource.Budget(size + 15);
            ArchiveDatabaseSource.write(root.resolve("first"), session, control, (table, row) -> row, budget);
            assertThatThrownBy(() -> ArchiveDatabaseSource.write(root.resolve("second"), session, control, (table, row) -> row, budget))
                    .hasMessage("transfer.database-limit");
        }
    }
}
