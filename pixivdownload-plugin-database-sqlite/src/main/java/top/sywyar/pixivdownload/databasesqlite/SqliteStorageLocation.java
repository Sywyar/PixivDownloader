package top.sywyar.pixivdownload.databasesqlite;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** SQLite 主文件和 WAL 属于同一搬迁单元；检查入口只定位、不创建。 */
final class SqliteStorageLocation {
    private static final String NAME = "pixiv_download.db";
    private static final List<String> SUFFIXES = List.of("-wal", "-shm", "");
    private SqliteStorageLocation() {}
    static Path inspect(DatabaseConnectionSettings settings) {
        Path target = settings.dataDirectory().resolve(NAME);
        if (Files.exists(target)) return target;
        return settings.legacyDirectories().stream().map(p -> p.resolve(NAME))
                .filter(Files::exists).findFirst().orElse(target);
    }
    static void prepare(DatabaseConnectionSettings settings) throws IOException {
        if (!settings.jdbcUrl().isBlank()) return;
        Path target = settings.dataDirectory().resolve(NAME);
        MigrationFiles.requirePlain(target, false);
        for (String suffix : SUFFIXES) MigrationFiles.requirePlain(Path.of(target + suffix), false);
        // 已有受管主库仍是权威位置；历史目录中不同内容的旧库保留，不能阻断正常重开。
        if (Files.exists(target)) return;
        var sources = settings.legacyDirectories().stream().map(p -> p.resolve(NAME))
                .filter(p -> !p.equals(target) && Files.exists(p, LinkOption.NOFOLLOW_LINKS)).toList();
        if (sources.isEmpty()) return;
        if (!Files.exists(target) && SUFFIXES.stream().anyMatch(s -> Files.exists(Path.of(target + s))))
            throw new IOException("DATABASE_LEGACY_CONFLICT");
        Path reference = Files.exists(target) ? target : sources.get(0);
        for (Path source : sources) {
            for (String suffix : SUFFIXES) MigrationFiles.requirePlain(Path.of(source + suffix), false);
            if (!matches(source, reference)) throw new IOException("DATABASE_LEGACY_CONFLICT");
        }
        for (Path directory : settings.legacyDirectories()) {
            Path source = directory.resolve(NAME);
            if (source.equals(target) || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
            for (String suffix : SUFFIXES) {
                MigrationFiles.requirePlain(Path.of(source + suffix), false);
                MigrationFiles.requirePlain(Path.of(target + suffix), false);
            }
            if (Files.exists(target)) {
                if (!matches(source, target)) throw new IOException("DATABASE_LEGACY_CONFLICT");
            } else {
                if (SUFFIXES.stream().anyMatch(s -> Files.exists(Path.of(target + s))))
                    throw new IOException("DATABASE_LEGACY_CONFLICT");
                Files.createDirectories(target.getParent());
                List<Path> copied = new ArrayList<>();
                try {
                    for (String suffix : SUFFIXES) {
                        Path from = Path.of(source + suffix), to = Path.of(target + suffix);
                        if (Files.exists(from)) {
                            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
                            copied.add(to);
                        }
                    }
                    if (!matches(source, target)) throw new IOException("DATABASE_LEGACY_VERIFICATION_FAILED");
                    for (Path path : copied) try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                } catch (IOException failure) {
                    for (int i = copied.size() - 1; i >= 0; i--) {
                        try { Files.deleteIfExists(copied.get(i)); }
                        catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                    }
                    throw failure;
                }
            }
            // 只有整个单元逐字节一致后才清理旧位置，主文件先删除，避免误开残缺源库。
            Files.delete(source);
            for (String suffix : List.of("-wal", "-shm")) Files.deleteIfExists(Path.of(source + suffix));
        }
    }
    private static boolean matches(Path source, Path target) throws IOException {
        for (String suffix : SUFFIXES) {
            Path a = Path.of(source + suffix), b = Path.of(target + suffix);
            if (Files.exists(a) != Files.exists(b) || Files.exists(a) && Files.mismatch(a, b) != -1) return false;
        }
        return true;
    }
}
