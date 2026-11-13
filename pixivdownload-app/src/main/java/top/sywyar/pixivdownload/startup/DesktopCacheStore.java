package top.sywyar.pixivdownload.startup;

import top.sywyar.pixivdownload.common.PlainFilePathGuard;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 原子切换缓存指针；正在被 JVM 映射的旧文件可留到下一次发布后回收。 */
final class DesktopCacheStore {
    private static final int MANIFEST_BYTES = 168;
    private final Path root;

    DesktopCacheStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

    record Entry(Path cache, String key) {}

    Optional<Entry> find(String key) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Path manifest = root.resolve("desktop.identity");
        PlainFilePathGuard.requirePlainParent(manifest, false);
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        PlainFilePathGuard.requirePlainRegularFile(manifest);
        byte[] bytes;
        try (var input = Files.newInputStream(manifest, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MANIFEST_BYTES + 1);
        }
        if (bytes.length != MANIFEST_BYTES) return Optional.empty();
        String[] fields = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        if (fields.length != 4 || !fields[0].equals("1") || !fields[1].equals(key)
                || !fields[2].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !fields[3].matches("[0-9a-f]{64}"))
            return Optional.empty();
        Path cache = root.resolve("desktop-" + UUID.fromString(fields[2]) + ".aot");
        if (!Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)
                || !DistributionLauncher.hash(cache).equals(fields[3])) return Optional.empty();
        return Optional.of(new Entry(cache, key));
    }

    Lease acquire() throws IOException {
        PlainFilePathGuard.requirePlainParent(root.resolve("prepare.lock"), true);
        FileChannel channel = FileChannel.open(root.resolve("prepare.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) { channel.close(); return null; }
            return new Lease(channel, lock);
        } catch (OverlappingFileLockException busy) {
            channel.close();
            return null;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    Path candidate(UUID id) { return root.resolve("desktop-" + id + ".aot"); }

    void publish(UUID id, String key) throws IOException {
        Path cache = candidate(id);
        PlainFilePathGuard.requirePlainRegularFile(cache);
        Path temporary = root.resolve("desktop.identity.tmp");
        PlainFilePathGuard.requirePlainParent(temporary, false);
        Files.deleteIfExists(temporary);
        Files.writeString(temporary, "1\n" + key + "\n" + id + "\n" + DistributionLauncher.hash(cache),
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
        Files.move(temporary, root.resolve("desktop.identity"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        // Windows 上正在映射的旧文件无法删除；不影响已发布的新缓存。
        try (var entries = Files.newDirectoryStream(root, "desktop-*.aot")) {
            for (Path old : entries) {
                if (!old.equals(cache) && old.getFileName().toString()
                        .matches("desktop-[0-9a-f-]{36}\\.aot")) {
                    try { Files.deleteIfExists(old); }
                    catch (IOException inUse) { /* 下一次发布时再尝试回收。 */ }
                }
            }
        } catch (IOException cleanup) {
            System.err.println("AOT distribution: old cache cleanup deferred (" + cleanup.getClass().getSimpleName() + ")");
        }
    }

    record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException {
            try { lock.close(); } finally { channel.close(); }
        }
    }
}
