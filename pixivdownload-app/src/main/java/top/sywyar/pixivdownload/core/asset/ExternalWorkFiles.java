package top.sywyar.pixivdownload.core.asset;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImportRequest;
import top.sywyar.pixivdownload.core.work.importing.LocalWorkFileImportRequest;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 外部作品的只读文件引用。删除画廊记录后保留引用作为文件所有权标记。 */
@Repository
public class ExternalWorkFiles {
    private static final String TABLE = "external_work_files";
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private final MainDatabase database;
    private final PathPrefixCodec paths;
    public ExternalWorkFiles(MainDatabase database, PathPrefixCodec paths, DatabaseInitializer initializer) {
        this.database = database;
        this.paths = paths;
    }
    private static Expression identity(WorkType type, Expression id) {
        return call(AND, call(EQUAL, column(TABLE, "work_type"), value(type.name())),
                call(EQUAL, column(TABLE, "work_id"), id));
    }
    private static Query recordTime(WorkType type, long id) {
        String table = type == WorkType.ARTWORK ? "artworks" : "novels";
        String key = type == WorkType.ARTWORK ? "artwork_id" : "novel_id";
        return new Query(table, Map.of("time", column(table, "time")), call(EQUAL, column(table, key), value(id)),
                List.of(), List.of(), 1, 0);
    }
    private static Expression currentRecord(WorkType type, long id) {
        return matchesRecord(type, value(id), new Scalar(recordTime(type, id)));
    }
    private static Expression matchesRecord(WorkType type, Expression id, Expression time) {
        return call(AND, identity(type, id), call(EQUAL, column(TABLE, "record_time"), time));
    }
    /** 当前登记可匹配外部引用时返回 1，否则为 NULL。 */
    public static Expression referenceMarker(WorkType type, Expression id, Expression time) {
        return new Scalar(new Query(TABLE, Map.of("found", value(1)), matchesRecord(type, id, time),
                List.of(), List.of(), 1, 0));
    }
    public boolean contains(WorkType type, long id) {
        return !database.read(s -> s.query(new Query(TABLE, Map.of("found", value(1)), currentRecord(type, id),
                List.of(), List.of(), 1, 0))).isEmpty();
    }
    private List<Path> paths(Expression where, int limit) {
        return database.read(s -> s.query(new Query(TABLE, Map.of("root_path", column(TABLE, "root_path"),
                "file_path", column(TABLE, "file_path")), where, List.of(),
                List.of(new Query.Order(column(TABLE, "page"), false, true)), limit, 0))).stream().map(row -> {
            Path root = Path.of(paths.resolve((String) row.get("root_path"))).toAbsolutePath();
            Path file = Path.of(paths.resolve((String) row.get("file_path"))).toAbsolutePath();
            return readableFile(root, file);
        }).toList();
    }

    /** Existing gallery source, including root containment and the plain-file requirement. */
    public static Path readableFile(Path root, Path file) {
        try {
            Path candidate = containedPage(root, file);
            return candidate != null && PlainFilePathGuard.isPlainRegularFile(candidate) ? candidate : null;
        } catch (IOException unavailable) {
            return null;
        }
    }

    /** Shared by the gallery and restore preflight. The page need not have been copied yet. */
    public static Path containedPage(Path root, Path file) throws IOException {
        Path directory = resolveParents(root);
        Path candidate = resolveParents(file);
        return candidate.startsWith(directory) && candidate.getNameCount() > directory.getNameCount() ? candidate : null;
    }

    /** A mapped root and its page file may use different prefixes to reach the same directory. */
    static Path resolveParents(Path path) throws IOException {
        if (path.equals(path.normalize())) return path;
        Path current = path.getRoot();
        for (Path part : path) {
            if (part.toString().equals("..")) {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    PlainFilePathGuard.requirePlainParent(current, false);
                    current = current.toRealPath();
                    if (!Files.isDirectory(current)) throw new IOException("Expected a directory");
                } else if (!attributes.isDirectory() || attributes.isOther()) {
                    throw new IOException("Expected a plain directory");
                }
                if (current.getParent() != null) current = current.getParent();
            } else if (!part.toString().equals(".")) {
                current = current.resolve(part);
            }
        }
        return current;
    }
    public List<Path> files(WorkType type, long id) { return paths(currentRecord(type, id), Integer.MAX_VALUE); }
    public Path file(WorkType type, long id, int page) {
        if (page < 0) return null;
        var matches = paths(call(AND, currentRecord(type, id), call(EQUAL, column(TABLE, "page"), value(page))), 1);
        return matches.isEmpty() ? null : matches.get(0);
    }
    public void save(WorkFileImportRequest request) {
        save(new LocalWorkFileImportRequest(request.workType(), request.workId(), request.sourceRoot(), request.pageFiles()));
    }
    public void save(LocalWorkFileImportRequest request) {
        database.transaction(s -> {
            s.delete(TABLE, identity(request.workType(), value(request.workId())));
            var records = s.query(recordTime(request.workType(), request.workId()));
            if (records.isEmpty()) throw new org.springframework.dao.EmptyResultDataAccessException(1);
            Object time = records.get(0).get("time");
            for (int page = 0; page < request.pageFiles().size(); page++) {
                Path file = request.sourceRoot().resolve(request.pageFiles().get(page)).toAbsolutePath().normalize();
                s.insert(TABLE, Map.of("reference_id", request.workType().name() + ":" + request.workId() + ":" + page,
                        "work_type", request.workType().name(), "work_id", request.workId(), "page", page,
                        "root_path", paths.encode(request.sourceRoot().toString()), "file_path", paths.encode(file.toString()),
                        "record_time", time), DatabaseSession.Conflict.error());
            }
            return null;
        });
    }

    /** 读取登记事实时保留失效路径，不能用过滤后的文件列表覆盖数据库。 */
    public List<Reference> references(WorkType type, long id) {
        return database.read(s -> s.query(new Query(TABLE, Map.of(
                "root_path", column(TABLE, "root_path"), "file_path", column(TABLE, "file_path"),
                "page", column(TABLE, "page"), "source_page", column(TABLE, "source_page"),
                "parsed_metadata", column(TABLE, "parsed_metadata")), currentRecord(type, id), List.of(),
                List.of(new Query.Order(column(TABLE, "page"), false, true)), 1001, 0))).stream().map(row -> {
            String metadata = (String) row.get("parsed_metadata");
            Number sourcePage = (Number) row.get(metadata == null ? "page" : "source_page");
            return new Reference(Path.of(paths.resolve((String) row.get("root_path"))),
                    Path.of(paths.resolve((String) row.get("file_path"))),
                    sourcePage == null ? null : sourcePage.intValue(), metadata);
        }).toList();
    }

    public record Reference(Path root, Path file, Integer sourcePage, String parsedMetadata) {}

    /** Per-work identities for scan and commit. Missing or unsafe references keep their stored spelling. */
    public static final class FileIdentities {
        private final Map<Object, Path> keyed = new HashMap<>();
        private record FileStamp(long size, java.nio.file.attribute.FileTime modified) {}
        private final Map<FileStamp, List<Path>> unkeyed = new HashMap<>();

        public Path identify(Path root, Path file) {
            Path readable = readableFile(root.toAbsolutePath(), file.toAbsolutePath());
            if (readable == null) return file;
            try {
                var attrs = Files.readAttributes(readable, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.fileKey() != null) return keyed.computeIfAbsent(attrs.fileKey(), k -> readable);
                // Some providers omit fileKey; compare only candidates with matching file attributes.
                var candidates = unkeyed.computeIfAbsent(new FileStamp(attrs.size(), attrs.lastModifiedTime()), k -> new ArrayList<>());
                for (Path previous : candidates) {
                    if (previous.equals(readable)) return previous;
                    try {
                        if (Files.isSameFile(previous, readable)) return previous;
                    } catch (IOException unavailable) {
                        // A previous candidate may have disappeared since the scan.
                    }
                }
                candidates.add(readable);
                return readable;
            } catch (IOException unavailable) {
                return file;
            }
        }
    }

    /** 调用方持有作品锁和登记事务；只合并外部引用，不覆盖作品元数据。 */
    public boolean merge(LocalWorkFileImportRequest request) {
        var existing = references(request.workType(), request.workId());
        var identities = new FileIdentities();
        var known = new HashMap<Path, Reference>();
        for (Reference ref : existing) known.put(identities.identify(ref.root(), ref.file()), ref);
        List<Reference> additions = new ArrayList<>();
        for (int i = 0; i < request.pageFiles().size(); i++) {
            Path path = request.file(i);
            var source = request.sources().get(i);
            Path identity = identities.identify(source.root(), path);
            var previous = known.get(identity);
            if (previous != null) {
                if (source.page() != null && previous.sourcePage() != null && !source.page().equals(previous.sourcePage()))
                    throw new IllegalArgumentException("IMPORT_PAGE_CONFLICT");
                continue;
            }
            var ref = new Reference(source.root(), path, source.page(), JSON.writeValueAsString(source.hints()));
            additions.add(ref);
            known.put(identity, ref);
        }
        if (existing.size() + additions.size() > 1000) throw new IllegalArgumentException("IMPORT_FILE_LIMIT");
        List<Reference> ordered = new ArrayList<>(existing);
        additions.sort(Comparator.comparing(Reference::sourcePage, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ref -> ref.file().toString()));
        ordered.addAll(additions);
        Set<Integer> pages = new HashSet<>();
        for (var ref : ordered) {
            if (ref.sourcePage() != null && !pages.add(ref.sourcePage())) throw new IllegalArgumentException("IMPORT_PAGE_CONFLICT");
        }
        if (additions.isEmpty()) return false;
        database.transaction(s -> {
            var records = s.query(recordTime(request.workType(), request.workId()));
            if (records.isEmpty()) throw new org.springframework.dao.EmptyResultDataAccessException(1);
            Object time = records.getFirst().get("time");
            s.delete(TABLE, call(AND, identity(request.workType(), value(request.workId())),
                    call(NOT_EQUAL, column(TABLE, "record_time"), value(time))));
            Set<String> extensions = new TreeSet<>();
            for (int page = 0; page < ordered.size(); page++) {
                Reference ref = ordered.get(page);
                String name = ref.file().getFileName().toString();
                extensions.add(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
                if (page < existing.size()) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("reference_id", request.workType().name() + ":" + request.workId() + ":" + page);
                row.put("work_type", request.workType().name()); row.put("work_id", request.workId()); row.put("page", page);
                row.put("root_path", paths.encode(ref.root().toString())); row.put("file_path", paths.encode(ref.file().toString()));
                row.put("record_time", time); row.put("source_page", ref.sourcePage()); row.put("parsed_metadata", ref.parsedMetadata());
                s.insert(TABLE, row, DatabaseSession.Conflict.error());
            }
            s.update("artworks", Map.of("count", value(ordered.size()), "extensions", value(String.join(",", extensions))),
                    call(EQUAL, column("artworks", "artwork_id"), value(request.workId())));
            return null;
        });
        return true;
    }

    /** 解析导入的本地数量不作为来源站点总页数；有可靠快照后才检查来源页覆盖。 */
    public boolean hasCompletePageIdentity(long id) {
        List<Reference> references = references(WorkType.ARTWORK, id);
        if (references.stream().noneMatch(ref -> ref.parsedMetadata() != null)) return true;
        var rows = database.read(s -> s.query(new Query("artworks", Map.of("metadata_json", column("artworks", "metadata_json")),
                call(EQUAL, column("artworks", "artwork_id"), value(id)), List.of(), List.of(), 1, 0)));
        if (rows.isEmpty() || !(rows.getFirst().get("metadata_json") instanceof String json)) return false;
        try {
            var document = new WorkMetaCurator(JSON).restore(WorkType.ARTWORK, id, JSON.readTree(json)).document();
            int count = document.path("raw").path("pageCount").asInt(0);
            if (count <= 0 || count != references.size()) return false;
            Set<Integer> pages = new HashSet<>();
            for (var ref : references) if (ref.sourcePage() == null || ref.sourcePage() >= count || !pages.add(ref.sourcePage())) return false;
            return true;
        } catch (RuntimeException invalid) { return false; }
    }
}
