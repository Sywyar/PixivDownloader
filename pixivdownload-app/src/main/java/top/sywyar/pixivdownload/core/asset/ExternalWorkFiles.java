package top.sywyar.pixivdownload.core.asset;

import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImportRequest;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import java.nio.file.Path;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 外部作品的只读文件引用。删除画廊记录后保留引用作为文件所有权标记。 */
@Repository
public class ExternalWorkFiles {
    private static final String TABLE = "external_work_files";
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
}
