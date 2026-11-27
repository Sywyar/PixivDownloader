package top.sywyar.pixivdownload.transfer;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.StringDataType;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Precommit check across all external page rows, including different works and different prefix IDs. */
final class RestoredPagePaths implements AutoCloseable {
    private final Path indexFile;
    private final MVStore store;
    private final MVMap<String, String> pages;
    private final Map<Long, String> sourceRoots;
    private final Map<Long, String> targetRoots;
    private final UnaryOperator<String> sourcePrefix;

    RestoredPagePaths(Path file, Map<Long, String> sourceRoots, Map<Long, String> targetRoots) throws IOException {
        indexFile = file;
        this.sourceRoots = sourceRoots;
        sourcePrefix = PathPrefixCodec.archivePrefixLabel(sourceRoots);
        this.targetRoots = targetRoots;
        MigrationFiles.createPrivateFile(file);
        store = new MVStore.Builder().fileName(file.toString()).open();
        pages = store.openMap("pages", new MVMap.Builder<String, String>()
                .keyType(StringDataType.INSTANCE).valueType(StringDataType.INSTANCE));
    }

    void check(TableSpec table, java.util.List<Object> original, java.util.List<Object> mapped, String identity) {
        int file = column(table, "file_path");
        String source = PathPrefixCodec.archiveSpelling((String) original.get(file), sourceRoots);
        Path target = Path.of(PathPrefixCodec.archiveSpelling((String) mapped.get(file), targetRoots));
        if (!target.isAbsolute()) return; // An unresolved historical prefix has no target location.
        String type = switch (String.valueOf(original.get(column(table, "work_type")))) {
            case "ARTWORK" -> "ARTWORK";
            case "NOVEL" -> "NOVEL";
            default -> "WORK";
        };
        String page = type + " " + ((Number) original.get(column(table, "work_id"))).longValue()
                + " page " + ((Number) original.get(column(table, "page"))).longValue();
        int rootColumn = column(table, "root_path");
        Path root = Path.of(PathPrefixCodec.archiveSpelling((String) mapped.get(rootColumn), targetRoots));
        if (root.isAbsolute() && !restorablePage(root, target))
            throw new Conflict("transfer.database-page-outside-root", page, "root_path "
                    + sourcePrefix.apply((String) original.get(rootColumn)) + ", file_path "
                    + sourcePrefix.apply((String) original.get(file)));
        String other = "";
        try {
            // Resolve a selected symlink/.. by the filesystem; only missing descendants retain their spelling.
            Path parent = target.getParent();
            if (parent != null) target = physicalParent(parent).resolve(target.getFileName());
            String spelling = target.toString();
            // Include length-changing Unicode folds in the candidate set. Only the filesystem decides equality.
            StringBuilder folded = new StringBuilder();
            spelling.codePoints().map(Character::toUpperCase).map(Character::toLowerCase).forEach(folded::appendCodePoint);
            String bucket = Normalizer.normalize(folded.toString().toUpperCase(Locale.ROOT), Normalizer.Form.NFD) + '\0';
            String key = bucket + spelling;
            for (var it = pages.keyIterator(bucket); it.hasNext();) {
                String previous = it.next();
                if (!previous.startsWith(bucket)) break;
                String[] stored = pages.get(previous).split("\u0000", 3);
                if (stored[0].equals(source) || !identity.isEmpty() && identity.equals(stored[1])) continue;
                other = stored[2];
                if (sameDestination(Path.of(previous.substring(bucket.length())), target))
                    throw new Conflict("transfer.database-path-collision", other, page);
            }
            pages.putIfAbsent(key, source + '\0' + identity + '\0' + page);
        } catch (IOException inaccessible) {
            throw new Conflict("transfer.database-path-unverified", other, page);
        }
    }

    private static int column(TableSpec table, String name) {
        for (int i = 0; i < table.columns().size(); i++) if (table.columns().get(i).name().equals(name)) return i;
        throw new IllegalArgumentException("transfer.database-schema-mismatch");
    }

    private static boolean restorablePage(Path root, Path file) {
        try {
            Path candidate = ExternalWorkFiles.containedPage(root, file);
            if (candidate == null) return false;
            if (exists(candidate)) return PlainFilePathGuard.isPlainRegularFile(candidate);
            Path parent = candidate.getParent();
            return parent.getParent() == null || PlainFilePathGuard.requireDirectoryOpenPrecondition(parent).rejectedNode() == null;
        } catch (NoSuchFileException missing) {
            // Both references can share the same offline root containing a symlink/.. selection.
            if (!file.startsWith(root) || file.getNameCount() <= root.getNameCount()) return false;
            for (Path part : file.subpath(root.getNameCount(), file.getNameCount()))
                if (part.toString().equals("..")) return false;
            return true;
        } catch (IOException inaccessible) {
            return false;
        }
    }

    private static Path physicalParent(Path parent) {
        Path existing = parent;
        for (;;) {
            try {
                Path real = existing.toRealPath();
                return existing.getNameCount() == parent.getNameCount() ? real
                        : real.resolve(parent.subpath(existing.getNameCount(), parent.getNameCount()));
            } catch (NoSuchFileException missing) {
                if (existing.getParent() == null) return parent;
                existing = existing.getParent();
            } catch (IOException inaccessible) {
                // A single offline reference is valid. Only an ambiguous pair needs a directory case check.
                return parent;
            }
        }
    }

    private static boolean sameDestination(Path first, Path second) throws IOException {
        if (first.toString().equals(second.toString())) return true;
        if (!first.getRoot().equals(second.getRoot()) || first.getNameCount() != second.getNameCount()) return false;
        Path directory = first.getRoot();
        for (int i = 0; i < first.getNameCount(); i++) {
            String a = first.getName(i).toString(), b = second.getName(i).toString();
            if (!a.equals(b) && !sameEntry(directory, a, b)) return false;
            directory = directory.resolve(a);
        }
        return true;
    }

    private static boolean sameEntry(Path dir, String a, String b) throws IOException {
        boolean first = exists(dir.resolve(a)), second = exists(dir.resolve(b));
        if (first != second) return false;
        if (first) {
            // Windows Path.equals ignores case even for a case-sensitive directory. Compare directory entries.
            return !bothListed(dir, a, b) && Files.isSameFile(dir.resolve(a), dir.resolve(b));
        }
        while (dir != null && !exists(dir)) dir = dir.getParent();
        if (dir == null) throw new IOException("No accessible ancestor for case comparison");
        // Neither name exists yet. Test these two spellings in the same directory without occupying either name.
        if (a.chars().allMatch(c -> c < 128) && b.chars().allMatch(c -> c < 128) && a.equalsIgnoreCase(b)) {
            Path probe = Files.createTempFile(dir, ".pixiv-case-", ".tmp");
            try { return exists(dir.resolve(probe.getFileName().toString().toUpperCase(Locale.ROOT))); }
            finally { Files.delete(probe); }
        }
        // A private child inherits directory case rules and leaves the full component length available.
        Path scratch = Files.createTempDirectory(dir, ".pixiv-case-");
        try {
            Path probe = Files.createFile(scratch.resolve(a));
            try { return exists(scratch.resolve(b)); }
            finally { Files.delete(probe); }
        } finally { Files.delete(scratch); }
    }

    private static boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException absent) { return false; }
    }

    private static boolean bothListed(Path directory, String a, String b) throws IOException {
        boolean first = false, second = false;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                first |= name.equals(a);
                second |= name.equals(b);
                if (first && second) return true;
            }
        }
        return false;
    }

    static final class Conflict extends IllegalArgumentException {
        final String code;
        Conflict(String code, String first, String second) {
            super(first.isEmpty() ? second : first + " / " + second);
            this.code = code;
        }
    }

    @Override public void close() throws IOException {
        try { store.close(); }
        finally { Files.deleteIfExists(indexFile); }
    }
}
