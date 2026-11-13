package top.sywyar.pixivdownload.plugin.runtime.artifact.cache;

import top.sywyar.pixivdownload.plugin.runtime.cache.PluginCacheAccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 有界内容指纹；开发快照按源 classes 的相对布局比较，避免复制期间变化进入缓存。 */
record PreparationFingerprint(String sha256, long bytes) {
    private static final Set<String> MARKERS = Set.of(".preparation-cache", ".pixiv-plugin-runtime-workspace",
            ".pixiv-plugin-dev-snapshot");

    static PreparationFingerprint capture(Path root, boolean developmentImage, boolean image,
                                          PluginCacheAccess access, long maximumBytes, int maximumEntries) throws IOException {
        boolean directory = Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS);
        var collector = new TreeEntries(root, directory, developmentImage, image, access, maximumEntries);
        Files.walkFileTree(root, Set.of(), directory ? 66 : 0, collector);
        var named = collector.named;
        if (named.size() > maximumEntries) throw new IOException("PREPARATION_ENTRY_LIMIT");
        MessageDigest digest = digest();
        long total = 0;
        byte[] buffer = new byte[8192];
        for (var entry : named.entrySet()) {
            Path path = entry.getValue();
            var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            update(digest, entry.getKey());
            update(digest, before.isDirectory() ? "directory" : "file");
            if (before.isDirectory()) continue;
            update(digest, Long.toString(before.size()));
            long count = 0;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > maximumBytes - total) throw new IOException("PREPARATION_BYTE_LIMIT");
                    total += read; count += read; digest.update(buffer, 0, read);
                }
            }
            var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || after.isSymbolicLink() || after.isOther() || count != before.size()
                    || before.size() != after.size() || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("PREPARATION_INPUT_CHANGED");
        }
        return new PreparationFingerprint(HexFormat.of().formatHex(digest.digest()), total);
    }

    // 命名成员类的字节码会随准备规则一起纳入缓存身份。
    private static final class TreeEntries extends SimpleFileVisitor<Path> {
        private final Path root;
        private final boolean directory, developmentImage, image;
        private final PluginCacheAccess access;
        private final int maximumEntries;
        private final TreeMap<String, Path> named = new TreeMap<>();
        private long visited;

        private TreeEntries(Path root, boolean directory, boolean developmentImage, boolean image,
                            PluginCacheAccess access, int maximumEntries) {
            this.root = root;
            this.directory = directory;
            this.developmentImage = developmentImage;
            this.image = image;
            this.access = access;
            this.maximumEntries = maximumEntries;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
            return collect(path, attributes);
        }

        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
            return collect(path, attributes);
        }

        private FileVisitResult collect(Path path, BasicFileAttributes attributes) throws IOException {
            if (++visited > (long) maximumEntries + 4) throw new IOException("PREPARATION_ENTRY_LIMIT");
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isDirectory() && !attributes.isRegularFile()) throw new IOException("PREPARATION_PATH_INVALID");
            if (access != null) access.requirePrivate(path, attributes.isDirectory());
            if (directory && root.equals(path)) return FileVisitResult.CONTINUE;
            String name = directory ? root.relativize(path).toString().replace('\\', '/') : "artifact";
            if (name.length() > 4096 || directory && root.relativize(path).getNameCount() > 64)
                throw new IOException("PREPARATION_PATH_LIMIT");
            if (image && MARKERS.contains(name)) return FileVisitResult.CONTINUE;
            if (developmentImage) {
                if (name.equals("classes")) return FileVisitResult.CONTINUE;
                if (name.startsWith("classes/")) name = name.substring(8);
            }
            if (named.put(name, path) != null) throw new IOException("PREPARATION_PATH_DUPLICATE");
            return FileVisitResult.CONTINUE;
        }
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }
}
