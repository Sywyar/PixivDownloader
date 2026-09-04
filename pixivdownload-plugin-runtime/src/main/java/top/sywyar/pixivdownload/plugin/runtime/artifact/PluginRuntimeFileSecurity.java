package top.sywyar.pixivdownload.plugin.runtime.artifact;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 尽力证明运行时托管文件的 owner 并收紧权限；不支持 ACL/POSIX 的文件系统仍依赖形态、NOFOLLOW 与哈希边界。 */
final class PluginRuntimeFileSecurity {

    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeFileSecurity.class);
    private static final int MAX_MANAGED_ENTRIES = 25_000;
    private static final Set<PosixFilePermission> WRITABLE_DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> WRITABLE_FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> READ_ONLY_DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> READ_ONLY_FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ);
    private static final Set<AclEntryPermission> READ_ONLY_ACL_PERMISSIONS = EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.SYNCHRONIZE);
    private static final Set<AclEntryPermission> CONTENT_WRITE_ACL_PERMISSIONS = EnumSet.of(
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE);

    private PluginRuntimeFileSecurity() {
    }

    /** 收紧全部运行时托管根，并返回经过当前进程创建探针证明的 OS owner。 */
    static UserPrincipal secureLoadingRoots(PluginRuntimeLayout layout) throws IOException {
        Objects.requireNonNull(layout, "layout");
        Path pluginsRoot = layout.pluginsRoot().toAbsolutePath().normalize();
        requirePlainDirectory(pluginsRoot, "plugins root");
        warnIfPermissionViewsUnavailable(pluginsRoot);
        UserPrincipal owner = proveCurrentOwner(pluginsRoot);
        Path provenance = secureManagedDirectory(
                pluginsRoot, layout.provenanceDirectory(), owner, "plugin provenance directory");
        secureWritableEntriesBestEffort(provenance, owner);
        secureManagedDirectory(pluginsRoot, layout.runtimeDirectory(), owner, "plugin runtime directory");
        return owner;
    }

    static Path createPrivateDirectory(Path parent, String name, UserPrincipal owner) throws IOException {
        requirePlainDirectory(parent, "plugin runtime parent directory");
        requireOwner(parent, owner, "plugin runtime parent directory");
        Path directory = parent.resolve(name).toAbsolutePath().normalize();
        if (!Objects.equals(directory.getParent(), parent.toAbsolutePath().normalize())) {
            throw new IOException("plugin runtime directory must be a direct child: " + directory);
        }
        Files.createDirectory(directory);
        secureWritableDirectory(directory, owner);
        return directory;
    }

    static void secureWritableDirectory(Path directory, UserPrincipal owner) throws IOException {
        requirePlainDirectory(directory, "plugin runtime directory");
        requireOwner(directory, owner, "plugin runtime directory");
        applyPermissions(directory, owner, WRITABLE_DIRECTORY_PERMISSIONS,
                EnumSet.allOf(AclEntryPermission.class), true);
    }

    static void secureWritableFile(Path file, UserPrincipal owner) throws IOException {
        requirePlainRegularFile(file, "plugin runtime file");
        requireOwner(file, owner, "plugin runtime file");
        applyPermissions(file, owner, WRITABLE_FILE_PERMISSIONS,
                EnumSet.allOf(AclEntryPermission.class), true);
    }

    static void secureReadOnlyDirectory(Path directory, UserPrincipal owner) throws IOException {
        requirePlainDirectory(directory, "plugin runtime directory");
        requireOwner(directory, owner, "plugin runtime directory");
        applyPermissions(directory, owner, READ_ONLY_DIRECTORY_PERMISSIONS,
                READ_ONLY_ACL_PERMISSIONS, false);
    }

    static void secureReadOnlyFile(Path file, UserPrincipal owner) throws IOException {
        requirePlainRegularFile(file, "plugin runtime file");
        requireOwner(file, owner, "plugin runtime file");
        applyPermissions(file, owner, READ_ONLY_FILE_PERMISSIONS,
                READ_ONLY_ACL_PERMISSIONS, false);
    }

    static void verifyReadOnly(Path path, boolean directory, UserPrincipal owner) throws IOException {
        if (directory) {
            requirePlainDirectory(path, "plugin runtime directory");
        } else {
            requirePlainRegularFile(path, "plugin runtime file");
        }
        requireOwner(path, owner, "plugin runtime entry");
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                Set<PosixFilePermission> expected = directory
                        ? READ_ONLY_DIRECTORY_PERMISSIONS : READ_ONLY_FILE_PERMISSIONS;
                if (!posix.readAttributes().permissions().equals(expected)) {
                    log.warn("Plugin runtime entry permissions could not be sealed read-only: {}", path);
                }
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(
                    path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl != null && owner != null) {
                verifyAcl(path, owner, acl.getAcl(), false);
            }
        } catch (IOException | RuntimeException failure) {
            warnHardeningFailure("verify read-only permissions", path, failure);
        }
    }

    static void makeTreeWritable(Path root, UserPrincipal owner) throws IOException {
        BasicFileAttributes rootAttributes = attributesIfPresent(root);
        if (rootAttributes == null) {
            return;
        }
        requirePlainDirectory(root, "plugin artifact workspace");
        secureWritableDirectory(root, owner);
        try (var walk = Files.walk(root)) {
            for (Path entry : walk.skip(1).toList()) {
                BasicFileAttributes attributes = Files.readAttributes(
                        entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory() && !attributes.isRegularFile()) {
                    throw new IOException("plugin artifact workspace contains an unsafe entry: " + entry);
                }
                if (attributes.isDirectory()) {
                    secureWritableDirectory(entry, owner);
                } else {
                    secureWritableFile(entry, owner);
                }
            }
        }
    }

    static UserPrincipal owner(Path path) throws IOException {
        try {
            return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException failure) {
            warnHardeningFailure("read filesystem owner", path, failure);
            return null;
        }
    }

    private static UserPrincipal proveCurrentOwner(Path pluginsRoot) throws IOException {
        UserPrincipal rootOwner = owner(pluginsRoot);
        if (rootOwner == null) {
            return null;
        }
        Path probe = pluginsRoot.resolve(".runtime-owner-probe-" + UUID.randomUUID());
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel ignored = Files.newByteChannel(probe, options)) {
            UserPrincipal processOwner = owner(probe);
            if (rootOwner != null && processOwner != null && !samePrincipal(rootOwner, processOwner)) {
                log.warn("Plugins root owner differs from the current application identity; "
                        + "continuing with portable best-effort hardening: {}", pluginsRoot);
            }
            return processOwner;
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private static Path secureManagedDirectory(Path pluginsRoot, Path candidate,
                                               UserPrincipal owner, String role) throws IOException {
        Path directory = candidate.toAbsolutePath().normalize();
        if (!Objects.equals(directory.getParent(), pluginsRoot)) {
            throw new IOException(role + " is not a direct child of the plugins root");
        }
        BasicFileAttributes attributes = attributesIfPresent(directory);
        if (attributes == null) {
            Files.createDirectory(directory);
        }
        requirePlainDirectory(directory, role);
        requireOwner(directory, owner, role);
        secureWritableDirectory(directory, owner);
        return directory;
    }

    private static void secureWritableEntriesBestEffort(Path root, UserPrincipal owner) {
        try (var children = Files.list(root)) {
            int entryCount = 0;
            var iterator = children.iterator();
            while (iterator.hasNext()) {
                if (++entryCount > MAX_MANAGED_ENTRIES) {
                    log.warn("Plugin provenance directory exceeds the permission-hardening entry limit: {}", root);
                    return;
                }
                Path entry = iterator.next();
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(
                            entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException | RuntimeException failure) {
                    warnHardeningFailure("inspect managed provenance entry", entry, failure);
                    continue;
                }
                if (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory() && !attributes.isRegularFile()) {
                    log.warn("Skipping permission hardening for unsafe plugin provenance entry: {}", entry);
                    continue;
                }
                try {
                    if (attributes.isDirectory()) {
                        secureWritableDirectory(entry, owner);
                    } else {
                        secureWritableFile(entry, owner);
                    }
                } catch (IOException | RuntimeException failure) {
                    warnHardeningFailure("harden managed provenance entry", entry, failure);
                }
            }
        } catch (IOException | RuntimeException failure) {
            warnHardeningFailure("enumerate managed provenance entries", root, failure);
        }
    }

    private static void applyPermissions(Path path,
                                         UserPrincipal owner,
                                         Set<PosixFilePermission> posixPermissions,
                                         Set<AclEntryPermission> aclPermissions,
                                         boolean writable) throws IOException {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                Files.setPosixFilePermissions(path, posixPermissions);
                if (!posix.readAttributes().permissions().equals(posixPermissions)) {
                    throw new IOException("failed to prove plugin runtime POSIX permissions: " + path);
                }
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(
                    path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null || owner == null) {
                return;
            }
            AclEntry ownerEntry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(aclPermissions)
                    .build();
            acl.setAcl(List.of(ownerEntry));
            verifyAcl(path, owner, acl.getAcl(), writable);
        } catch (IOException | RuntimeException failure) {
            warnHardeningFailure("apply filesystem permissions", path, failure);
        }
    }

    private static void verifyAcl(Path path, UserPrincipal owner,
                                  List<AclEntry> entries, boolean writable) throws IOException {
        boolean ownerAllowed = false;
        for (AclEntry entry : entries) {
            if (entry.type() != AclEntryType.ALLOW) {
                continue;
            }
            boolean ownerEntry = samePrincipal(owner, entry.principal());
            if (ownerEntry) {
                ownerAllowed |= entry.permissions().contains(AclEntryPermission.READ_DATA)
                        && (!writable || entry.permissions().contains(AclEntryPermission.WRITE_DATA));
                if (!writable && !java.util.Collections.disjoint(
                        entry.permissions(), CONTENT_WRITE_ACL_PERMISSIONS)) {
                    throw new IOException("plugin runtime entry remains writable: " + path);
                }
            } else if (!java.util.Collections.disjoint(
                    entry.permissions(), CONTENT_WRITE_ACL_PERMISSIONS)) {
                throw new IOException("plugin runtime entry is writable by another identity: " + path);
            }
        }
        if (!ownerAllowed) {
            throw new IOException("plugin runtime owner lacks required access: " + path);
        }
    }

    private static void requireOwner(Path path, UserPrincipal expected, String role) throws IOException {
        UserPrincipal actual = owner(path);
        if (expected != null && actual != null && !samePrincipal(expected, actual)) {
            log.warn("{} is owned by another identity; continuing with portable best-effort hardening: {}",
                    role, path);
        }
    }

    private static boolean samePrincipal(UserPrincipal left, UserPrincipal right) {
        return left != null && right != null
                && (left.equals(right) || left.getName().equalsIgnoreCase(right.getName()));
    }

    private static void warnIfPermissionViewsUnavailable(Path pluginsRoot) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    pluginsRoot, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            AclFileAttributeView acl = Files.getFileAttributeView(
                    pluginsRoot, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix == null && acl == null) {
                log.warn("Filesystem exposes neither POSIX permissions nor Windows ACLs; "
                        + "plugin runtime permission hardening is unavailable for {}", pluginsRoot);
            }
        } catch (RuntimeException failure) {
            warnHardeningFailure("inspect filesystem permission capabilities", pluginsRoot, failure);
        }
    }

    private static void warnHardeningFailure(String operation, Path path, Throwable failure) {
        log.warn("Could not {} for {}; continuing with portable best-effort hardening: {}",
                operation, path, failure.toString());
    }

    private static void requirePlainDirectory(Path path, String role) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(path);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isDirectory()) {
            throw new IOException(role + " must be a plain directory: " + path);
        }
    }

    private static void requirePlainRegularFile(Path path, String role) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(path);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isRegularFile()) {
            throw new IOException(role + " must be a plain regular file: " + path);
        }
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }
}
