package top.sywyar.pixivdownload.plugin.runtime.install.verify.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件扫描持久缓存")
class PluginScanCacheTest {
    @TempDir Path temporary;
    private static final PluginPackageLimits LIMITS = PluginPackageLimits.defaults();

    @BeforeEach
    void usePhysicalTemporaryDirectory() throws IOException {
        temporary = temporary.toRealPath();
    }

    @Test @DisplayName("新进程读取纯值缓存并得到原始扫描的完整结果")
    void reusesAcrossProcesses() throws Exception {
        Path archive = archive("sample.jar", "example/Feature.class", "class-bytes");
        Path cacheRoot = temporary.resolve("cache");
        var cache = new PluginScanCache(cacheRoot);
        assertThat(cache.verifyArchive(archive, LIMITS)).isEqualTo(PluginPackageVerifier.verifyAndMeasure(archive, LIMITS));
        assertThat(cache.statistics().writes()).isEqualTo(1);
        Path output = temporary.resolve("child.log");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName(), cacheRoot.toString(), archive.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).withFailMessage(Files.readString(output)).isZero();
            assertThat(Files.readString(output)).contains("CACHE_HIT_OK");
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    public static final class Probe {
        public static void main(String[] args) {
            var cache = new PluginScanCache(Path.of(args[0]));
            var result = cache.verifyArchive(Path.of(args[1]), LIMITS);
            if (cache.statistics().hits() != 1 || !result.equals(PluginPackageVerifier.verifyAndMeasure(Path.of(args[1]), LIMITS))) {
                throw new AssertionError("cache was not reused");
            }
            System.out.println("CACHE_HIT_OK");
        }
    }

    @Test @DisplayName("等长且时间戳未变的内容替换使缓存失效")
    void invalidatesSameMetadataReplacement() throws IOException {
        Path directory = temporary.resolve("development");
        Files.createDirectories(directory.resolve("classes/example"));
        Path source = directory.resolve("classes/example/Feature.class");
        Files.writeString(source, "old-bytes", StandardCharsets.UTF_8);
        var time = Files.getLastModifiedTime(source);
        var cache = new PluginScanCache(temporary.resolve("cache"));
        cache.verifyDevelopment(directory, LIMITS);
        Files.writeString(source, "new-bytes", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(source, time);
        cache.verifyDevelopment(directory, LIMITS);
        assertThat(cache.statistics().hits()).isZero();
        assertThat(cache.statistics().writes()).isEqualTo(2);
        cache.verifyDevelopment(directory, LIMITS);
        assertThat(cache.statistics().hits()).isEqualTo(1);
        Files.createDirectory(directory.resolve("empty"));
        cache.verifyDevelopment(directory, LIMITS);
        assertThat(cache.statistics().writes()).isEqualTo(3);
    }

    @Test @DisplayName("库与描述符内容变化均失效而随机清理标记可复用")
    void invalidatesDevelopmentLibrariesAndDescriptor() throws IOException {
        Path directory = temporary.resolve("dev");
        Files.createDirectories(directory.resolve("lib"));
        Path library = archive("lib.jar", "library/Helper.class", "library");
        Files.copy(library, directory.resolve("lib/helper.jar"));
        Files.writeString(directory.resolve("plugin.properties"), "id=demo", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".pixiv-plugin-dev-snapshot"), "marker-a", StandardCharsets.UTF_8);
        var cache = new PluginScanCache(temporary.resolve("cache"));
        var original = cache.verifyDevelopment(directory, LIMITS);
        Files.writeString(directory.resolve(".pixiv-plugin-dev-snapshot"), "marker-b", StandardCharsets.UTF_8);
        assertThat(cache.verifyDevelopment(directory, LIMITS)).isEqualTo(original);
        assertThat(cache.statistics().hits()).isEqualTo(1);
        archive("lib.jar", "library/Helper.class", "changed");
        Files.copy(library, directory.resolve("lib/helper.jar"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThat(cache.verifyDevelopment(directory, LIMITS).archiveDigests()).isNotEqualTo(original.archiveDigests());
        Files.writeString(directory.resolve("plugin.properties"), "id=next", StandardCharsets.UTF_8);
        cache.verifyDevelopment(directory, LIMITS);
        assertThat(cache.statistics().writes()).isEqualTo(3);
    }

    @Test @DisplayName("限制收紧后重新校验且失败输入不写入缓存")
    void preservesLimitsAndFailure() throws IOException {
        Path archive = archive("sample.jar", "example/Feature.class", "long-content");
        var cache = new PluginScanCache(temporary.resolve("cache"));
        cache.verifyArchive(archive, LIMITS);
        var strict = new PluginPackageLimits(LIMITS.maxArchiveBytes(), LIMITS.maxEntries(),
                LIMITS.maxTotalUncompressedBytes(), 1, LIMITS.maxDescriptorBytes(), LIMITS.maxCompressionRatio(),
                LIMITS.maxEntryNameLength(), LIMITS.maxEntryDepth());
        assertThatThrownBy(() -> cache.verifyArchive(archive, strict)).isInstanceOf(PluginPackageException.class);
        assertThat(cache.statistics().hits()).isZero();
        assertThat(cache.statistics().writes()).isEqualTo(1);
        Files.writeString(archive, "not a zip", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> cache.verifyArchive(archive, LIMITS)).isInstanceOf(PluginPackageException.class);
        assertThat(cache.statistics().writes()).isEqualTo(1);
    }

    @Test @DisplayName("篡改与截断缓存自动回退并重建")
    void repairsCorruption() throws IOException {
        Path archive = archive("sample.jar", "example/Feature.class", "class-bytes");
        Path root = temporary.resolve("cache");
        var cache = new PluginScanCache(root);
        var expected = cache.verifyArchive(archive, LIMITS);
        Path entry;
        try (var paths = Files.list(root)) { entry = paths.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow(); }
        byte[] bytes = Files.readAllBytes(entry);
        bytes[100] ^= 1;
        Files.write(entry, bytes);
        assertThat(cache.verifyArchive(archive, LIMITS)).isEqualTo(expected);
        Files.write(entry, new byte[2]);
        assertThat(cache.verifyArchive(archive, LIMITS)).isEqualTo(expected);
        assertThat(new PluginScanCache(root).verifyArchive(archive, LIMITS)).isEqualTo(expected);
        assertThat(cache.statistics().failures()).isEqualTo(2);
        assertThat(cache.statistics().writes()).isEqualTo(3);
        Files.write(root.resolve("cache.key"), new byte[2]);
        assertThat(cache.verifyArchive(archive, LIMITS)).isEqualTo(expected);
        assertThat(cache.statistics().failures()).isEqualTo(3);
        var repaired = new PluginScanCache(root);
        assertThat(repaired.verifyArchive(archive, LIMITS)).isEqualTo(expected);
        assertThat(repaired.statistics().hits()).isEqualTo(1);
    }

    @Test @DisplayName("扫描实现规则变化后旧缓存不能命中")
    void invalidatesScannerChanges() throws IOException {
        Path archive = archive("sample.jar", "example/Feature.class", "class-bytes");
        var cache = new PluginScanCache(temporary.resolve("cache"));
        cache.verifyArchive(archive, LIMITS);
        try (var verifier = org.mockito.Mockito.mockStatic(PluginPackageVerifier.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            verifier.when(PluginPackageVerifier::scanImplementationIdentity).thenReturn("different-scanner");
            cache.verifyArchive(archive, LIMITS);
            assertThat(cache.statistics().hits()).isZero();
            assertThat(cache.statistics().writes()).isEqualTo(2);
        }
    }

    @Test @DisplayName("认证有效的其它条目也不能借用到不同内容")
    void rejectsSwappedEntries() throws IOException {
        Path first = archive("first.jar", "first/Feature.class", "first");
        Path second = archive("second.jar", "second/Feature.class", "second");
        Path root = temporary.resolve("cache");
        var cache = new PluginScanCache(root);
        var expected = cache.verifyArchive(first, LIMITS);
        Path firstEntry;
        try (var paths = Files.list(root)) { firstEntry = paths.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow(); }
        cache.verifyArchive(second, LIMITS);
        Path secondEntry;
        try (var paths = Files.list(root)) { secondEntry = paths.filter(p -> p.toString().endsWith(".bin") && !p.equals(firstEntry)).findFirst().orElseThrow(); }
        Files.write(firstEntry, Files.readAllBytes(secondEntry));
        assertThat(cache.verifyArchive(first, LIMITS)).isEqualTo(expected);
        assertThat(cache.statistics().hits()).isZero();
        assertThat(cache.statistics().failures()).isEqualTo(1);
    }

    @Test @DisplayName("目录新增空条目仍会触发条目上限")
    void emptyDirectoryInvalidatesBudget() throws IOException {
        Path directory = Files.createDirectory(temporary.resolve("dev"));
        Files.writeString(directory.resolve("data.txt"), "data", StandardCharsets.UTF_8);
        var limits = new PluginPackageLimits(10000, 1, 10000, 10000, 1000, 200, 1024, 64);
        var cache = new PluginScanCache(temporary.resolve("cache"));
        cache.verifyDevelopment(directory, limits);
        Files.createDirectory(directory.resolve("empty"));
        assertThatThrownBy(() -> cache.verifyDevelopment(directory, limits)).isInstanceOf(PluginPackageException.class);
        assertThat(cache.statistics().hits()).isZero();
    }

    @Test @DisplayName("缓存路径不可用或锁被占用不阻断原始扫描")
    void unavailableOrBusyCacheFallsBack() throws IOException {
        Path archive = archive("sample.jar", "example/Feature.class", "class-bytes");
        var expected = PluginPackageVerifier.verifyAndMeasure(archive, LIMITS);
        Path invalid = temporary.resolve("file");
        Files.writeString(invalid, "untouched", StandardCharsets.UTF_8);
        assertThat(new PluginScanCache(invalid).verifyArchive(archive, LIMITS)).isEqualTo(expected);
        assertThat(Files.readString(invalid)).isEqualTo("untouched");
        Path root = temporary.resolve("cache");
        new PluginScanCache(root).verifyArchive(archive, LIMITS);
        try (var channel = FileChannel.open(root.resolve("cache.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var busy = new PluginScanCache(root);
            assertThat(busy.verifyArchive(archive, LIMITS)).isEqualTo(expected);
            assertThat(busy.statistics().hits()).isZero();
            assertThat(busy.statistics().writes()).isZero();
        }
    }

    @Test @DisplayName("缓存条目数有界且旧条目自动淘汰")
    void evictsOldEntries() throws IOException {
        var files = new ScanCacheFiles(temporary.resolve("cache"));
        assertThat(files.write("0".repeat(64), new byte[1])).isTrue();
        Path oldest = temporary.resolve("cache").resolve("0".repeat(64) + ".bin");
        Files.setLastModifiedTime(oldest, java.nio.file.attribute.FileTime.fromMillis(0));
        for (int i = 1; i < ScanCacheFiles.MAX_ENTRIES; i++) {
            Files.copy(oldest, temporary.resolve("cache").resolve(String.format("%064x.bin", i)));
        }
        assertThat(files.write(String.format("%064x", ScanCacheFiles.MAX_ENTRIES), new byte[1])).isTrue();
        assertThat(Files.exists(oldest)).isFalse();
        try (var paths = Files.list(temporary.resolve("cache"))) {
            assertThat(paths.filter(p -> p.toString().endsWith(".bin")).count()).isEqualTo(ScanCacheFiles.MAX_ENTRIES);
        }
        assertThat(files.read(String.format("%064x", ScanCacheFiles.MAX_ENTRIES))).containsExactly((byte) 0);
        assertThat(files.write("f".repeat(64), new byte[ScanCacheFiles.MAX_ENTRY_BYTES])).isFalse();
    }

    @Test @DisplayName("累计字节达到上限时淘汰旧条目")
    void evictsForTotalBytes() throws IOException {
        Path root = temporary.resolve("cache");
        var files = new ScanCacheFiles(root);
        byte[] payload = new byte[ScanCacheFiles.MAX_ENTRY_BYTES - 32];
        files.write("0".repeat(64), payload);
        Path oldest = root.resolve("0".repeat(64) + ".bin");
        Files.setLastModifiedTime(oldest, java.nio.file.attribute.FileTime.fromMillis(0));
        int count = (int) (ScanCacheFiles.MAX_TOTAL_BYTES / ScanCacheFiles.MAX_ENTRY_BYTES);
        for (int i = 1; i < count; i++) Files.copy(oldest, root.resolve(String.format("%064x.bin", i)));
        assertThat(files.write("f".repeat(64), payload)).isTrue();
        assertThat(Files.exists(oldest)).isFalse();
        try (var paths = Files.list(root)) {
            long total = 0;
            for (Path entry : paths.filter(p -> p.toString().endsWith(".bin")).toList()) total += Files.size(entry);
            assertThat(total).isEqualTo(ScanCacheFiles.MAX_TOTAL_BYTES);
        }
    }

    @Test @DisplayName("纯值编码超出单条预算时中止且拒绝尾随数据")
    void boundsEncodingAndDecoding() throws IOException {
        var packages = new java.util.HashSet<String>();
        for (int i = 0; i < 30000; i++) packages.add("long.package.".repeat(7) + i);
        var space = new top.sywyar.pixivdownload.plugin.runtime.install.model.PluginClassSpace(
                java.util.Map.of("", packages), java.util.Map.of(), java.util.Set.of(), java.util.Set.of());
        assertThatThrownBy(() -> ScanCacheCodec.encode("identity",
                new PluginPackageVerifier.VerificationUsage(30000, 30000, space)))
                .isInstanceOf(IOException.class).hasMessage("CACHE_ENTRY_CAPACITY");
        byte[] encoded = ScanCacheCodec.encode("identity", new PluginPackageVerifier.VerificationUsage(1, 1));
        assertThatThrownBy(() -> ScanCacheCodec.decode("identity", java.util.Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(IOException.class).hasMessage("CACHE_TRAILING_DATA");
    }

    private Path archive(String file, String name, String content) throws IOException {
        Path path = temporary.resolve(file);
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            var entry = new ZipEntry(name);
            entry.setTime(0);
            out.putNextEntry(entry);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path;
    }
}
