package top.sywyar.pixivdownload.core.db.pathprefix;

import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.hash.ImageHashMapper;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** Invalidates indexed pages and missing markers using the same effective source as the gallery. */
final class ArtworkHashSourcePaths {
    private static final String HASHES = "artwork_image_hashes";
    private static final String ART = "artworks";
    private static final String REFS = "external_work_files";

    static void invalidate(MainDatabase database, UnaryOperator<String> before, UnaryOperator<String> after) {
        database.transaction(session -> {
            if (session.query(new Query(HASHES, Map.of("found", value(1)), null, List.of(), List.of(), 1, 0)).isEmpty())
                return null;
            var id = column(ART, "artwork_id");
            var time = column(ART, "time");
            var indexed = new Exists(new Query(HASHES, Map.of("found", value(1)), call(EQUAL, column(HASHES, "artwork_id"), id),
                    List.of(), List.of(), 1, 0));
            var managed = call(IS_NULL, ExternalWorkFiles.referenceMarker(WorkType.ARTWORK, id, time));
            session.scan(new Query(ART, Map.of("id", id, "folder", column(ART, "folder"), "moved", column(ART, "move_folder")),
                    call(AND, indexed, managed), List.of(), List.of(), Integer.MAX_VALUE, 0), row -> {
                String moved = (String) row.get("moved");
                String stored = ArtworkRecord.usesMoveFolder(moved) ? moved : (String) row.get("folder");
                if (!sameLocation(before.apply(stored), after.apply(stored))) ImageHashMapper.deleteByArtwork(session, ((Number) row.get("id")).longValue());
                return true;
            });
            var current = new Query.Join(Query.JoinKind.INNER, new Query.Table(ART), call(AND,
                    call(EQUAL, column(REFS, "work_id"), id), call(EQUAL, column(REFS, "record_time"), time)));
            session.scan(new Query(new Query.Table(REFS), List.of(current), Map.of("id", id,
                    "root", column(REFS, "root_path"), "file", column(REFS, "file_path")),
                    call(AND, indexed, call(EQUAL, column(REFS, "work_type"), value(WorkType.ARTWORK.name()))),
                    List.of(), null, List.of(), false, Integer.MAX_VALUE, 0), row -> {
                String root = (String) row.get("root"), file = (String) row.get("file");
                String oldRoot = before.apply(root), newRoot = after.apply(root);
                String oldFile = before.apply(file), newFile = after.apply(file);
                if (Objects.equals(oldRoot, newRoot) && Objects.equals(oldFile, newFile)) return true;
                if (!sameLocation(oldFile, newFile) || !sameLocation(readable(oldRoot, oldFile), readable(newRoot, newFile))) {
                    ImageHashMapper.deleteByArtwork(session, ((Number) row.get("id")).longValue());
                }
                return true;
            });
            return null;
        });
    }

    private static String readable(String root, String file) {
        try {
            Path page = ExternalWorkFiles.readableFile(Path.of(root).toAbsolutePath(), Path.of(file).toAbsolutePath());
            return page == null ? null : page.toString();
        } catch (InvalidPathException unusable) {
            return null;
        }
    }

    private static boolean sameLocation(String left, String right) {
        if (Objects.equals(left, right)) return true;
        if (left == null || right == null) return false;
        try {
            Path a = Path.of(left).toAbsolutePath(), b = Path.of(right).toAbsolutePath();
            // A missing page or directory can still name the same location through two aliases to its parent.
            while (a.getParent() != null && b.getParent() != null && a.getFileName().equals(b.getFileName())
                    && Files.notExists(a, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.notExists(b, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                a = a.getParent();
                b = b.getParent();
            }
            return Files.isSameFile(a, b);
        } catch (IOException | InvalidPathException unavailable) {
            return false;
        }
    }
}
