package top.sywyar.pixivdownload.transfer;

import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.transfer.archive.ArchiveDatabaseSource;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** One optional source identity per external page, in the same primary-key order as the archived rows. */
final class SourcePageIdentities {
    static final String ENTRY = "page-identities.bin";
    static final String TABLE = "external_work_files";
    private static final byte[] UNKNOWN = new byte[32];

    static TransferArchive.Source write(Path run, ArchiveDatabaseSource source, Map<Long, String> roots,
            ArchiveDatabaseSource.Budget budget, MigrationControl control) throws Exception {
        var table = source.metadata().schema().tables().stream().filter(t -> TABLE.equals(t.name())).findFirst().orElseThrow();
        var names = table.columns().stream().map(c -> c.name()).toList();
        int root = names.indexOf("root_path"), file = names.indexOf("file_path");
        var digest = MigrationValues.newDigest();
        Path output = run.resolve(ENTRY);
        MigrationFiles.createPrivateFile(output);
        try (var rows = source.openArchivedRows(TABLE, control);
             var out = new BufferedOutputStream(budget.wrap(Files.newOutputStream(output, LinkOption.NOFOLLOW_LINKS)))) {
            while (rows.next()) {
                var row = rows.values();
                String identity = identity(PathPrefixCodec.archiveSpelling((String) row.get(root), roots),
                        PathPrefixCodec.archiveSpelling((String) row.get(file), roots));
                out.write(identity == null ? UNKNOWN : digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return new TransferArchive.Source(new TransferArchive.Descriptor("host", TransferArchive.Category.DATABASE, ENTRY, 1),
                () -> Files.newInputStream(output, LinkOption.NOFOLLOW_LINKS));
    }

    private static String identity(String root, String file) {
        try {
            Path directory = Path.of(root), candidate = Path.of(file);
            if (!directory.isAbsolute() || !candidate.isAbsolute()) return null;
            Path page = ExternalWorkFiles.containedPage(directory, candidate);
            if (page == null || !PlainFilePathGuard.isPlainRegularFile(page)) return null;
            Object key = Files.readAttributes(page, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            // toRealPath returns the stored name on case-insensitive systems without a file key.
            return key == null ? "path:" + page.toRealPath() : "file:" + key;
        } catch (IOException | InvalidPathException offline) {
            return null;
        }
    }

    static String next(DataInputStream input) {
        if (input == null) return "";
        byte[] identity = new byte[UNKNOWN.length];
        try { input.readFully(identity); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        return Arrays.equals(identity, UNKNOWN) ? "" : HexFormat.of().formatHex(identity);
    }

    static boolean validSize(long size, long rows) {
        return size % UNKNOWN.length == 0 && size / UNKNOWN.length == rows;
    }
}
