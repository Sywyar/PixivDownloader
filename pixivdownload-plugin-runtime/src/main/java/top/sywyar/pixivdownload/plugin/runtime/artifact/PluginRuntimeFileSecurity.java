package top.sywyar.pixivdownload.plugin.runtime.artifact;

import java.io.IOException;
import java.nio.channels.FileChannel;
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

/** 证明运行时托管的插件文件属于当前进程身份，并把访问权限收紧到该身份。 */
final class PluginRuntimeFileSecurity {

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
        UserPrincipal owner = proveCurrentOwner(pluginsRoot);
        Path provenance = secureManagedDirectory(
                pluginsRoot, layout.provenanceDirectory(), owner, "plugin provenance directory");
        secureWritableTree(provenance, owner);
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
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> expected = directory
                    ? READ_ONLY_DIRECTORY_PERMISSIONS : READ_ONLY_FILE_PERMISSIONS;
            if (!posix.readAttributes().permissions().equals(expected)) {
                throw new IOException("plugin runtime entry is not read-only: " + path);
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("filesystem cannot prove plugin runtime permissions: " + path);
        }
        verifyAcl(path, owner, acl.getAcl(), false);
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
        return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static UserPrincipal proveCurrentOwner(Path pluginsRoot) throws IOException {
        UserPrincipal rootOwner = owner(pluginsRoot);
        Path probe = pluginsRoot.resolve(".runtime-owner-probe-" + UUID.randomUUID());
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (FileChannel ignored = FileChannel.open(probe, options)) {
            UserPrincipal processOwner = owner(probe);
            if (!samePrincipal(rootOwner, processOwner)) {
                throw new IOException("plugins root is not owned by the current application identity: "
                        + pluginsRoot);
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

    private static void secureWritableTree(Path root, UserPrincipal owner) throws IOException {
        try (var walk = Files.walk(root)) {
            List<Path> entries = walk.toList();
            if (entries.size() > MAX_MANAGED_ENTRIES) {
                throw new IOException("plugin provenance directory exceeds the supported entry count");
            }
            for (Path entry : entries) {
                BasicFileAttributes attributes = Files.readAttributes(
                        entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory() && !attributes.isRegularFile()) {
                    throw new IOException("plugin provenance contains an unsafe entry: " + entry);
                }
                if (attributes.isDirectory()) {
                    secureWritableDirectory(entry, owner);
                } else {
                    secureWritableFile(entry, owner);
                }
            }
        }
    }

    private static void applyPermissions(Path path,
                                         UserPrincipal owner,
                                         Set<PosixFilePermission> posixPermissions,
                                         Set<AclEntryPermission> aclPermissions,
                                         boolean writable) throws IOException {
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
        if (acl == null) {
            throw new IOException("filesystem cannot prove plugin runtime permissions: " + path);
        }
        AclEntry ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(aclPermissions)
                .build();
        acl.setAcl(List.of(ownerEntry));
        verifyAcl(path, owner, acl.getAcl(), writable);
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
        if (!samePrincipal(expected, actual)) {
            throw new IOException(role + " is owned by another identity: " + path);
        }
    }

    private static boolean samePrincipal(UserPrincipal left, UserPrincipal right) {
        return left.equals(right) || left.getName().equalsIgnoreCase(right.getName());
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
