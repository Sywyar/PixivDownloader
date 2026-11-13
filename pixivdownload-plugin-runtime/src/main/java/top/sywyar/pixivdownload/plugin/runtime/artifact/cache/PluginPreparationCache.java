package top.sywyar.pixivdownload.plugin.runtime.artifact.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.artifact.*;
import top.sywyar.pixivdownload.plugin.runtime.cache.PluginCacheAccess;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;

/** 空闲准备树经认证后独占移入本代工作区；存活 generation 从不直接加载缓存路径。 */
public final class PluginPreparationCache {
    static final int MAX_ENTRIES = 512;
    static final long MAX_IMAGE_BYTES = 1024L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 2 * MAX_IMAGE_BYTES;
    private static final String MANIFEST = ".preparation-cache";
    private static final Logger log = LoggerFactory.getLogger(PluginPreparationCache.class);
    private final Path root;
    private final PluginCacheAccess access;
    private final PluginPackageLimits limits;
    private long hits, misses, returns, failures;

    public PluginPreparationCache(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        access = root == null ? null : new PluginCacheAccess(root);
        limits = PluginPackageLimits.defaults();
    }

    public synchronized String identity(Path input, boolean development) {
        if (access == null) return null;
        try { return identityOf(input, development, false); }
        catch (IOException | UncheckedIOException | SecurityException failure) { failed("fingerprint", failure); return null; }
    }

    public synchronized boolean matches(String identity, Path input, boolean developmentImage) {
        if (identity == null) return false;
        try { return identity.equals(identityOf(input, developmentImage, developmentImage)); }
        catch (IOException | UncheckedIOException | SecurityException failure) { failed("fingerprint", failure); return false; }
    }

    private String identityOf(Path input, boolean development, boolean image) throws IOException {
        var fingerprint = PreparationFingerprint.capture(input, image, image, null,
                development ? limits.maxTotalUncompressedBytes() : limits.maxArchiveBytes(), limits.maxEntries());
        var digest = PreparationFingerprint.digest();
        for (String part : List.of("plugin-preparation-v1", Rules.IDENTITY, PluginPackageVerifier.scanImplementationIdentity(),
                Runtime.version().toString(), System.getProperty("java.vendor", ""), System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                limits.toString(), development ? "development" : input.getFileName().toString(), fingerprint.sha256()))
            PreparationFingerprint.update(digest, part);
        if (Rules.IDENTITY.isEmpty()) throw new IOException("PREPARATION_IMPLEMENTATION_UNAVAILABLE");
        return HexFormat.of().formatHex(digest.digest());
    }

    /** destination 必须是本次新建的空私有目录；命中后文件身份保留，目录归当前 generation 独占。 */
    public synchronized Ticket take(String identity, Path destination) {
        if (identity == null || access == null) return null;
        try {
            Ticket ticket = access.locked(key -> {
                Path ready = root.resolve(identity);
                if (!Files.exists(ready, LinkOption.NOFOLLOW_LINKS)) return null;
                Ticket expected = readTicket(ready, key);
                if (!identity.equals(expected.identity()) || !expected.fingerprint().equals(capture(ready)))
                    throw new IOException("PREPARATION_CONTENT_CHANGED");
                // 原子移动保留源目录权限；新 owner marker 需要工作区根可写。
                access.makePrivate(ready, true);
                access.makePrivate(destination, true);
                Files.delete(ready.resolve(MANIFEST));
                Files.delete(destination);
                try { Files.move(ready, destination, StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException failure) {
                    Files.createDirectory(destination); access.makePrivate(destination, true); throw failure;
                }
                return expected;
            });
            if (ticket != null) hits++; else misses++;
            return ticket;
        } catch (IOException | UncheckedIOException | SecurityException failure) {
            failed("take", failure); misses++; return null;
        }
    }

    /** 首次准备完成时记住内容；释放时只接受与执行前相同的树。 */
    public synchronized Ticket remember(String identity, Path directory) {
        if (identity == null || access == null) return null;
        try {
            return access.locked(key -> {
                try (var walk = Files.walk(directory, 66)) {
                    var paths = walk.limit(PluginArtifactSnapshot.MAX_WORKSPACE_ENTRIES + 1L).toList();
                    if (paths.size() > PluginArtifactSnapshot.MAX_WORKSPACE_ENTRIES) throw new IOException("PREPARATION_ENTRY_LIMIT");
                    for (Path path : paths) {
                        boolean directoryEntry = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                        try { access.requirePrivate(path, directoryEntry); }
                        catch (IOException notPrivate) { access.makePrivate(path, directoryEntry); }
                    }
                }
                return new Ticket(identity, capture(directory));
            });
        } catch (IOException | UncheckedIOException | SecurityException failure) { failed("remember", failure); return null; }
    }

    /** 必须在 classloader / worker 完全释放之后调用；返回 false 时由原 owner 删除私有工作区。 */
    public synchronized boolean release(Ticket ticket, Path directory) {
        if (ticket == null || access == null) return false;
        try {
            Boolean moved = access.locked(key -> {
                if (!ticket.fingerprint().equals(capture(directory))) throw new IOException("PREPARATION_CONTENT_CHANGED");
                Path ready = root.resolve(ticket.identity());
                makeRoom(ready, ticket.fingerprint().bytes(), key);
                Path manifest = directory.resolve(MANIFEST);
                if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    access.requirePrivate(manifest, false); Files.delete(manifest);
                }
                byte[] payload = encode(ticket);
                try (var output = Files.newOutputStream(manifest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
                    output.write(payload); output.write(PluginCacheAccess.authenticate(key, payload));
                }
                access.makePrivate(manifest, false);
                Files.move(directory, ready, StandardCopyOption.ATOMIC_MOVE);
                Files.setLastModifiedTime(ready, FileTime.fromMillis(System.currentTimeMillis()));
                return true;
            });
            if (Boolean.TRUE.equals(moved)) { returns++; return true; }
            return false;
        } catch (IOException | UncheckedIOException | SecurityException failure) { failed("release", failure); return false; }
    }

    private PreparationFingerprint capture(Path directory) throws IOException {
        return PreparationFingerprint.capture(directory, false, true, access, MAX_IMAGE_BYTES,
                PluginArtifactSnapshot.MAX_WORKSPACE_ENTRIES - 4);
    }

    private Ticket readTicket(Path directory, byte[] key) throws IOException {
        access.requirePrivate(directory, true);
        Path manifest = directory.resolve(MANIFEST);
        access.requirePrivate(manifest, false);
        byte[] bytes = PluginCacheAccess.readBounded(manifest, 512);
        if (bytes.length < 32) throw new IOException("PREPARATION_AUTHENTICATION");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(PluginCacheAccess.authenticate(key, payload),
                Arrays.copyOfRange(bytes, payload.length, bytes.length))) throw new IOException("PREPARATION_AUTHENTICATION");
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String identity = input.readUTF(); String digest = input.readUTF(); long size = input.readLong();
            if (!identity.matches("[0-9a-f]{64}") || !digest.matches("[0-9a-f]{64}") || size < 0
                    || size > MAX_IMAGE_BYTES || input.read() != -1) throw new IOException("PREPARATION_MANIFEST");
            return new Ticket(identity, new PreparationFingerprint(digest, size));
        }
    }

    private byte[] encode(Ticket ticket) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF(ticket.identity()); output.writeUTF(ticket.fingerprint().sha256());
            output.writeLong(ticket.fingerprint().bytes());
        }
        return bytes.toByteArray();
    }

    private void makeRoom(Path replacement, long incoming, byte[] key) throws IOException {
        var entries = new ArrayList<Path>();
        var sizes = new HashMap<Path, Long>();
        var ages = new HashMap<Path, FileTime>();
        long total = 0;
        try (var stream = Files.newDirectoryStream(root)) {
            int inspected = 0;
            for (Path entry : stream) {
                if (++inspected > MAX_ENTRIES + 64) throw new IOException("PREPARATION_DIRECTORY_LIMIT");
                if (!entry.getFileName().toString().matches("[0-9a-f]{64}")) continue;
                access.requirePrivate(entry, true);
                if (entry.equals(replacement)) {
                    if (!PluginArtifactSnapshot.deleteWorkspaceQuietly(entry)) throw new IOException("PREPARATION_REPLACEMENT_FAILED");
                    continue;
                }
                long size;
                try { size = readTicket(entry, key).fingerprint().bytes(); }
                catch (IOException invalid) { size = MAX_IMAGE_BYTES; }
                entries.add(entry); sizes.put(entry, size); total += size;
                ages.put(entry, Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS));
            }
        }
        entries.sort(Comparator.comparing(ages::get));
        int count = entries.size();
        for (Path entry : entries) {
            if (count < MAX_ENTRIES && total + incoming <= MAX_TOTAL_BYTES) break;
            if (!PluginArtifactSnapshot.deleteWorkspaceQuietly(entry)) throw new IOException("PREPARATION_EVICTION_FAILED");
            total -= sizes.get(entry); count--;
        }
    }

    private void failed(String operation, Exception failure) {
        if (++failures == 1) {
            log.warn("Plugin preparation cache {} failed ({}); preparing private files again", operation, failure.getClass().getSimpleName());
            log.debug("Plugin preparation cache failure details", failure);
        }
    }

    public synchronized Statistics statistics() { return new Statistics(hits, misses, returns, failures); }
    public record Statistics(long hits, long misses, long returns, long failures) { }
    public record Ticket(String identity, PreparationFingerprint fingerprint) { }

    private static final class Rules {
        private static final String IDENTITY = identity();
        private static String identity() {
            try {
                var digest = PreparationFingerprint.digest();
                for (Class<?> type : List.of(PluginPreparationCache.class, PreparationFingerprint.class, PluginCacheAccess.class,
                        PluginArtifactSnapshot.class, PluginArtifactMaterializer.class, PluginDevelopmentArtifacts.class,
                        PluginPackageReader.class)) fingerprint(type, digest);
                return HexFormat.of().formatHex(digest.digest());
            } catch (IOException failure) { return ""; }
        }
        private static void fingerprint(Class<?> type, MessageDigest digest) throws IOException {
            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (input == null) throw new IOException("PREPARATION_IMPLEMENTATION_UNAVAILABLE");
                digest.update(input.readAllBytes());
            }
            for (Class<?> nested : Arrays.stream(type.getDeclaredClasses()).sorted(Comparator.comparing(Class::getName)).toList())
                fingerprint(nested, digest);
        }
    }
}
