package top.sywyar.pixivdownload.sdk.development;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

/** 缓存只保存固定 ZIP；每次运行复制、核验并解压到项目自己的新目录。 */
final class SdkRuntimeArchive {
    static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    static final long MAX_EXTRACTED_BYTES = 1536L * 1024 * 1024;
    static final int MAX_ENTRIES = 48_000;
    static final int MAX_CACHE_ENTRIES = 8;
    static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration LOCK_TIMEOUT = Duration.ofMinutes(6);
    private static final Set<String> DOWNLOAD_HOSTS = Set.of(
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");

    private SdkRuntimeArchive() {
    }

    static Path cached(SdkRuntimeLock lock, Path cache) throws IOException, InterruptedException {
        requireDirectory(cache);
        Path guard = cache.resolve("cache.lock");
        try (FileChannel channel = FileChannel.open(guard, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            var lease = tryLock(channel);
            long deadline = System.nanoTime() + LOCK_TIMEOUT.toNanos();
            while (lease == null) {
                if (System.nanoTime() > deadline) throw new IOException("SDK_CACHE_BUSY");
                Thread.sleep(100);
                lease = tryLock(channel);
            }
            try {
                var artifact = lock.runtime().archive();
                Path zip = cache.resolve(artifact.sha256() + ".zip");
                if (Files.exists(zip, LinkOption.NOFOLLOW_LINKS)) {
                    verify(zip, artifact);
                    return zip;
                }
                try (var entries = Files.list(cache)) {
                    var existing = entries.limit(32).toList();
                    if (existing.size() >= 32 || existing.stream()
                            .filter(path -> path.getFileName().toString().endsWith(".zip")).count() >= MAX_CACHE_ENTRIES) {
                        throw new IOException("SDK_CACHE_CAPACITY");
                    }
                }
                Path temporary = Files.createTempFile(cache, "download-", ".part");
                try {
                    download(URI.create(lock.runtime().downloadUrl()), temporary, artifact.size());
                    verify(temporary, artifact);
                    Files.move(temporary, zip);
                    return zip;
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } finally {
                lease.release();
            }
        }
    }

    static void prepare(SdkRuntimeLock lock, Path cachedZip, Path run) throws IOException {
        requireDirectory(run);
        try (var entries = Files.list(run)) {
            if (entries.findAny().isPresent()) throw new IOException("SDK_RUN_NOT_EMPTY");
        }
        Path frozen = Files.createTempFile(run, "runtime-", ".zip");
        try {
            try (InputStream input = Files.newInputStream(cachedZip, LinkOption.NOFOLLOW_LINKS)) {
                copy(input, frozen, lock.runtime().archive().size(), Long.MAX_VALUE);
            }
            verify(frozen, lock.runtime().archive());
            extract(frozen, run);
            verifyBaseline(lock, run);
        } finally {
            Files.deleteIfExists(frozen);
        }
    }

    static void verifyBaseline(SdkRuntimeLock lock, Path run) throws IOException {
        verify(run.resolve(lock.runtime().host().file()), lock.runtime().host());
        verify(run.resolve(lock.runtime().pluginsManifest().file()), lock.runtime().pluginsManifest());
        var manifest = SdkRuntimeLock.readJson(run.resolve(lock.runtime().pluginsManifest().file()));
        if (!manifest.isArray() || manifest.isEmpty() || manifest.size() > 512) {
            throw new IOException("SDK_INVALID_PLUGIN_MANIFEST");
        }
        Set<String> ids = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (var plugin : manifest) {
            String id = SdkRuntimeLock.requiredText(plugin, "id");
            String version = SdkRuntimeLock.requiredText(plugin, "version");
            String file = SdkRuntimeLock.requiredText(plugin, "file");
            if (!id.matches("[a-z0-9][a-z0-9._-]{0,127}") || version.length() > 128
                    || !ids.add(id) || !files.add(file.toLowerCase(Locale.ROOT))
                    || !file.matches("plugins/[A-Za-z0-9._-]+\\.jar") || !plugin.path("size").isIntegralNumber()
                    || !plugin.path("size").canConvertToLong()
                    || !plugin.path("signature").isObject()) {
                throw new IOException("SDK_INVALID_PLUGIN_IDENTITY");
            }
            verify(run.resolve(file), new SdkRuntimeLock.Artifact(file, plugin.path("size").longValue(),
                    SdkRuntimeLock.requiredText(plugin, "sha256")));
            // ZIP 的固定摘要同时覆盖这些元数据；执行准入仍由宿主的正式供应链校验完成。
            String name = Path.of(file).getFileName().toString();
            for (Path sidecar : java.util.List.of(run.resolve(file + ".sig"), run.resolve(file + ".sha256"),
                    run.resolve("plugins/provenance/" + name + ".pixiv-plugin-provenance"))) {
                if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(sidecar) == 0 || Files.size(sidecar) > SdkRuntimeLock.MAX_JSON_BYTES) {
                    throw new IOException("SDK_PLUGIN_METADATA_MISSING");
                }
            }
        }
        try (var installed = Files.list(run.resolve("plugins"))) {
            for (Path file : installed.toList()) {
                String name = file.getFileName().toString();
                if ((name.endsWith(".jar") || name.endsWith(".zip"))
                        && !files.contains(("plugins/" + name).toLowerCase(Locale.ROOT))) {
                    throw new IOException("SDK_UNEXPECTED_PLUGIN");
                }
            }
        }
    }

    static void extract(Path archive, Path destination) throws IOException {
        Set<String> names = new HashSet<>();
        var filesystemNames = new HashMap<String, String>();
        long total = 0;
        try (ZipFile zip = new ZipFile(archive.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            if (zip.size() > MAX_ENTRIES) throw new IOException("SDK_ARCHIVE_ENTRY_LIMIT");
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
                Path relative = SdkRuntimeLock.safeRelative(name);
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("SDK_DUPLICATE_ARCHIVE_PATH");
                String prefix = "";
                for (Path part : relative) {
                    prefix = prefix.isEmpty() ? part.toString() : prefix + "/" + part;
                    String previous = filesystemNames.putIfAbsent(prefix.toLowerCase(Locale.ROOT), prefix);
                    if (previous != null && !previous.equals(prefix)) throw new IOException("SDK_ARCHIVE_PATH_COLLISION");
                    if (filesystemNames.size() > MAX_ENTRIES) throw new IOException("SDK_ARCHIVE_ENTRY_LIMIT");
                }
                Path target = destination.resolve(relative);
                // 新目录内只创建普通文件，不物化 ZIP 的链接 / 权限属性。
                if (entry.isDirectory()) {
                    requireDirectory(target);
                    continue;
                }
                requireDirectory(target.getParent());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("SDK_ARCHIVE_PATH_COLLISION");
                Files.createFile(target);
                long remaining = Math.min(MAX_ENTRY_BYTES, MAX_EXTRACTED_BYTES - total);
                try (InputStream input = zip.getInputStream(entry)) {
                    total += copy(input, target, remaining, Long.MAX_VALUE);
                }
            }
        }
    }

    static void verify(Path file, SdkRuntimeLock.Artifact expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != expected.size()
                || !sha256(file, expected.size()).equals(expected.sha256())) {
            throw new IOException("SDK_ARTIFACT_MISMATCH: " + expected.file());
        }
    }

    static String sha256(Path file, long maximum) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        long read = 0;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                read += count;
                if (read > maximum) throw new IOException("SDK_ARTIFACT_TOO_LARGE");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void requireDirectory(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        Files.createDirectories(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || !absolute.toRealPath().equals(absolute)) {
            throw new IOException("SDK_UNSAFE_DIRECTORY");
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException busy) {
            return null;
        }
    }

    private static void download(URI initial, Path output, long maximum) throws IOException {
        URI current = initial;
        long deadline = System.nanoTime() + DOWNLOAD_TIMEOUT.toNanos();
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!"https".equals(current.getScheme()) || !DOWNLOAD_HOSTS.contains(current.getHost())
                    || current.getUserInfo() != null || current.getFragment() != null
                    || current.getPort() != -1 || current.toASCIIString().length() > 8192) {
                throw new IOException("SDK_DOWNLOAD_URL_REJECTED");
            }
            var connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || System.nanoTime() > deadline) throw new IOException("SDK_DOWNLOAD_REDIRECT");
                    current = current.resolve(location);
                    continue;
                }
                if (status != 200 || connection.getContentLengthLong() > maximum) {
                    throw new IOException("SDK_DOWNLOAD_RESPONSE: " + status);
                }
                try (InputStream input = connection.getInputStream()) {
                    copy(input, output, maximum, deadline);
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("SDK_DOWNLOAD_REDIRECT_LIMIT");
    }

    static long copy(InputStream input, Path output, long maximum, long deadline) throws IOException {
        long total = 0;
        try (var target = Files.newOutputStream(output, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                total += count;
                if (total > maximum || System.nanoTime() > deadline) throw new IOException("SDK_COPY_LIMIT");
                target.write(buffer, 0, count);
            }
        }
        return total;
    }
}
