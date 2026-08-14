package top.sywyar.pixivdownload.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

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
