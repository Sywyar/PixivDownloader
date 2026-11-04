package top.sywyar.pixivdownload.plugin.runtime.artifact;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** 对已确定的本地部署内容取有界摘要，不把路径、时间或运行期计数当作部署身份。 */
public final class DeploymentFingerprint {
    private DeploymentFingerprint() { }

    public static String of(Path source) throws IOException { return of(source, Set.of()); }

    static String of(Path source, Set<String> excluded) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        long remaining = 512L * 1024 * 1024;
        byte[] buffer = new byte[8192];
        boolean directory = Files.isDirectory(source);
        try (var paths = Files.walk(source, 64)) {
            var files = paths.limit(32769).sorted().toList();
            if (files.size() > 32768) throw new IOException("DEPLOYMENT_CAPACITY");
            for (var file : files) {
                var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("DEPLOYMENT_LINK");
                if (attributes.isDirectory()) {
                    if (source.relativize(file).getNameCount() >= 64) throw new IOException("DEPLOYMENT_CAPACITY");
                    continue;
                }
                if (!attributes.isRegularFile()) throw new IOException("DEPLOYMENT_FILE_INVALID");
                String name = directory ? source.relativize(file).toString().replace('\\', '/') : "archive";
                if (name.length() > 4096) throw new IOException("DEPLOYMENT_CAPACITY");
                if (excluded.contains(name)) continue;
                digest.update(name.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
                long size = attributes.size();
                if (size > remaining) throw new IOException("DEPLOYMENT_CAPACITY");
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(size).array());
                long read = 0;
                try (var input = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if ((remaining -= count) < 0) throw new IOException("DEPLOYMENT_CAPACITY");
                        read += count; digest.update(buffer, 0, count);
                    }
                }
                if (read != size) throw new IOException("DEPLOYMENT_CHANGED");
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
