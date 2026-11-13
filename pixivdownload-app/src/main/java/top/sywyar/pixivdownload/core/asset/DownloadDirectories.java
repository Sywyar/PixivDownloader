package top.sywyar.pixivdownload.core.asset;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 下载独占目录的持久归属；共享导入目录不写入此表。短归属事务串行，下载传输仍可并发。 */
@Component
public final class DownloadDirectories {
    private static final String TABLE = "download_directories";
    private final MainDatabase database;
    private final PathPrefixCodec paths;

    public DownloadDirectories(MainDatabase database, PathPrefixCodec paths, DatabaseInitializer initializer) {
        this.database = database;
        this.paths = paths;
    }

    /** 在创建任何作品文件前提交归属；失败下载保留归属供同一作品恢复。 */
    public synchronized void prepare(WorkType type, long id, Path directory) throws IOException {
        if (id <= 0) throw rejected("invalid-variable");
        Path target = canonical(directory);
        database.transaction(session -> {
            boolean[] exact = {false};
            scanClaims(session, target, type, id, exact);
            scanAssets(session, target, type, id, exact);
            if (!exact[0] && Files.isDirectory(target)) {
                try (var entries = Files.newDirectoryStream(target)) {
                    if (entries.iterator().hasNext()) throw rejected("occupied");
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
            if (!hasClaim(session, type, id, target)) {
                session.insert(TABLE, Map.of("claim_id", UUID.randomUUID().toString(),
                        "work_type", type.name(), "work_id", id, "directory", paths.encode(target.toString())),
                        DatabaseSession.Conflict.error());
            }
            return null;
        });
        PlainFilePathGuard.requirePlainParent(target.resolve(".directory-check"), true);
        if (!PlainFilePathGuard.isPlainDirectory(target)) throw rejected("unsafe-path");
    }

    /** 自定义小说目录仅在存在持久下载归属、且没有其它资产重叠时允许递归操作。 */
    public synchronized boolean owns(WorkType type, long id, Path directory) {
        Path target;
        try { target = canonical(directory); }
        catch (IOException | DirectoryTemplateException failure) { return false; }
        return database.read(session -> {
            if (!hasClaim(session, type, id, target)) return false;
            try {
                scanClaims(session, target, type, id, new boolean[1]);
                scanAssets(session, target, type, id, new boolean[1]);
                return true;
            } catch (DirectoryTemplateException conflict) { return false; }
        });
    }

    /** 调用方在同一监视器内完成只读引用登记，避免登记与下载归属检查之间交错。 */
    public synchronized void requireImportable(Collection<Path> files) {
        database.read(session -> {
            session.scan(query(TABLE, Map.of("directory", column(TABLE, "directory")), null), row -> {
                Path claimed = resolved(row.get("directory"));
                for (Path file : files) if (contains(claimed, file.toAbsolutePath().normalize()))
                    throw rejected("occupied");
                return true;
            });
            return null;
        });
    }

    private void scanClaims(DatabaseSession session, Path target, WorkType type, long id, boolean[] exact)
            throws java.sql.SQLException {
        session.scan(query(TABLE, Map.of("directory", column(TABLE, "directory"),
                "type", column(TABLE, "work_type"), "id", column(TABLE, "work_id")), null), row -> {
            check(target, resolved(row.get("directory")), type.name().equals(row.get("type"))
                    && id == ((Number) row.get("id")).longValue(), exact);
            return true;
        });
    }

    private boolean hasClaim(DatabaseSession session, WorkType type, long id, Path target) throws java.sql.SQLException {
        boolean[] found = {false};
        session.scan(query(TABLE, Map.of("directory", column(TABLE, "directory")),
                call(AND, call(EQUAL, column(TABLE, "work_type"), value(type.name())),
                        call(EQUAL, column(TABLE, "work_id"), value(id)))), row -> {
            found[0] |= same(target, resolved(row.get("directory")));
            return !found[0];
        });
        return found[0];
    }

    private void scanAssets(DatabaseSession session, Path target, WorkType type, long id, boolean[] exact)
            throws java.sql.SQLException {
        for (WorkType candidate : WorkType.values()) {
            String table = candidate == WorkType.ARTWORK ? "artworks" : "novels";
            String key = candidate == WorkType.ARTWORK ? "artwork_id" : "novel_id";
            String external = "external_work_files";
            Expression imported = new Exists(new Query(external, Map.of("found", value(1)),
                    call(AND, call(EQUAL, column(external, "work_type"), value(candidate.name())),
                            call(EQUAL, column(external, "work_id"), column(table, key)),
                            call(EQUAL, column(external, "record_time"), column(table, "time"))),
                    List.of(), List.of(), 1, 0));
            var selected = new LinkedHashMap<String, Expression>();
            selected.put("id", column(table, key));
            selected.put("folder", column(table, "folder"));
            if (candidate == WorkType.ARTWORK) selected.put("move_folder", column(table, "move_folder"));
            session.scan(query(table, selected, call(NOT, imported)), row -> {
                boolean own = candidate == type && id == ((Number) row.get("id")).longValue();
                for (String field : List.of("folder", "move_folder")) {
                    Object stored = row.get(field);
                    if (stored instanceof String value && !value.isBlank())
                        check(target, resolved(value), own, exact);
                }
                return true;
            });
        }
        session.scan(query("external_work_files", Map.of("file", column("external_work_files", "file_path")), null), row -> {
            Path file = resolved(row.get("file"));
            if (contains(target, file)) throw rejected("occupied");
            return true;
        });
    }

    private static Query query(String table, Map<String, Expression> columns, Expression where) {
        // scan 逐行消费，不把整个作品库复制进内存。
        return new Query(table, columns, where, List.of(), List.of(), Integer.MAX_VALUE, 0);
    }

    private static void check(Path target, Path existing, boolean own, boolean[] exact) {
        if (same(target, existing) && own) { exact[0] = true; return; }
        if (contains(target, existing) || contains(existing, target)) throw rejected("occupied");
    }

    private Path resolved(Object value) {
        Path absolute = Path.of(paths.resolve((String) value)).toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        try { return existing == null ? absolute : existing.toRealPath().resolve(existing.relativize(absolute)).normalize(); }
        catch (IOException inaccessible) { return absolute; }
    }

    private static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getParent() == null || PlainFilePathGuard.requireDirectoryOpenPrecondition(absolute).rejected())
            throw rejected("unsafe-path");
        Path existing = absolute;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }

    private static boolean same(Path left, Path right) { return left.equals(right); }
    private static boolean contains(Path parent, Path path) { return path.startsWith(parent); }
    private static DirectoryTemplateException rejected(String reason) { return new DirectoryTemplateException(reason, ""); }
}
