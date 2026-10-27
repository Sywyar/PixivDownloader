package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("归档本地头与中央目录必须指向相同条目")
class ZipEntryIdentityTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"", "lib/private.jar", "inner.jar"})
    @DisplayName("安装事务在激活前拒绝根包及嵌套库的名称歧义")
    void rejectsAmbiguousNameBeforeInstall(String nested) throws Exception {
        byte[] content = PluginPackageFixtures.pluginJarBytes("duplicate", "1.0.0", null, "sample.Plugin",
                Map.of("review/contract/Api.class", new byte[]{1, 2, 3}));
        byte[] from = "review/contract/Api.class".getBytes(StandardCharsets.UTF_8);
        byte[] to = "review/innocent/Api.class".getBytes(StandardCharsets.UTF_8);
        int offset = indexOf(content, from);
        System.arraycopy(to, 0, content, offset, to.length);
        Path archive = directory.resolve("ambiguous.jar");
        if (nested.isEmpty()) Files.write(archive, content);
        else PluginPackageFixtures.writeZip(archive, Map.of(nested, content, "plugin.properties",
                PluginPackageFixtures.bytes(PluginPackageFixtures.pluginProperties("outer", "1.0.0", null, "sample.Plugin"))));
        assertThatThrownBy(() -> PluginPackageVerifier.verify(archive, PluginPackageLimits.defaults()))
                .isInstanceOf(PluginPackageException.class).hasMessageContaining("central directory");
        try (var installer = new ExternalPluginInstaller(directory.resolve("installed"))) {
            assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
            var result = installer.prepareTransaction(archive, false,
                    PluginPackageOrigin.localUnsignedUpload(PluginPackageIntegrity.sha256Hex(archive)));
            assertThat(result.readyToCommit()).isFalse();
            assertThat(result.result().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_MALFORMED);
            assertThat(installer.listInstalled()).isEmpty();
        }
    }

    @Test
    @DisplayName("重复中央目录偏移和未列出的本地条目均拒绝")
    void rejectsOffsetAndCountMismatch() throws Exception {
        byte[] valid = PluginPackageFixtures.zipBytes(Map.of("first.txt", new byte[]{1}, "other.txt", new byte[]{2}));
        int first = indexOf(valid, new byte[]{0x50, 0x4b, 1, 2});
        int next = first + 46 + "first.txt".length();
        for (boolean duplicateOffset : new boolean[]{true, false}) {
            byte[] invalid = valid.clone();
            ByteBuffer bytes = ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN);
            if (duplicateOffset) bytes.putInt(next + 42, bytes.getInt(first + 42));
            else bytes.putInt(first + 42, 1);
            Path archive = directory.resolve("offset.jar");
            Files.write(archive, invalid);
            assertThatThrownBy(() -> PluginPackageVerifier.verify(archive, PluginPackageLimits.defaults()))
                    .isInstanceOf(PluginPackageException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 10, 16, 20, 24})
    @DisplayName("中央目录的标志、方法、CRC 和长度必须与实际读取一致")
    void rejectsCentralMetadataMismatch(int field) throws Exception {
        byte[] raw = PluginPackageFixtures.zipBytes(Map.of("sample/Api.class", new byte[]{1, 2, 3}));
        int central = indexOf(raw, new byte[]{0x50, 0x4b, 1, 2});
        raw[central + field] ^= 1;
        Path archive = directory.resolve("metadata.jar");
        PluginPackageFixtures.writeZip(archive, Map.of("lib/api.jar", raw));
        assertThatThrownBy(() -> PluginPackageVerifier.verify(archive, PluginPackageLimits.defaults()))
                .isInstanceOfSatisfying(PluginPackageException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(PluginPackageException.Reason.MALFORMED));
    }

    @Test
    @DisplayName("保留无签名 data descriptor 及未设置 UTF8 标志的有效 UTF8 名称")
    void acceptsDescriptorAndEncodingVariants() throws Exception {
        byte[] original = PluginPackageFixtures.zipBytes(Map.of("资源.txt", new byte[]{1, 2, 3}));
        int descriptor = indexOf(original, new byte[]{0x50, 0x4b, 7, 8});
        int central = indexOf(original, new byte[]{0x50, 0x4b, 1, 2});
        original[7] &= ~8;
        original[central + 9] &= ~8;
        byte[] raw = new byte[original.length - 4];
        System.arraycopy(original, 0, raw, 0, descriptor);
        System.arraycopy(original, descriptor + 4, raw, descriptor, original.length - descriptor - 4);
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(raw.length - 6, central - 4);
        Path archive = directory.resolve("descriptor.jar");
        Files.write(archive, raw);
        assertThat(PluginPackageVerifier.verifyAndMeasure(archive, PluginPackageLimits.defaults()).entryCount()).isEqualTo(1);
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry("资源.txt"))) {
                assertThat(input.readAllBytes()).containsExactly(1, 2, 3);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("嵌套 JAR 注释和填充中不得藏有另一个中央目录结束记录")
    void rejectsAlternativeEndDirectory(boolean insideComment) throws Exception {
        byte[] original = PluginPackageFixtures.zipBytes(Map.of("sample/Api.class", new byte[]{1}));
        byte[] altered = java.util.Arrays.copyOf(original, original.length + 22);
        System.arraycopy(original, original.length - 22, altered, original.length, 22);
        if (insideComment) ByteBuffer.wrap(altered).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(original.length - 2, (short) 22);
        Path archive = directory.resolve("alternate.jar");
        PluginPackageFixtures.writeZip(archive, Map.of("lib/api.jar", altered));
        assertThatThrownBy(() -> PluginPackageVerifier.verify(archive, PluginPackageLimits.defaults()))
                .isInstanceOf(PluginPackageException.class).hasMessageContaining("central directory");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("根归档和嵌套 JAR 保留不足结束记录长度的注释签名")
    void acceptsShortEndSignatureInComment(boolean nested) throws Exception {
        for (int prefixLength : new int[]{8, 8190}) {
            for (int suffixLength : new int[]{0, 17}) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (ZipOutputStream zip = new ZipOutputStream(output)) {
                    zip.setComment("c".repeat(prefixLength) + "PK\u0005\u0006" + "x".repeat(suffixLength));
                    zip.putNextEntry(new ZipEntry("probe.txt"));
                    zip.write(new byte[]{1, 2, 3});
                    zip.closeEntry();
                }
                byte[] raw = output.toByteArray();
                Path rawArchive = directory.resolve("comment.jar");
                Files.write(rawArchive, raw);
                try (var zip = new java.util.zip.ZipFile(rawArchive.toFile());
                     var input = zip.getInputStream(zip.getEntry("probe.txt"))) {
                    assertThat(input.readAllBytes()).containsExactly(1, 2, 3);
                }
                Path archive = rawArchive;
                if (nested) {
                    archive = directory.resolve("outer.jar");
                    PluginPackageFixtures.writeZip(archive, Map.of("lib/probe.jar", raw));
                }
                var usage = PluginPackageVerifier.verifyAndMeasure(archive, PluginPackageLimits.defaults());
                assertThat(usage.entryCount()).isEqualTo(nested ? 2 : 1);
                if (nested) assertThat(usage.classSpace().archiveDigests()).containsEntry("lib/probe.jar",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("根归档和嵌套 JAR 继续拒绝 JDK 可读取的第二份完整目录")
    void rejectsSecondCentralDirectory(boolean nested) throws Exception {
        byte[] original = PluginPackageFixtures.zipBytes(Map.of("probe.txt", new byte[]{1, 2, 3}));
        int central = indexOf(original, new byte[]{0x50, 0x4b, 1, 2});
        byte[] altered = java.util.Arrays.copyOf(original, original.length + original.length - central);
        System.arraycopy(original, central, altered, original.length, original.length - central);
        ByteBuffer.wrap(altered).order(ByteOrder.LITTLE_ENDIAN).putInt(altered.length - 6, original.length);
        Path archive = directory.resolve("second-directory.jar");
        Files.write(archive, altered);
        try (var zip = new java.util.zip.ZipFile(archive.toFile());
             var input = zip.getInputStream(zip.getEntry("probe.txt"))) {
            assertThat(input.readAllBytes()).containsExactly(1, 2, 3);
        }
        if (nested) PluginPackageFixtures.writeZip(archive, Map.of("lib/probe.jar", altered));
        assertThatThrownBy(() -> PluginPackageVerifier.verify(archive, PluginPackageLimits.defaults()))
                .isInstanceOf(PluginPackageException.class).hasMessageContaining("central directory");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("UTF8 名称、额外字段、注释和普通数据描述符保留嵌套原始摘要")
    void acceptsSupportedLayouts(boolean stored) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.setComment("archive comment");
            ZipEntry entry = new ZipEntry("resources/中文.txt");
            entry.setExtra(new byte[]{0x34, 0x12, 2, 0, 9, 8});
            entry.setComment("entry comment");
            byte[] data = "正文".getBytes(StandardCharsets.UTF_8);
            if (stored) {
                CRC32 crc = new CRC32();
                crc.update(data);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCrc(crc.getValue());
            }
            zip.putNextEntry(entry);
            zip.write(data);
            zip.closeEntry();
        }
        byte[] raw = output.toByteArray();
        Path archive = directory.resolve("valid.jar");
        PluginPackageFixtures.writeZip(archive, Map.of("lib/api.jar", raw));
        var usage = PluginPackageVerifier.verifyAndMeasure(archive, PluginPackageLimits.defaults());
        assertThat(usage.classSpace().archiveDigests()).containsEntry("lib/api.jar",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        assertThat(usage.entryCount()).isEqualTo(2);
    }

    private static int indexOf(byte[] input, byte[] expected) {
        for (int i = 0; i <= input.length - expected.length; i++) {
            if (java.util.Arrays.equals(input, i, i + expected.length, expected, 0, expected.length)) return i;
        }
        throw new AssertionError("fixture marker missing");
    }
}
