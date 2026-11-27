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
    private final Map<Path, Object> pendingNovelDirectories = new HashMap<>();
    private final Map<Path, Object> pendingDeletions = new HashMap<>();

    public DownloadDirectories(MainDatabase database, PathPrefixCodec paths, DatabaseInitializer initializer) {
        this.database = database;
        this.paths = paths;
    }

    /** 在创建任何作品文件前提交归属；失败下载保留归属供同一作品恢复。 */
    public synchronized void prepare(WorkType type, long id, Path directory) throws IOException {
        if (id <= 0) throw rejected("invalid-variable");
        Path target = canonical(directory);
        database.transaction(session -> {
            if (!checkOwnership(session, target, type, id) && Files.isDirectory(target)) {
                try (var entries = Files.newDirectoryStream(target)) {
                    if (entries.iterator().hasNext()) throw rejected("occupied");
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
            saveClaim(session, type, id, target);
            return null;
        });
        PlainFilePathGuard.requirePlainParent(target.resolve(".directory-check"), true);
        if (!PlainFilePathGuard.isPlainDirectory(target)) throw rejected("unsafe-path");
    }

    /** Recovery may adopt existing page files. The claim and page registration commit together. */
    public synchronized boolean recoverArtwork(long id, Path directory, java.util.function.BooleanSupplier registerPages) {
        Path target;
        try { target = canonical(directory); }
        catch (IOException invalid) { throw rejected("unsafe-path"); }
        return database.transaction(session -> {
            checkOwnership(session, target, WorkType.ARTWORK, id);
            if (!registerPages.getAsBoolean()) return false;
            saveClaim(session, WorkType.ARTWORK, id, target);
            return true;
        });
    }

    private boolean checkOwnership(DatabaseSession session, Path target, WorkType type, long id) throws java.sql.SQLException {
        requireDirectoryAvailable(target);
        boolean[] exact = {false};
        scanClaims(session, target, type, id, exact);
        scanAssets(session, target, type, id, exact);
        return exact[0];
    }

    private void saveClaim(DatabaseSession session, WorkType type, long id, Path target) throws java.sql.SQLException {
        if (!hasClaim(session, type, id, target)) {
            session.insert(TABLE, Map.of("claim_id", UUID.randomUUID().toString(),
                    "work_type", type.name(), "work_id", id, "directory", paths.encode(target.toString())),
                    DatabaseSession.Conflict.error());
        }
    }

    public synchronized boolean prepareIf(WorkType type, long id, Path directory,
                                          java.util.function.BooleanSupplier registerLayout) throws IOException {
        try {
            return database.transaction(session -> {
                if (!registerLayout.getAsBoolean()) return false;
                try {
                    prepare(type, id, directory);
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
                return true;
            });
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
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
        var sources = canonicalFiles(files);
        if (sources.stream().anyMatch(this::insidePendingDirectory) || !Collections.disjoint(pendingDeletions.keySet(), sources)) throw rejected("occupied");
        database.read(session -> {
            session.scan(query(TABLE, Map.of("directory", column(TABLE, "directory")), null), row -> {
                Path claimed = resolved(row.get("directory"));
                for (Path file : sources) if (contains(claimed, file))
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
            session.scan(managedRows(candidate, table, key, false), row -> {
                boolean own = candidate == type && id == ((Number) row.get("id")).longValue();
                for (String field : List.of("folder", "move_folder")) {
                    Object stored = row.get(field);
                    if (stored instanceof String value && !value.isBlank())
                        check(target, resolved(value), own, exact);
                }
                return true;
            });
        }
        if (hasReferencedFile(session, file -> contains(target, file))) throw rejected("occupied");
    }

    private static Query managedRows(WorkType candidate, String table, String key, boolean activeOnly) {
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
        Expression where = call(NOT, imported);
        if (activeOnly) where = call(AND, where, call(EQUAL, column(table, "deleted"), value(0)));
        return query(table, selected, where);
    }

    public boolean hasDirectoryClaim(WorkType type, long id, Path dir) {
        return database.read(session -> hasClaim(session, type, id, canonicalEntry(dir)));
    }

    /** Published selections use their saved layout. Completed managed artworks use their current folder. */
    public List<Long> otherArtworksInDirectory(long artworkId, Path directory, boolean partial) {
        Path target = canonicalEntry(directory);
        return database.read(session -> {
            List<Long> ids = new ArrayList<>();
            String layouts = "artwork_partial_downloads";
            Query candidates = partial ? query(layouts, Map.of("id", column(layouts, "artwork_id"),
                    "folder", column(layouts, "folder")), null) : managedRows(WorkType.ARTWORK, "artworks", "artwork_id", true);
            session.scan(candidates, row -> {
                long id = ((Number) row.get("id")).longValue();
                if (id != artworkId) {
                    String folder = effectiveFolder(row);
                    if (folder != null && !folder.isBlank() && target.equals(resolved(folder))) ids.add(id);
                }
                return true;
            });
            return ids;
        });
    }

    private static String effectiveFolder(Map<String, Object> row) {
        String moved = (String) row.get("move_folder");
        return top.sywyar.pixivdownload.core.db.ArtworkRecord.usesMoveFolder(moved) ? moved : (String) row.get("folder");
    }

    /** Returns roots to skip in the caller's spelling. An enclosing foreign root protects the entire novel. */
    public synchronized Set<Path> foreignNovelDirectories(long id, Path directory) {
        Path target = canonicalEntry(directory);
        return database.read(session -> {
            Set<Path> overlaps = new HashSet<>();
            java.util.function.Consumer<Path> collect = other -> {
                if (target.startsWith(other)) overlaps.add(directory);
                else if (other.startsWith(target)) overlaps.add(directory.resolve(target.relativize(other)));
            };
            for (WorkType type : WorkType.values()) {
                String table = type == WorkType.ARTWORK ? "artworks" : "novels";
                String key = type == WorkType.ARTWORK ? "artwork_id" : "novel_id";
                session.scan(managedRows(type, table, key, true), row -> {
                    if (type == WorkType.NOVEL && ((Number) row.get("id")).longValue() == id) return true;
                    String folder = effectiveFolder(row);
                    if (folder != null && !folder.isBlank()) collect.accept(resolved(folder));
                    return true;
                });
                // An unfinished download may have only a claim. Completed works use their current location.
                Expression registered = new Exists(query(table, Map.of("found", value(1)),
                        call(EQUAL, column(table, key), column(TABLE, "work_id"))));
                Expression claim = call(AND, call(EQUAL, column(TABLE, "work_type"), value(type.name())), call(NOT, registered));
                if (type == WorkType.NOVEL) claim = call(AND, claim, call(NOT, call(EQUAL, column(TABLE, "work_id"), value(id))));
                session.scan(query(TABLE, Map.of("directory", column(TABLE, "directory")), claim), row -> {
                    collect.accept(resolved(row.get("directory")));
                    return true;
                });
            }
            String partial = "artwork_partial_downloads";
            session.scan(query(partial, Map.of("folder", column(partial, "folder")), null), row -> {
                collect.accept(resolved(row.get("folder")));
                return true;
            });
            return overlaps;
        });
    }

    /** 在尝试写事务前判断是否需要解析并预检生效目录。 */
    public synchronized boolean hasDeletionReservations() {
        return !pendingNovelDirectories.isEmpty() || !pendingDeletions.isEmpty();
    }

    /** Short admission check. The caller holds this monitor until its path update commits. */
    public synchronized void requireDirectoryAvailable(Path directory) {
        Path target = canonicalEntry(directory);
        if (pendingNovelDirectories.keySet().stream().anyMatch(root -> root.startsWith(target) || target.startsWith(root))
                || pendingDeletions.keySet().stream().anyMatch(file -> file.startsWith(target) || target.startsWith(file))) throw rejected("occupied");
    }

    private boolean insidePendingDirectory(Path file) {
        return pendingNovelDirectories.keySet().stream().anyMatch(file::startsWith);
    }

    public synchronized DeletionReservation reserveNovelDeletion(long id, Path directory) {
        Path target = canonicalEntry(directory);
        requireDirectoryAvailable(target);
        if (!foreignNovelDirectories(id, target).isEmpty()
                || database.read(session -> hasReferencedFile(session, file -> file.startsWith(target)))) throw rejected("occupied");
        Object token = new Object();
        pendingNovelDirectories.put(target, token);
        return () -> {
            synchronized (this) { pendingNovelDirectories.remove(target, token); }
        };
    }

    /** Historical managed records may overlap a read-only import. Only exact deletion targets conflict. */
    public synchronized DeletionReservation reserveDeletion(Collection<Path> files) {
        Set<Path> targets = canonicalFiles(files);
        if (targets.stream().anyMatch(this::insidePendingDirectory) || !Collections.disjoint(pendingDeletions.keySet(), targets)
                || (!targets.isEmpty() && database.read(session -> hasReferencedFile(session, targets::contains)))) {
            throw rejected("occupied");
        }
        Object token = new Object();
        targets.forEach(path -> pendingDeletions.put(path, token));
        return () -> {
            synchronized (this) { targets.forEach(path -> pendingDeletions.remove(path, token)); }
        };
    }

    public interface DeletionReservation extends java.io.Closeable {
        @Override void close();
    }

    private static Set<Path> canonicalFiles(Collection<Path> files) {
        Set<Path> targets = new HashSet<>();
        for (Path file : files) targets.add(canonicalEntry(file));
        return targets;
    }

    /** Returns the caller's path spellings for files protected by a read-only reference. */
    public Set<Path> referencedFiles(Collection<Path> files) {
        if (files.isEmpty()) return Set.of();
        Map<Path, List<Path>> candidates = new HashMap<>();
        for (Path file : files) candidates.computeIfAbsent(canonicalEntry(file), ignored -> new ArrayList<>()).add(file);
        return database.read(session -> {
            Set<Path> protectedFiles = new HashSet<>();
            visitReferencedFiles(session, path -> {
                var matches = candidates.remove(path);
                if (matches != null) protectedFiles.addAll(matches);
                return !candidates.isEmpty();
            });
            return protectedFiles;
        });
    }

    private boolean hasReferencedFile(DatabaseSession session, java.util.function.Predicate<Path> matches)
            throws java.sql.SQLException {
        boolean[] found = {false};
        visitReferencedFiles(session, path -> {
            found[0] = matches.test(path);
            return !found[0];
        });
        return found[0];
    }

    private void visitReferencedFiles(DatabaseSession session, java.util.function.Predicate<Path> visit)
            throws java.sql.SQLException {
        session.scan(query("external_work_files", Map.of("file", column("external_work_files", "file_path")), null),
                row -> visit.test(resolved(row.get("file"))));
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
        return canonicalEntry(Path.of(paths.resolve((String) value)));
    }

    private static Path canonicalEntry(Path path) {
        Path absolute = path.toAbsolutePath();
        if (!absolute.equals(absolute.normalize())) {
            try { absolute = ExternalWorkFiles.resolveParents(absolute); }
            catch (IOException unreachable) { absolute = absolute.normalize(); }
        }
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
