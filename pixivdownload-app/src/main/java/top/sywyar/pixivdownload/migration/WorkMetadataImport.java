package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefix;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.schema.CoreMigrationSchema;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;

import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 按数据库登记导入旧快照；源文件始终保留，已有快照不覆盖。 */
public final class WorkMetadataImport {
    // 旧文件可带缩进；紧凑文档仍由 curator 的 256 KiB 合同校验。
    public static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int PAGE_SIZE = 256;
    private static final ObjectMapper MAPPER = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final WorkMetaCurator CURATOR = new WorkMetaCurator(MAPPER);
    private static final top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema SCHEMA =
            CoreMigrationSchema.create(DatabaseSchemaRegistry.forBuiltInPlugins(), true);

    private WorkMetadataImport() {}

    public static Summary run(Connection connection, DatabaseTarget target, String rootFolder,
                              boolean dryRun, Consumer<String> reporter) throws SQLException, InterruptedException {
        return run(connection, target, rootFolder, dryRun, true, reporter);
    }

    public static Summary run(Connection connection, DatabaseTarget target, String rootFolder,
                              boolean dryRun, boolean projectArtworkColumns, Consumer<String> reporter)
            throws SQLException, InterruptedException {
        var codec = loadCodec(connection, target, rootFolder);
        int imported = 0, skipped = 0, failed = 0;
        for (WorkType type : List.of(WorkType.ARTWORK, WorkType.NOVEL)) {
            String table = type == WorkType.ARTWORK ? "artworks" : "novels";
            String key = type == WorkType.ARTWORK ? "artwork_id" : "novel_id";
            boolean projectColumns = type == WorkType.NOVEL || projectArtworkColumns;
            long last = 0;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Metadata import interrupted");
                List<Map<String, Object>> rows;
                try (var session = target.storageSession(connection, SCHEMA)) {
                    var fields = new LinkedHashMap<String, Expression>();
                    for (String field : List.of(key, "folder", "upload_time")) fields.put(field, column(table, field));
                    fields.put("has_metadata", call(NOT, call(IS_NULL, column(table, "metadata_json"))));
                    if (type == WorkType.ARTWORK) {
                        fields.put("is_original", column(table, "is_original"));
                        fields.put("moved", column(table, "moved"));
                        fields.put("move_folder", column(table, "move_folder"));
                    }
                    rows = session.query(new Query(table, fields,
                            call(AND, call(GREATER, column(table, key), value(last)),
                                    call(EQUAL, column(table, "deleted"), value(0))), List.of(),
                            List.of(new Query.Order(column(table, key), false, true)), PAGE_SIZE, 0));
                }
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Metadata import interrupted");
                    last = (Long) row.get(key);
                    String folder = (String) row.get("folder");
                    if (Long.valueOf(1).equals(row.get("moved"))) folder = (String) row.get("move_folder");
                    boolean existing = Long.valueOf(1).equals(row.get("has_metadata"));
                    String snapshot = null;
                    top.sywyar.pixivdownload.core.metadata.sidecar.CuratedWorkMeta meta;
                    try {
                        if (existing) {
                            if (!projectColumns || (row.get("upload_time") != null
                                    && (type == WorkType.NOVEL || row.get("is_original") != null))) {
                                skipped++;
                                continue;
                            }
                            // 单条读取快照，避免分页结果同时持有数百份 JSON。
                            try (var session = target.storageSession(connection, SCHEMA)) {
                                var current = session.query(new Query(table, Map.of("metadata_json", column(table, "metadata_json")),
                                        call(EQUAL, column(table, key), value(last)), List.of(), List.of(), 1, 0));
                                if (current.isEmpty() || current.get(0).get("metadata_json") == null) continue;
                                snapshot = (String) current.get(0).get("metadata_json");
                            }
                            meta = CURATOR.restore(type, last, MAPPER.reader()
                                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(snapshot));
                        } else {
                            if (folder == null || folder.isBlank()) continue;
                            Path file = Path.of(codec.resolve(folder)).resolve(last + ".meta.json");
                            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
                            meta = readSnapshot(file, type, last);
                        }
                    } catch (IOException | IllegalArgumentException e) {
                        failed++;
                        report(reporter, "metadata-import.invalid", type.name(), last);
                        continue;
                    }
                    var changes = new LinkedHashMap<String, Expression>();
                    if (!existing) changes.put("metadata_json", value(meta.document().toString()));
                    if (projectColumns && row.get("upload_time") == null && meta.uploadTime() != null)
                        changes.put("upload_time", call(COALESCE, column(table, "upload_time"), value(meta.uploadTime())));
                    if (projectColumns && type == WorkType.ARTWORK && row.get("is_original") == null && meta.isOriginal() != null)
                        changes.put("is_original", call(COALESCE, column(table, "is_original"), value(meta.isOriginal() ? 1 : 0)));
                    if (changes.isEmpty()) { skipped++; continue; }
                    if (dryRun) { imported++; continue; }
                    var transaction = target.beginToolTransaction(connection);
                    Throwable failure = null;
                    try {
                        int changed;
                        try (var session = target.storageSession(connection, SCHEMA)) {
                            changed = session.update(table, changes, call(AND,
                                    call(EQUAL, column(table, key), value(last)),
                                    call(EQUAL, column(table, "deleted"), value(0)),
                                    existing ? call(EQUAL, column(table, "metadata_json"), value(snapshot))
                                            : call(IS_NULL, column(table, "metadata_json"))));
                        }
                        transaction.commit();
                        if (changed > 0) imported++; else skipped++;
                    } catch (SQLException | RuntimeException | Error e) {
                        failure = e;
                        throw e;
                    } finally {
                        transaction.finish(failure);
                    }
                }
            }
        }
        report(reporter, "metadata-import.summary", imported, skipped, failed);
        return new Summary(imported, skipped, failed);
    }

    public static top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec loadCodec(
            Connection connection, DatabaseTarget target, String rootFolder) throws SQLException {
        List<PathPrefix> prefixes = new ArrayList<>();
        try (var session = target.storageSession(connection, SCHEMA)) {
            session.scan(new Query("path_prefixes", Map.of("id", column("path_prefixes", "id"),
                    "path", column("path_prefixes", "path")), null, List.of(), List.of(), Integer.MAX_VALUE, 0), row -> {
                prefixes.add(new PathPrefix((Long) row.get("id"), (String) row.get("path")));
                return true;
            });
        }
        return PathPrefixCodec.snapshot(rootFolder, prefixes);
    }

    public static top.sywyar.pixivdownload.core.metadata.sidecar.CuratedWorkMeta readRegisteredSnapshot(
            Connection connection, DatabaseTarget target, String rootFolder, long artworkId) throws SQLException, IOException {
        var codec = loadCodec(connection, target, rootFolder);
        try (var session = target.storageSession(connection, SCHEMA)) {
            var rows = session.query(new Query("artworks", Map.of("folder", column("artworks", "folder"),
                    "moved", column("artworks", "moved"), "move_folder", column("artworks", "move_folder")),
                    call(AND, call(EQUAL, column("artworks", "artwork_id"), value(artworkId)),
                            call(EQUAL, column("artworks", "deleted"), value(0))), List.of(), List.of(), 1, 0));
            if (rows.isEmpty()) return null;
            var row = rows.get(0);
            String folder = (String) row.get(Long.valueOf(1).equals(row.get("moved")) ? "move_folder" : "folder");
            if (folder == null || folder.isBlank()) return null;
            Path file = Path.of(codec.resolve(folder)).resolve(artworkId + ".meta.json");
            return Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? readSnapshot(file, WorkType.ARTWORK, artworkId) : null;
        }
    }

    private static top.sywyar.pixivdownload.core.metadata.sidecar.CuratedWorkMeta readSnapshot(
            Path file, WorkType type, long id) throws IOException {
        top.sywyar.pixivdownload.common.PlainFilePathGuard.requirePlainRegularFile(file);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) throw new IOException("METADATA_FILE_TOO_LARGE");
            return CURATOR.restore(type, id, MAPPER.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes));
        }
    }

    private static void report(Consumer<String> reporter, String key, Object... arguments) {
        if (reporter != null) reporter.accept(MessageBundles.getForLog(key, arguments));
    }

    public record Summary(int imported, int skipped, int failed) {}
}
