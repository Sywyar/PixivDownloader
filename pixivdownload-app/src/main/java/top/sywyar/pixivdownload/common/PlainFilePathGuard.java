package top.sywyar.pixivdownload.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Rejects symbolic links, junction-like entries and special files on destructive file paths. */
public final class PlainFilePathGuard {

    private PlainFilePathGuard() {
    }

    public static boolean isPlainRegularFile(Path file) {
        try {
            requirePlainRegularFile(file);
            return true;
        } catch (IOException | RuntimeException rejected) {
            return false;
        }
    }

    public static boolean isPlainDirectory(Path directory) {
        try {
            requirePlainParent(directory.resolve(".plain-directory-check"), false);
            BasicFileAttributes attributes = attributesIfPresent(directory.toAbsolutePath().normalize());
            return attributes != null && attributes.isDirectory()
                    && !attributes.isSymbolicLink() && !attributes.isOther();
        } catch (IOException | RuntimeException rejected) {
            return false;
        }
    }

    public static void requirePlainRegularFile(Path file) throws IOException {
        Path normalized = normalizeAbsolute(file);
        requirePlainParent(normalized, false);
        BasicFileAttributes attributes = attributesIfPresent(normalized);
        if (attributes == null || !attributes.isRegularFile()
                || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("path is not a plain regular file: " + normalized);
        }
    }

    /**
     * 校验一条目录路径的开路条件，并区分「已有但不安全的节点」与「尚未创建的层级」。
     *
     * <p>{@link #requirePlainParent(Path, boolean)} 只看最后平铺的「是/否普通目录」，无法区分这两种
     * 情况，调用方因此会把尚未创建的普通目录层级当成链接拒绝。本方法逐个检查已经存在的祖先节点：
     * 符号链接、Junction（{@code isOther()}）和非目录节点一律记为 {@link RejectedNode}，缺失的层级记入
     * {@link DirectoryOpenPrecondition#missingLevels()}，由调用方决定是否创建。
     *
     * <p>路径本身（{@code path} 的最后一个名字）也参与判定：它已存在但不是普通目录时同样拒绝，
     * 不存在时记为待创建。祖先链上第一个已存在但非普通目录的节点，以及路径本身的同类情形，都通过
     * {@link DirectoryOpenPrecondition#rejectedNode()} 返回，且 {@link RejectedNode#path()} 指向该节点，
     * 调用方据此给出区分链接 / Junction / 非目录 / 权限的文案。调用方在真正写入前必须再次调用本方法，
     * 以覆盖判定与写入之间的竞态窗口。
     *
     * @param path 必须在创建后成为普通目录的目标路径
     * @return 路径可创建或已经是普通目录；{@link DirectoryOpenPrecondition#rejected()} 为真时必须拒绝
     * @throws IOException 路径无绝对父目录
     */
    public static DirectoryOpenPrecondition requireDirectoryOpenPrecondition(Path path) throws IOException {
        Path normalized = normalizeAbsolute(path);
        Path parent = normalized.getParent();
        Path current = parent == null ? null : parent.getRoot();
        if (parent == null || current == null) {
            throw new IOException("path must have an absolute parent: " + normalized);
        }
        RejectedNode rootRejection = rejectNodeIfUnsafe(current);
        if (rootRejection != null) {
            return new DirectoryOpenPrecondition(normalized, rootRejection, List.of());
        }
        List<Path> missing = new ArrayList<>();
        for (Path component : parent) {
            current = current.resolve(component.toString());
            BasicFileAttributes attributes = attributesIfPresent(current);
            if (attributes == null) {
                missing.add(current);
                continue;
            }
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                // 已存在的祖先不是普通目录：连同该节点一起返回，由调用方按其类型给出区分文案，
                // 而不是笼统抛出「不是普通目录」把链接、缺失与权限问题混为一谈。
                return new DirectoryOpenPrecondition(normalized, RejectedNode.of(current, attributes), List.of());
            }
        }
        RejectedNode rejectedNode = rejectNodeIfUnsafe(normalized);
        if (rejectedNode == null && attributesIfPresent(normalized) == null) {
            missing.add(normalized);
        }
        return new DirectoryOpenPrecondition(normalized, rejectedNode, missing);
    }

    /** 已存在但不是普通目录的节点；调用方据此区分链接 / Junction / 非目录。 */
    public record RejectedNode(Path path, Kind kind) {

        /** 节点为何被视为不安全。 */
        public enum Kind {
            /** 符号链接（{@code mklink /D}）。 */
            SYMBOLIC_LINK,
            /** Junction 或其它重解析点（{@code mklink /J}）。 */
            OTHER_REPARSE_POINT,
            /** 存在但不是目录（普通文件等）。 */
            NOT_A_DIRECTORY
        }

        public static RejectedNode of(Path path, BasicFileAttributes attributes) {
            Kind kind;
            if (attributes.isSymbolicLink()) {
                kind = Kind.SYMBOLIC_LINK;
            } else if (attributes.isOther()) {
                kind = Kind.OTHER_REPARSE_POINT;
            } else {
                kind = Kind.NOT_A_DIRECTORY;
            }
            return new RejectedNode(path, kind);
        }
    }

    /**
     * 目录开路条件：{@code rejectedNode} 非空表示必须拒绝，{@code missingLevels} 为需要创建的层级。
     *
     * @param path           规范化后的绝对路径
     * @param rejectedNode   已存在但指向不安全目标的节点，{@code null} 表示没有
     * @param missingLevels  尚不存在、需要逐级创建的目录，按从外到内排序
     */
    public record DirectoryOpenPrecondition(Path path, RejectedNode rejectedNode, List<Path> missingLevels) {

        public DirectoryOpenPrecondition {
            Objects.requireNonNull(path, "path");
            missingLevels = List.copyOf(missingLevels);
        }

        /** 是否必须拒绝写入。 */
        public boolean rejected() {
            return rejectedNode != null;
        }

        /** 是否已经是可以直接写入的普通目录。 */
        public boolean ready() {
            return rejectedNode == null && missingLevels.isEmpty();
        }
    }

    private static RejectedNode rejectNodeIfUnsafe(Path path) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(path);
        if (attributes == null || (attributes.isDirectory()
                && !attributes.isSymbolicLink() && !attributes.isOther())) {
            return null;
        }
        return RejectedNode.of(path, attributes);
    }

    public static void requirePlainParent(Path file, boolean createMissing) throws IOException {
        Path normalized = normalizeAbsolute(file);
        Path parent = normalized.getParent();
        Path current = parent == null ? null : parent.getRoot();
        if (parent == null || current == null) {
            throw new IOException("path must have an absolute parent: " + normalized);
        }
        requirePlainDirectoryEntry(current);
        for (Path component : parent) {
            current = current.resolve(component.toString());
            BasicFileAttributes attributes = attributesIfPresent(current);
            if (attributes == null && createMissing) {
                Files.createDirectory(current);
                attributes = attributesIfPresent(current);
            }
            if (attributes == null || !attributes.isDirectory()
                    || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("path parent is not a plain directory: " + current);
            }
        }
    }

    private static Path normalizeAbsolute(Path path) throws IOException {
        if (path == null) {
            throw new IOException("path must not be null");
        }
        return path.toAbsolutePath().normalize();
    }

    private static void requirePlainDirectoryEntry(Path directory) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(directory);
        if (attributes == null || !attributes.isDirectory()
                || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("path component is not a plain directory: " + directory);
        }
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }
}
