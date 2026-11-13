package top.sywyar.pixivdownload.plugin.runtime.install.verify.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginClassSpace;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier.VerificationUsage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/** 只复用已成功扫描的纯值；不保存签名、来源、启用或兼容性的准入结论。 */
public final class PluginScanCache {
    private static final Logger log = LoggerFactory.getLogger(PluginScanCache.class);
    private final ScanCacheFiles files;
    private long hits, misses, writes, failures;

    /** 未提供目录时保留原始扫描路径，不创建文件。 */
    public PluginScanCache(Path directory) { files = directory == null ? null : new ScanCacheFiles(directory); }

    public synchronized VerificationUsage verifyArchive(Path archive, PluginPackageLimits limits) {
        return compute(archive, false, limits, () -> PluginPackageVerifier.verifyAndMeasure(archive, limits));
    }

    public synchronized PluginClassSpace verifyDevelopment(Path directory, PluginPackageLimits limits) {
        return compute(directory, true, limits, () -> PluginPackageVerifier.measureDevelopment(directory, limits)).classSpace();
    }

    public synchronized Statistics statistics() { return new Statistics(hits, misses, writes, failures); }

    private VerificationUsage compute(Path input, boolean directory, PluginPackageLimits limits, Supplier<VerificationUsage> scan) {
        if (files == null) return scan.get();
        String identity;
        try { identity = identity(input, directory, limits); }
        catch (IOException | UncheckedIOException | SecurityException failure) {
            failed("fingerprint", failure); misses++; return scan.get();
        }
        try {
            byte[] bytes = files.read(identity);
            if (bytes != null) {
                var usage = ScanCacheCodec.decode(identity, bytes);
                if (usage.entryCount() > limits.maxEntries() || usage.totalUncompressedBytes() > limits.maxTotalUncompressedBytes()) {
                    throw new IOException("CACHE_USAGE_LIMIT");
                }
                hits++; return usage;
            }
        } catch (IOException | SecurityException failure) { failed("read", failure); }
        misses++;
        // 扫描失败原样传播，不把部分用量或失败结论写入持久缓存。
        VerificationUsage usage = scan.get();
        try {
            if (identity.equals(identity(input, directory, limits)) && files.write(identity, ScanCacheCodec.encode(identity, usage))) writes++;
        } catch (IOException | UncheckedIOException | SecurityException failure) { failed("write", failure); }
        return usage;
    }

    private void failed(String operation, Exception failure) {
        failures++;
        // 每个运行时只输出首个缓存故障，累计数量由启动汇总保留。
        if (failures == 1) {
            log.warn("Plugin scan cache {} failed ({}); using full verification", operation, failure.getClass().getSimpleName());
            log.debug("Plugin scan cache failure details", failure);
        }
    }

    private static String identity(Path input, boolean directory, PluginPackageLimits limits) throws IOException {
        MessageDigest digest = sha256();
        update(digest, "plugin-scan-v1");
        update(digest, PluginPackageVerifier.scanImplementationIdentity());
        update(digest, Runtime.version().toString());
        update(digest, System.getProperty("java.vendor", ""));
        update(digest, System.getProperty("os.name", ""));
        update(digest, limits.toString());
        update(digest, directory ? "directory" : "archive");
        byte[] buffer = new byte[8192];
        long remaining = directory ? limits.maxTotalUncompressedBytes() : limits.maxArchiveBytes();
        try (var walk = Files.walk(input, directory ? (int) Math.min(Integer.MAX_VALUE, (long) limits.maxEntryDepth() + 1) : 0)) {
            var paths = walk.limit((long) limits.maxEntries() + 2).sorted().toList();
            if (paths.size() > (long) limits.maxEntries() + 1) throw new IOException("CACHE_INPUT_CAPACITY");
            for (Path path : paths) {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("CACHE_INPUT_LINK");
                if (directory && path.equals(input)) continue;
                String name = directory ? input.relativize(path).toString().replace('\\', '/') : "archive";
                if (name.length() > limits.maxEntryNameLength()) throw new IOException("CACHE_INPUT_CAPACITY");
                update(digest, name);
                update(digest, attributes.isDirectory() ? "directory" : "file");
                if (attributes.isDirectory()) {
                    if (input.relativize(path).getNameCount() > limits.maxEntryDepth()) throw new IOException("CACHE_INPUT_CAPACITY");
                    continue;
                }
                if (!attributes.isRegularFile()) throw new IOException("CACHE_INPUT_TYPE");
                update(digest, Long.toString(attributes.size()));
                long read = 0;
                try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if ((remaining -= count) < 0) throw new IOException("CACHE_INPUT_CAPACITY");
                        read += count;
                        // 开发会话的随机清理 marker 不属于部署内容；其路径、类型和长度仍参与身份。
                        if (!directory || !name.equals(".pixiv-plugin-dev-snapshot")) digest.update(buffer, 0, count);
                    }
                }
                if (read != attributes.size()) throw new IOException("CACHE_INPUT_CHANGED");
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }

    public record Statistics(long hits, long misses, long writes, long failures) { }
}
