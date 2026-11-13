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
import java.nio.file.Path;
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
    private static Expression identity(WorkType type, long id) {
        return call(AND, call(EQUAL, column(TABLE, "work_type"), value(type.name())),
                call(EQUAL, column(TABLE, "work_id"), value(id)));
    }
    private static Query recordTime(WorkType type, long id) {
        String table = type == WorkType.ARTWORK ? "artworks" : "novels";
        String key = type == WorkType.ARTWORK ? "artwork_id" : "novel_id";
        return new Query(table, Map.of("time", column(table, "time")), call(EQUAL, column(table, key), value(id)),
                List.of(), List.of(), 1, 0);
    }
    private static Expression currentRecord(WorkType type, long id) {
        return call(AND, identity(type, id), call(EQUAL, column(TABLE, "record_time"), new Scalar(recordTime(type, id))));
    }
    public boolean contains(WorkType type, long id) {
        return !database.read(s -> s.query(new Query(TABLE, Map.of("found", value(1)), currentRecord(type, id),
                List.of(), List.of(), 1, 0))).isEmpty();
    }
    private List<Path> paths(Expression where, int limit) {
        return database.read(s -> s.query(new Query(TABLE, Map.of("root_path", column(TABLE, "root_path"),
                "file_path", column(TABLE, "file_path")), where, List.of(),
                List.of(new Query.Order(column(TABLE, "page"), false, true)), limit, 0))).stream().map(row -> {
            Path root = Path.of(paths.resolve((String) row.get("root_path"))).toAbsolutePath().normalize();
            Path file = Path.of(paths.resolve((String) row.get("file_path"))).toAbsolutePath().normalize();
            return file.startsWith(root) && PlainFilePathGuard.isPlainRegularFile(file) ? file : null;
        }).toList();
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
            s.delete(TABLE, identity(request.workType(), request.workId()));
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

    /** 调用方持有作品锁和登记事务；只合并外部引用，不覆盖作品元数据。 */
    public boolean merge(LocalWorkFileImportRequest request) {
        var combined = new LinkedHashMap<Path, Reference>();
        for (Reference ref : references(request.workType(), request.workId())) combined.put(ref.file(), ref);
        int before = combined.size();
        for (int i = 0; i < request.pageFiles().size(); i++) {
            Path path = request.file(i);
            var source = request.sources().get(i);
            var previous = combined.get(path);
            if (previous != null) {
                if (source.page() != null && previous.sourcePage() != null && !source.page().equals(previous.sourcePage()))
                    throw new IllegalArgumentException("IMPORT_PAGE_CONFLICT");
                continue;
            }
            combined.put(path, new Reference(source.root().normalize(), path, source.page(), JSON.writeValueAsString(source.hints())));
        }
        if (combined.size() > 1000) throw new IllegalArgumentException("IMPORT_FILE_LIMIT");
        Set<Integer> pages = new HashSet<>();
        for (var ref : combined.values()) {
            if (ref.sourcePage() != null && !pages.add(ref.sourcePage())) throw new IllegalArgumentException("IMPORT_PAGE_CONFLICT");
        }
        if (before == combined.size()) return false;
        List<Reference> ordered = new ArrayList<>(combined.values().stream().limit(before).toList());
        // 已登记展示序号关联缩略图和逐页事实；补充时保持原序号，只排序新增文件。
        ordered.addAll(combined.values().stream().skip(before).sorted(Comparator
                .comparing(Reference::sourcePage, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ref -> ref.file().toString())).toList());
        database.transaction(s -> {
            var records = s.query(recordTime(request.workType(), request.workId()));
            if (records.isEmpty()) throw new org.springframework.dao.EmptyResultDataAccessException(1);
            Object time = records.getFirst().get("time");
            s.delete(TABLE, identity(request.workType(), request.workId()));
            Set<String> extensions = new TreeSet<>();
            for (int page = 0; page < ordered.size(); page++) {
                Reference ref = ordered.get(page);
                var row = new LinkedHashMap<String, Object>();
                row.put("reference_id", request.workType().name() + ":" + request.workId() + ":" + page);
                row.put("work_type", request.workType().name()); row.put("work_id", request.workId()); row.put("page", page);
                row.put("root_path", paths.encode(ref.root().toString())); row.put("file_path", paths.encode(ref.file().toString()));
                row.put("record_time", time); row.put("source_page", ref.sourcePage()); row.put("parsed_metadata", ref.parsedMetadata());
                s.insert(TABLE, row, DatabaseSession.Conflict.error());
                String name = ref.file().getFileName().toString();
                extensions.add(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
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
