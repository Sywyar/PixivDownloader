package top.sywyar.pixivdownload.plugin.runtime.cache;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;

/** 插件缓存共用的私有权限、认证和非阻塞文件锁；不承载发布者信任。 */
public final class PluginCacheAccess {
    private final Path root;
    private UserPrincipal owner;

    public PluginCacheAccess(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public <T> T locked(Operation<T> operation) throws IOException {
        prepareRoot();
        Path lockFile = root.resolve("cache.lock");
        boolean exists = Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS);
        if (exists) requirePrivate(lockFile, false);
        try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            if (!exists) makePrivate(lockFile, false);
            try (var lock = channel.tryLock()) {
                if (lock == null) return null;
                requirePrivate(root, true);
                Path keyFile = root.resolve("cache.key");
                byte[] key = null;
                if (Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                    requirePrivate(keyFile, false);
                    if (Files.size(keyFile) == 32) key = readBounded(keyFile, 32);
                }
                if (key == null || key.length != 32) {
                    key = new byte[32];
                    new SecureRandom().nextBytes(key);
                    Path temporary = Files.createTempFile(root, ".writing-key-", ".tmp");
                    try {
                        makePrivate(temporary, false);
                        Files.write(temporary, key, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                        Files.move(temporary, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                }
                return operation.run(key);
            } catch (OverlappingFileLockException busy) { return null; }
        }
    }

    private void prepareRoot() throws IOException {
        for (Path current = root.getRoot(); current != null; ) {
            boolean leaf = current.equals(root);
            boolean created = false;
            try { Files.createDirectory(current); created = true; }
            catch (FileAlreadyExistsException exists) { }
            requirePlain(current, true);
            if (leaf) {
                if (owner == null) {
                    Path probe = Files.createTempFile(root, ".owner-", ".tmp");
                    try { owner = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS); }
                    finally { Files.deleteIfExists(probe); }
                }
                if (created) makePrivate(root, true);
                requirePrivate(root, true);
                return;
            }
            current = current.resolve(root.getName(current.getNameCount()));
        }
    }

    public void requirePrivate(Path path, boolean directory) throws IOException {
        requirePlain(path, directory);
        if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) throw new IOException("CACHE_OWNER");
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            var actual = posix.readAttributes().permissions();
            if (!permissions(directory).containsAll(actual) || !actual.contains(PosixFilePermission.OWNER_READ)
                    || directory && !actual.contains(PosixFilePermission.OWNER_EXECUTE)) throw new IOException("CACHE_PERMISSIONS");
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("CACHE_PERMISSIONS_UNAVAILABLE");
        boolean readable = false;
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW) continue;
            if (!owner.equals(entry.principal()) && !entry.permissions().isEmpty()) throw new IOException("CACHE_PERMISSIONS");
            readable |= owner.equals(entry.principal()) && entry.permissions().contains(AclEntryPermission.READ_DATA);
        }
        if (!readable) throw new IOException("CACHE_PERMISSIONS");
    }

    public void makePrivate(Path path, boolean directory) throws IOException {
        requirePlain(path, directory);
        if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) throw new IOException("CACHE_OWNER");
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) posix.setPermissions(permissions(directory));
        else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("CACHE_PERMISSIONS_UNAVAILABLE");
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
        requirePrivate(path, directory);
    }

    private static Set<PosixFilePermission> permissions(boolean directory) {
        return PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
    }

    public static void requirePlain(Path path, boolean directory) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new IOException("CACHE_PATH_INVALID");
        }
    }

    public static byte[] readBounded(Path file, int maximum) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("CACHE_ENTRY_CAPACITY");
            return bytes;
        }
    }

    public static byte[] authenticate(byte[] key, byte[] bytes) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException failure) { throw new IOException("CACHE_AUTHENTICATION_UNAVAILABLE", failure); }
    }

    @FunctionalInterface public interface Operation<T> { T run(byte[] key) throws IOException; }
}
