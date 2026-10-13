package top.sywyar.pixivdownload.plugin.api.database.migration;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 备份和源变化凭据使用的普通文件操作；不解释引擎文件布局。 */
public final class MigrationFiles {
    private MigrationFiles() {}
    /**
     * 拒绝路径及父目录中的符号链接或特殊节点，允许目标尚未存在。
     * @param path 待核对路径
     * @param directory 目标应为目录而非文件
     * @throws IOException 路径不安全或无法检查
     */
    public static void requirePlain(Path path, boolean directory) throws IOException {
        var absolute = path.toAbsolutePath().normalize();
        for (Path part = absolute; part != null; part = part.getParent()) {
            if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) continue;
            var attributes = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || part.equals(absolute) && !(directory ? attributes.isDirectory() : attributes.isRegularFile()))
                throw new IOException("MIGRATION_UNSAFE_PATH");
        }
    }
    /**
     * 创建不覆盖旧文件的受限备份文件。
     * @param file 全新普通文件路径
     * @throws IOException 路径、创建或权限设置失败
     */
    public static void createPrivateFile(Path file) throws IOException {
        requirePlain(file, false);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.createFile(file);
        makePrivate(file);
    }
    /**
     * 将现有文件或目录的 ACL 或 POSIX 权限限制到所有者。
     * @param path 已检查的普通路径
     * @throws IOException 无法设置权限
     */
    public static void makePrivate(Path path) throws IOException {
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        else {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (Files.isDirectory(path)) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        }
    }
    /**
     * 按原始字节计算变化凭据，不创建文件。
     * @param path 普通文件路径
     * @return SHA-256；不存在或零字节文件返回空字符串
     * @throws IOException 路径不安全或读取失败
     */
    public static String digest(Path path) throws IOException {
        requirePlain(path, false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0) return "";
        var digest = MigrationValues.newDigest();
        try (var input = new java.security.DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return MigrationValues.finish(digest);
    }
}
