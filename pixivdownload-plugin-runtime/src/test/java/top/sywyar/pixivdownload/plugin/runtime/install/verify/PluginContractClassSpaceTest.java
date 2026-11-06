package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("独立契约 API 字节与类命名空间准入")
class PluginContractClassSpaceTest {
    @TempDir Path directory;

    @Test
    @DisplayName("原始库摘要绑定成功，根目录和嵌套库的重复包均拒绝")
    void rejectsCopiesAndSplitPackages() throws Exception {
        var contract = inspect(contract(Map.of("sample/contract/Api.class", new byte[]{1}), Map.of(), null));
        assertThat(PluginClassSpaceVerifier.contractPackages(contract.classSpace(), contract.descriptor()))
                .containsExactly("sample.contract");
        for (Map<String, byte[]> entries : java.util.List.of(
                Map.of("sample/contract/Duplicate.class", new byte[]{1}),
                Map.of("classes/sample/contract/Duplicate.class", new byte[]{1}),
                Map.of("META-INF/versions/17/sample/contract/Duplicate.class", new byte[]{1}),
                Map.of("lib/private.jar", PluginPackageFixtures.zipBytes(Map.of("sample/contract/Duplicate.class", new byte[]{1}))),
                Map.of("inner.jar", PluginPackageFixtures.pluginJarBytes("other", "3.2.1", null,
                        "sample.Other", Map.of("sample/contract/Duplicate.class", new byte[]{1}))))) {
            Path other = directory.resolve("other.jar");
            Map<String, byte[]> contents = new LinkedHashMap<>(entries);
            contents.put("plugin.properties", PluginPackageFixtures.bytes(
                    PluginPackageFixtures.pluginProperties("other", "3.2.1", null, "sample.Other")));
            PluginPackageFixtures.writeZip(other, contents);
            var duplicate = inspect(other);
            assertThatThrownBy(() -> PluginClassSpaceVerifier.requireCompatible(
                    contract.classSpace(), contract.descriptor(), duplicate.descriptor(), duplicate.classSpace()))
                    .isInstanceOf(PluginPackageException.class).hasMessageContaining("split contract package");
        }
    }

    @Test
    @DisplayName("摘要错误、多版本 API 和包装层重复契约在创建 loader 前拒绝")
    void rejectsInvalidContractArtifact() throws Exception {
        assertThatThrownBy(() -> inspect(contract(Map.of("sample/contract/Api.class", new byte[]{1}), Map.of(), "0".repeat(64))))
                .isInstanceOf(PluginPackageException.class);
        assertThatThrownBy(() -> inspect(contract(Map.of("sample/contract/Api.class", new byte[]{1},
                        "META-INF/versions/17/sample/contract/Api.class", new byte[]{2}), Map.of(), null)))
                .isInstanceOf(PluginPackageException.class);
        assertThatThrownBy(() -> inspect(contract(Map.of("sample/contract/Api.class", new byte[]{1}),
                        Map.of("sample/contract/Other.class", new byte[]{2}), null)))
                .isInstanceOf(PluginPackageException.class);
    }

    @Test
    @DisplayName("契约及共存插件不得通过清单或索引加载未扫描类型")
    void rejectsImplicitClassPaths() throws Exception {
        var implicit = Map.of("META-INF/MANIFEST.MF", PluginPackageFixtures.bytes(
                "Manifest-Version: 1.0\r\nClass-Path: hidden.jar\r\n\r\n"));
        var api = new LinkedHashMap<String, byte[]>(implicit);
        api.put("sample/contract/Api.class", new byte[]{1});
        assertThatThrownBy(() -> inspect(contract(api, Map.of(), null)))
                .isInstanceOf(PluginPackageException.class);
        assertThatThrownBy(() -> inspect(contract(Map.of("sample/contract/Api.class", new byte[]{1}),
                Map.of("META-INF/INDEX.LIST", new byte[]{1}), null)))
                .isInstanceOf(PluginPackageException.class);
        var contract = inspect(contract(Map.of("sample/contract/Api.class", new byte[]{1}), Map.of(), null));
        var contents = new LinkedHashMap<String, byte[]>(implicit);
        contents.put("plugin.properties", PluginPackageFixtures.bytes(
                PluginPackageFixtures.pluginProperties("other", "3.2.1", null, "sample.Other")));
        Path otherPath = directory.resolve("implicit.jar");
        PluginPackageFixtures.writeZip(otherPath, contents);
        var other = inspect(otherPath);
        assertThatThrownBy(() -> PluginClassSpaceVerifier.requireCompatible(
                contract.classSpace(), contract.descriptor(), other.descriptor(), other.classSpace()))
                .isInstanceOf(PluginPackageException.class).hasMessageContaining("implicit");
    }

    @Test
    @DisplayName("私有库自引用索引可与契约共存，外部、畸形与超限索引仍拒绝")
    void selfContainedIndexDoesNotExtendClasspath() throws Exception {
        var contract = inspect(contract(Map.of("sample/contract/Api.class", new byte[]{1}), Map.of(), null));
        String safe = "JarIndex-Version: 1.0\r\n\r\nprivate.jar\r\nMETA-INF\r\nsample/privateapi\r\n\r\n";
        for (String index : java.util.List.of(safe,
                safe + "hidden.jar\nother/package\n", safe.replace("private.jar", "../private.jar"),
                safe.replace("private.jar", "https://example.test/private.jar"),
                safe.replace("sample/privateapi", "hidden.jar\nother/package"),
                safe.replace("META-INF", "invalid\u0000"), safe.stripTrailing() + "\n"
                        + java.util.stream.IntStream.range(0, 2000).mapToObj(i -> java.util.UUID.randomUUID().toString())
                        .collect(java.util.stream.Collectors.joining("\n")))) {
            Path other = directory.resolve("indexed.jar");
            PluginPackageFixtures.writeZip(other, Map.of(
                    "plugin.properties", PluginPackageFixtures.bytes(
                            PluginPackageFixtures.pluginProperties("indexed", "3.2.1", null, "sample.Other")),
                    "lib/private.jar", PluginPackageFixtures.zipBytes(Map.of(
                            "sample/privateapi/Value.class", new byte[]{1},
                            "META-INF/INDEX.LIST", PluginPackageFixtures.bytes(index)))));
            var inspection = inspect(other);
            if (index.equals(safe)) {
                assertThat(inspection.classSpace().implicitClassPaths()).isEmpty();
                PluginClassSpaceVerifier.requireCompatible(contract.classSpace(), contract.descriptor(),
                        inspection.descriptor(), inspection.classSpace());
            } else {
                assertThat(inspection.classSpace().implicitClassPaths()).containsExactly("lib/private.jar");
                assertThatThrownBy(() -> PluginClassSpaceVerifier.requireCompatible(contract.classSpace(), contract.descriptor(),
                        inspection.descriptor(), inspection.classSpace())).isInstanceOf(PluginPackageException.class);
            }
        }
    }

    @Test
    @DisplayName("事务安装拒绝与已安装契约重复的包并返回结构化原因")
    void transactionRejectsSplitPackage() throws Exception {
        Path source = contract(Map.of("sample/contract/Api.class", new byte[]{1}), Map.of(), null);
        try (var installer = new top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller(directory.resolve("installed"))) {
            assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
            var origin = top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin.localUnsignedUpload(
                    PluginPackageIntegrity.sha256Hex(source));
            var prepared = installer.prepareTransaction(source, false, origin);
            assertThat(prepared.readyToCommit()).as(prepared.result().toString()).isTrue();
            var committed = installer.commitTransaction(prepared);
            installer.verifyCommittedTarget(committed);
            installer.markActivated(committed);
            installer.completeTransaction(committed);
            Path duplicate = directory.resolve("duplicate.jar");
            PluginPackageFixtures.writeZip(duplicate, Map.of(
                    "plugin.properties", PluginPackageFixtures.bytes(
                            PluginPackageFixtures.pluginProperties("other", "3.2.1", null, "sample.Other")),
                    "sample/contract/Copy.class", new byte[]{2}));
            var rejected = installer.prepareTransaction(duplicate, false,
                    top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin.localUnsignedUpload(
                            PluginPackageIntegrity.sha256Hex(duplicate)));
            assertThat(rejected.result().outcome()).isEqualTo(
                    top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome.REJECTED_UNSAFE);
            assertThat(rejected.readyToCommit()).isFalse();
            assertThat(installer.listInstalled()).extracting("id").containsExactly("value-contract");
        }
    }

    private Path contract(Map<String, byte[]> api, Map<String, byte[]> extra, String digest) throws Exception {
        byte[] jar = PluginPackageFixtures.zipBytes(api);
        String sha = digest == null ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar)) : digest;
        String properties = PluginPackageFixtures.pluginProperties("value-contract", "3.2.1", null, "sample.Wrapper")
                + "pixiv.lifecycle-policy=process-restart\npixiv.contract-api=lib/api.jar\npixiv.contract-sha256=" + sha + "\n";
        Map<String, byte[]> entries = new LinkedHashMap<>(extra);
        entries.put("plugin.properties", PluginPackageFixtures.bytes(properties));
        entries.put("lib/api.jar", jar);
        Path result = directory.resolve("contract.jar");
        PluginPackageFixtures.writeZip(result, entries);
        return result;
    }

    @Test
    @DisplayName("冻结开发目录沿用契约摘要、重复包、隐式类路径和累计条目检查")
    void developmentSnapshotsRetainContractChecks() throws Exception {
        Path packaged = contract(Map.of("sample/contract/Api.class", new byte[]{1}), Map.of(), null);
        Path snapshot = directory.resolve("snapshot");
        java.nio.file.Files.createDirectories(snapshot.resolve("lib"));
        java.nio.file.Files.createDirectories(snapshot.resolve("classes/sample/wrapper"));
        try (var zip = new java.util.zip.ZipFile(packaged.toFile())) {
            for (String name : java.util.List.of("plugin.properties", "lib/api.jar")) {
                try (var input = zip.getInputStream(zip.getEntry(name))) {
                    java.nio.file.Files.copy(input, snapshot.resolve(name));
                }
            }
        }
        java.nio.file.Files.write(snapshot.resolve("classes/sample/wrapper/Plugin.class"), new byte[]{1});
        var descriptor = PluginPackageReader.inspectDescriptor(snapshot.resolve("plugin.properties"));
        var space = PluginPackageVerifier.developmentClassSpace(snapshot, PluginPackageLimits.defaults());
        assertThat(PluginClassSpaceVerifier.contractPackages(space, descriptor)).containsExactly("sample.contract");
        assertThat(space.packagesByArchive().get("")).containsExactly("sample.wrapper");
        Path copy = snapshot.resolve("classes/sample/contract/Copy.class");
        java.nio.file.Files.createDirectories(copy.getParent());
        java.nio.file.Files.write(copy, new byte[]{2});
        assertThatThrownBy(() -> PluginClassSpaceVerifier.contractPackages(
                PluginPackageVerifier.developmentClassSpace(snapshot, PluginPackageLimits.defaults()), descriptor))
                .hasMessageContaining("outside the declared API jar");
        java.nio.file.Files.delete(copy);
        Path manifest = snapshot.resolve("classes/META-INF/MANIFEST.MF");
        java.nio.file.Files.createDirectories(manifest.getParent());
        java.nio.file.Files.writeString(manifest, "Manifest-Version: 1.0\r\nClass-Path: hidden.jar\r\n\r\n");
        assertThatThrownBy(() -> PluginClassSpaceVerifier.contractPackages(
                PluginPackageVerifier.developmentClassSpace(snapshot, PluginPackageLimits.defaults()), descriptor))
                .hasMessageContaining("digest-bound");
        var limits = PluginPackageLimits.defaults();
        assertThatThrownBy(() -> PluginPackageVerifier.developmentClassSpace(snapshot,
                new PluginPackageLimits(limits.maxArchiveBytes(), 1, limits.maxTotalUncompressedBytes(),
                        limits.maxEntryUncompressedBytes(), limits.maxDescriptorBytes(), limits.maxCompressionRatio())))
                .isInstanceOf(PluginPackageException.class).hasMessageContaining("too many development entries");
    }

    private PluginPackageInspection inspect(Path artifact) {
        var usage = PluginPackageVerifier.verifyAndMeasure(artifact, PluginPackageLimits.defaults());
        return PluginClassSpaceVerifier.withClassSpace(PluginPackageReader.inspect(artifact), usage.classSpace());
    }
}
