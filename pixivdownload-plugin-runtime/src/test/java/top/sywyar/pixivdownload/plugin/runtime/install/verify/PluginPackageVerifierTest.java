package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;

@DisplayName("插件包资源规模安全扫描：Zip Bomb / 解压资源耗尽防护")
class PluginPackageVerifierTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("合法小包：在默认上限内通过扫描，不抛")
    void legitPackagePasses() {
        Path zip = PluginPackageFixtures.explodedZip(dir.resolve("ok.zip"),
                "ext", "1.0.0", null, "com.example.P");
        assertThatCode(() -> PluginPackageVerifier.verify(zip, PluginPackageLimits.defaults()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("entry 数量超限：TOO_LARGE")
    void rejectsTooManyEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", PluginPackageFixtures.bytes("a"));
        entries.put("b.txt", PluginPackageFixtures.bytes("b"));
        entries.put("c.txt", PluginPackageFixtures.bytes("c"));
        Path zip = dir.resolve("many.zip");
        PluginPackageFixtures.writeZip(zip, entries);

        assertTooLarge(zip, limits(64 << 20, 2, 256L << 20, 64 << 20, 1 << 20, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("总解压字节超限：TOO_LARGE（实际读取字节累计，非 header）")
    void rejectsTotalUncompressed() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.bin", new byte[50]);
        entries.put("b.bin", new byte[50]);
        entries.put("c.bin", new byte[50]);
        Path zip = dir.resolve("total.zip");
        PluginPackageFixtures.writeZip(zip, entries);

        // 总上限 100，三个 50 字节 entry 共 150 → 超限
        assertTooLarge(zip, limits(64 << 20, 20000, 100, 64 << 20, 1 << 20, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("单 entry 解压字节超限：TOO_LARGE（同样覆盖 single-jar zip 内的 inner jar）")
    void rejectsSingleEntryTooLarge() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("inner.jar", new byte[2048]);
        Path zip = dir.resolve("bigentry.zip");
        PluginPackageFixtures.writeZip(zip, entries);

        // 单 entry 上限 1024，inner.jar 2048 字节 → 超限
        assertTooLarge(zip, limits(64 << 20, 20000, 256L << 20, 1024, 1 << 20, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("单 entry 超限失败仍记录触发拒绝的实际读取字节")
    void reportsBytesReadWhenSingleEntryCrossesLimit() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("payload.bin", new byte[]{1, 2});
        Path zip = dir.resolve("entry-usage.zip");
        PluginPackageFixtures.writeZip(zip, entries);

        assertThatThrownBy(() -> PluginPackageVerifier.verifyAndMeasure(
                zip, limits(64 << 20, 20000, 100, 1, 1 << 20, Long.MAX_VALUE)))
                .isInstanceOf(PluginPackageException.class)
                .satisfies(error -> {
                    PluginPackageException failure = (PluginPackageException) error;
                    assertThat(failure.reason()).isEqualTo(PluginPackageException.Reason.TOO_LARGE);
                    assertThat(failure.hasVerificationUsage()).isTrue();
                    assertThat(failure.consumedEntries()).isEqualTo(1);
                    assertThat(failure.consumedUncompressedBytes()).isEqualTo(2L);
                });
    }

    @Test
    @DisplayName("归档体积超限：TOO_LARGE（解压前即按磁盘体积拒绝）")
    void rejectsArchiveTooLarge() {
        Path zip = PluginPackageFixtures.explodedZip(dir.resolve("arch.zip"),
                "ext", "1.0.0", null, "com.example.P");
        // 归档体积上限 10 字节，任何真实 zip 都更大 → 超限
        assertTooLarge(zip, limits(10, 20000, 256L << 20, 64 << 20, 1 << 20, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("压缩比过高（高度可压缩的大 entry）：TOO_LARGE")
    void rejectsHighCompressionRatio() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        // 256 KiB 全零：deflate 压缩极小，压缩比远超 200，且超过 64 KiB 地板阈值
        entries.put("zeros.bin", new byte[256 * 1024]);
        Path zip = dir.resolve("bomb.zip");
        PluginPackageFixtures.writeZip(zip, entries);

        // 解压字节 / 总字节都在默认上限内，仅压缩比触发拒绝
        assertTooLarge(zip, PluginPackageLimits.defaults());
    }

    @Test
    @DisplayName("不可压缩的大 entry：按实际读取字节而非 header 声明拒绝")
    void countsActualBytesNotHeader() {
        byte[] random = new byte[4096];
        new Random(42).nextBytes(random); // 不可压缩、压缩比≈1，仅靠实际字节计数触发单 entry 上限
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("blob.bin", random);
        Path zip = dir.resolve("blob.zip");
        PluginPackageFixtures.writeZip(zip, entries);

        assertTooLarge(zip, limits(64 << 20, 20000, 256L << 20, 1024, 1 << 20, Long.MAX_VALUE));
    }

    @Test
    @DisplayName(".jar 也被扫描（不只 .zip）：jar 内 entry 超总解压上限 → TOO_LARGE")
    void scansJarNotOnlyZip() {
        byte[] jarBytes = PluginPackageFixtures.pluginJarBytes("ext", "1.0.0", null, "com.example.P");
        Path jar = dir.resolve("plugin.jar");
        try {
            Files.write(jar, jarBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 总解压上限设为 1 字节，jar 内任何 entry 都会超 → 证明 .jar 同样被扫描
        assertTooLarge(jar, limits(64 << 20, 20000, 1, 64 << 20, 1 << 20, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("JAR-with-lib 的私有依赖内部压缩炸弹也按同一预算拒绝")
    void rejectsCompressionBombInsidePrivateLibrary() {
        Map<String, byte[]> privateLibraryEntries = new LinkedHashMap<>();
        privateLibraryEntries.put("private/zeros.bin", new byte[256 * 1024]);
        Map<String, byte[]> pluginEntries = new LinkedHashMap<>();
        pluginEntries.put("plugin.properties", PluginPackageFixtures.bytes("plugin.id=ext\n"));
        pluginEntries.put("lib/private-lib.jar", PluginPackageFixtures.zipBytes(privateLibraryEntries));
        Path jar = dir.resolve("private-lib-bomb.jar");
        PluginPackageFixtures.writeZip(jar, pluginEntries);

        assertTooLarge(jar, PluginPackageLimits.defaults());
    }

    @Test
    @DisplayName("根插件 JAR 不得夹带宿主共享命名空间")
    void rejectsHostControlledClassInRootPluginJar() {
        Path jar = dir.resolve("shared-class.jar");
        PluginPackageFixtures.writeZip(jar, Map.of(
                "plugin.properties", PluginPackageFixtures.bytes("plugin.id=ext\n"),
                "top/sywyar/pixivdownload/plugin/api/plugin/PixivFeaturePlugin.class",
                PluginPackageFixtures.bytes("shadow")
        ));

        assertUnsafe(jar);
    }

    @Test
    @DisplayName("私有依赖与 multi-release entry 也不得夹带 PF4J 类")
    void rejectsHostControlledClassInPrivateMultiReleaseJar() {
        byte[] privateJar = PluginPackageFixtures.zipBytes(Map.of(
                "META-INF/versions/17/org/pf4j/PluginManager.class",
                PluginPackageFixtures.bytes("shadow")
        ));
        Path jar = dir.resolve("shared-private-lib.jar");
        PluginPackageFixtures.writeZip(jar, Map.of(
                "plugin.properties", PluginPackageFixtures.bytes("plugin.id=ext\n"),
                "lib/private.jar", privateJar
        ));

        assertUnsafe(jar);
    }

    @Test
    @DisplayName("根 inner JAR 的内部 entry 数也计入统一上限")
    void countsEntriesInsideRootInnerJar() {
        Map<String, byte[]> innerEntries = new LinkedHashMap<>();
        innerEntries.put("plugin.properties", PluginPackageFixtures.bytes("plugin.id=ext\n"));
        innerEntries.put("a.class", PluginPackageFixtures.bytes("a"));
        innerEntries.put("b.class", PluginPackageFixtures.bytes("b"));
        Map<String, byte[]> outerEntries = new LinkedHashMap<>();
        outerEntries.put("plugin.jar", PluginPackageFixtures.zipBytes(innerEntries));
        Path zip = dir.resolve("inner-entry-count.zip");
        PluginPackageFixtures.writeZip(zip, outerEntries);

        assertTooLarge(zip, limits(64 << 20, 3, 256L << 20, 64 << 20, 1 << 20, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("重复 entry：逐字相同的归档名称按 UNSAFE 拒绝")
    void rejectsExactDuplicateEntries() throws IOException {
        Path jar = dir.resolve("duplicate.jar");
        PluginPackageFixtures.writeDuplicateEntryZip(jar, "plugin.properties",
                PluginPackageFixtures.bytes("plugin.id=first\n"),
                PluginPackageFixtures.bytes("plugin.id=second\n"));
        assertUnsafe(jar);

        Path zip = dir.resolve("nested-duplicate.zip");
        PluginPackageFixtures.writeZip(zip, Map.of("plugin.jar", Files.readAllBytes(jar)));
        assertUnsafe(zip);
    }

    @Test
    @DisplayName("可移植名称冲突：分隔符、Unicode、大小写与 Windows 文件名语义统一拒绝")
    void rejectsPortableEntryNameCollisions() {
        List<CollisionCase> cases = List.of(
                new CollisionCase("case", "plugin.properties", "Plugin.Properties"),
                new CollisionCase("separator", "classes/Marker.class", "classes\\Marker.class"),
                new CollisionCase("unicode", "classes/caf\u00e9.class", "classes/cafe\u0301.class"),
                new CollisionCase("trailing-dot", "classes/Marker.class", "classes/Marker.class."),
                new CollisionCase("trailing-space", "classes/Marker.class", "classes/Marker.class ")
        );
        for (CollisionCase collision : cases) {
            Path zip = dir.resolve(collision.label() + ".zip");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put(collision.first(), PluginPackageFixtures.bytes("first"));
            entries.put(collision.second(), PluginPackageFixtures.bytes("second"));
            PluginPackageFixtures.writeZip(zip, entries);

            assertThatThrownBy(() -> PluginPackageVerifier.verify(zip, PluginPackageLimits.defaults()))
                    .as(collision.label())
                    .isInstanceOfSatisfying(PluginPackageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(PluginPackageException.Reason.UNSAFE));
        }
    }

    @Test
    @DisplayName("Windows 保留设备名：普通扩展名与兼容数字写法均按 UNSAFE 拒绝")
    void rejectsWindowsReservedDeviceNames() {
        for (String entryName : List.of("classes/NUL.txt", "classes/COM\u00b9.log")) {
            Path zip = dir.resolve(entryName.contains("NUL") ? "nul.zip" : "com-superscript.zip");
            PluginPackageFixtures.writeZip(zip, Map.of(entryName, PluginPackageFixtures.bytes("payload")));

            assertUnsafe(zip);
        }
    }

    // ---------- helpers ----------

    private void assertTooLarge(Path archive, PluginPackageLimits limits) {
        assertThatThrownBy(() -> PluginPackageVerifier.verify(archive, limits))
                .isInstanceOf(PluginPackageException.class)
                .satisfies(e -> assertThat(((PluginPackageException) e).reason())
                        .isEqualTo(PluginPackageException.Reason.TOO_LARGE));
    }

    private void assertUnsafe(Path archive) {
        assertThatThrownBy(() -> PluginPackageVerifier.verify(archive, PluginPackageLimits.defaults()))
                .isInstanceOfSatisfying(PluginPackageException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(PluginPackageException.Reason.UNSAFE));
    }

    private static PluginPackageLimits limits(long archive, int entries, long total, long entry,
                                              long descriptor, long ratio) {
        return new PluginPackageLimits(archive, entries, total, entry, descriptor, ratio);
    }

    private record CollisionCase(String label, String first, String second) {
    }
}
