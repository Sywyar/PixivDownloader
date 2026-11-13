package top.sywyar.pixivdownload.plugin.runtime.install.verify.cache;

import top.sywyar.pixivdownload.plugin.runtime.cache.PluginCacheAccess;
import static top.sywyar.pixivdownload.plugin.runtime.cache.PluginCacheAccess.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;

/** 独立的私有元数据缓存；认证密钥与缓存一起可丢弃，不参与插件来源信任。 */
final class ScanCacheFiles {
    static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    static final int MAX_ENTRIES = 512;
    static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private final Path root;
    private final PluginCacheAccess access;

    ScanCacheFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.access = new PluginCacheAccess(this.root);
    }

    byte[] read(String identity) throws IOException {
        return access.locked(key -> {
            Path file = root.resolve(identity + ".bin");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            access.requirePrivate(file, false);
            byte[] encoded = readBounded(file, MAX_ENTRY_BYTES);
            if (encoded.length < 32) throw new IOException("CACHE_AUTHENTICATION");
            byte[] payload = Arrays.copyOf(encoded, encoded.length - 32);
            if (!MessageDigest.isEqual(authenticate(key, payload), Arrays.copyOfRange(encoded, payload.length, encoded.length))) {
                throw new IOException("CACHE_AUTHENTICATION");
            }
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
            return payload;
        });
    }

    boolean write(String identity, byte[] payload) throws IOException {
        Boolean written = access.locked(key -> {
            if (payload.length > MAX_ENTRY_BYTES - 32) return false;
            Path destination = root.resolve(identity + ".bin");
            makeRoom(destination, payload.length + 32);
            Path temporary = Files.createTempFile(root, ".writing-", ".tmp");
            try {
                access.makePrivate(temporary, false);
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    var bytes = java.nio.ByteBuffer.wrap(payload);
                    while (bytes.hasRemaining()) channel.write(bytes);
                    bytes = java.nio.ByteBuffer.wrap(authenticate(key, payload));
                    while (bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } finally { Files.deleteIfExists(temporary); }
        });
        return Boolean.TRUE.equals(written);
    }

    private void makeRoom(Path destination, int incoming) throws IOException {
        var entries = new ArrayList<Path>();
        long total = 0;
        try (var paths = Files.newDirectoryStream(root)) {
            int inspected = 0;
            for (Path path : paths) {
                if (++inspected > MAX_ENTRIES + 64) throw new IOException("CACHE_DIRECTORY_CAPACITY");
                String name = path.getFileName().toString();
                if (name.startsWith(".writing-") && name.endsWith(".tmp")) {
                    access.requirePrivate(path, false); Files.delete(path);
                } else if (name.matches("[0-9a-f]{64}\\.bin") && !path.equals(destination)) {
                    requirePlain(path, false);
                    entries.add(path); total = Math.addExact(total, Files.size(path));
                }
            }
        }
        var ages = new HashMap<Path, FileTime>();
        for (Path entry : entries) ages.put(entry, Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS));
        entries.sort(Comparator.comparing(ages::get));
        int count = entries.size();
        for (Path entry : entries) {
            if (count < MAX_ENTRIES && total + incoming <= MAX_TOTAL_BYTES) break;
            access.requirePrivate(entry, false);
            long size = Files.size(entry); Files.delete(entry); total -= size; count--;
        }
    }

}
