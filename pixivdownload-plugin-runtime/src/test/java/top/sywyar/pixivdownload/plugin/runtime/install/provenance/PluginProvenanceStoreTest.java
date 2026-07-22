package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件来源证明存储")
class PluginProvenanceStoreTest {

    private static final String SHA256 = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("严格读取拒绝结构损坏与来源状态语义矛盾")
    void rejectsMalformedStrictRecords() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins"));
        PluginProvenanceStore store = new PluginProvenanceStore(plugins);
        List<String> invalidRecords = List.of(
                validLocalRecord() + "unknown=value\n",
                validLocalRecord() + "source=LOCAL_UPLOAD\n",
                validLocalRecord().replace("artifactSha256=" + SHA256, "artifactSha256=abcd"),
                validLocalRecord().replace("artifactSizeBytes=4", "artifactSizeBytes=0"),
                validLocalRecord().replace("source=LOCAL_UPLOAD", "source=MARKET_CATALOG")
                        .replace("officialRepository=false", "officialRepository=true")
                        + "repositoryId=official\nexpectedSizeBytes=4\nexpectedSha256=" + SHA256 + "\n",
                validLocalRecord().replace("status=UNSIGNED_ALLOWED", "status=VERIFIED"),
                validLocalRecord() + "offlineStatus=VERIFIED\nofflineVerifiedAt=2026-07-01T00:01:00Z\n",
                validLocalRecord().replace("verifiedAt=2026-07-01T00:00:00Z\n", ""),
                validLocalRecord() + "keyId=forged-key\n",
                validCatalogRecord().replace("status=VERIFIED", "status=UNSIGNED_ALLOWED"),
                validCatalogRecord() + "offlineStatus=UNSIGNED_ALLOWED\nofflineVerifiedAt=2026-07-01T00:01:00Z\n",
                validCatalogRecord().replace("\nkeyId=test-key\n", "\nkeyId=other-key\n"),
                validCatalogRecord().replace("signature.formatVersion=1", "signature.formatVersion=2"),
                validCatalogRecord().replace("signature.value=c2ln", "signature.value=not-base64!"),
                validCatalogRecord().replace("diagnosticCode=VERIFIED", "diagnosticCode=INVALID_SIGNATURE"),
                validCatalogRecord() + "offlineStatus=VERIFIED\n"
                        + "offlineVerifiedAt=2026-06-30T23:59:59Z\n");

        for (int index = 0; index < invalidRecords.size(); index++) {
            Path artifact = plugins.resolve("invalid-" + index + ".jar");
            Path sidecar = store.sidecarPath(artifact);
            Files.createDirectories(sidecar.getParent());
            Files.writeString(sidecar, invalidRecords.get(index), StandardCharsets.UTF_8);

            assertThatThrownBy(() -> store.readRequiredForRecovery(artifact))
                    .as("非法来源证明 #%s 必须 fail-closed", index)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("invalid");
        }
    }

    @Test
    @DisplayName("程序化构造与严格读取共用来源状态校验")
    void rejectsProgrammaticSemanticContradictions() {
        assertThatThrownBy(() -> new PluginProvenanceRecord(
                PluginPackageSource.LOCAL_UPLOAD,
                null,
                false,
                null,
                null,
                4L,
                SHA256,
                null,
                VerificationStatus.VERIFIED,
                null,
                null,
                null,
                Instant.parse("2026-07-01T00:00:00Z"),
                null,
                null,
                "VERIFIED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSIGNED_ALLOWED");
    }

    @Test
    @DisplayName("严格读取在 Properties 解析前拒绝非法 UTF-8")
    void rejectsMalformedUtf8() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins"));
        PluginProvenanceStore store = new PluginProvenanceStore(plugins);
        Path artifact = plugins.resolve("bad-utf8.jar");
        Path sidecar = store.sidecarPath(artifact);
        Files.createDirectories(sidecar.getParent());
        Files.write(sidecar, new byte[]{(byte) 0xC3, 0x28});

        assertThatThrownBy(() -> store.readRequiredForRecovery(artifact))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    @DisplayName("带计量读取返回与解析同源的实际字节数")
    void measuredReadReturnsBytesConsumedByTheParsedRecord() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins-measured"));
        PluginProvenanceStore store = new PluginProvenanceStore(plugins);
        Path artifact = plugins.resolve("demo.jar");
        byte[] bytes = validLocalRecord().getBytes(StandardCharsets.UTF_8);
        Path sidecar = store.sidecarPath(artifact);
        Files.createDirectories(sidecar.getParent());
        Files.write(sidecar, bytes);

        PluginProvenanceStore.MeasuredProvenance measured = store.readMeasured(artifact).orElseThrow();

        assertThat(measured.byteCount()).isEqualTo(bytes.length);
        assertThat(measured.record().source()).isEqualTo(PluginPackageSource.LOCAL_UPLOAD);
    }

    @Test
    @DisplayName("程序生成的来源证明超过读取上限时在创建临时文件前拒绝")
    void rejectsOversizedGeneratedRecordBeforeTemporaryWrite() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins-oversized-write"));
        PluginProvenanceStore store = new PluginProvenanceStore(plugins);
        Path artifact = plugins.resolve("demo.jar");
        Files.write(artifact, new byte[]{1, 2, 3, 4});
        SignatureMetadata signature = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, "test-key", "c2ln");
        PluginProvenanceRecord record = new PluginProvenanceRecord(
                PluginPackageSource.MARKET_CATALOG, "official", true,
                4L, SHA256, 4L, SHA256, signature, VerificationStatus.VERIFIED,
                "test-key", "p".repeat(1_100_000), "Test Trust",
                Instant.parse("2026-07-01T00:00:00Z"), null, null, "VERIFIED");
        Path sidecar = store.sidecarPath(artifact);
        Path temporary = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");

        assertThatThrownBy(() -> store.write(artifact, record))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("generated plugin provenance exceeds");

        assertThat(sidecar).doesNotExist();
        assertThat(temporary).doesNotExist();
    }

    @Test
    @DisplayName("正常读取可收敛等价 legacy 双副本而恢复读取保持零副作用拒绝")
    void reconcilesEqualLegacyCopyOnlyOnNormalRead() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins"));
        PluginProvenanceStore store = new PluginProvenanceStore(plugins);
        Path artifact = plugins.resolve("demo.jar");
        Path current = store.sidecarPath(artifact);
        Path legacy = artifact.resolveSibling("demo.jar.pixiv-plugin-provenance");
        Files.createDirectories(current.getParent());
        Files.writeString(current, validLocalRecord(), StandardCharsets.UTF_8);
        Files.copy(current, legacy);

        assertThatThrownBy(() -> store.readRequiredForRecovery(artifact))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("both current and legacy");
        assertThat(Files.exists(legacy)).isTrue();

        assertThat(store.read(artifact)).isPresent();
        assertThat(Files.exists(current)).isTrue();
        assertThat(Files.exists(legacy)).isFalse();
    }

    @Test
    @DisplayName("中断的 hardlink sidecar 发布按同一文件身份收敛且不覆盖目标")
    void completesInterruptedHardlinkSidecarMove() throws Exception {
        Path plugins = Files.createDirectories(temporaryDirectory.resolve("plugins"));
        PluginProvenanceStore store = new PluginProvenanceStore(plugins);
        Path sourceArtifact = plugins.resolve("source.jar");
        Path targetArtifact = plugins.resolve("target.jar");
        Path sourceSidecar = store.sidecarPath(sourceArtifact);
        Path targetSidecar = store.sidecarPath(targetArtifact);
        Files.createDirectories(sourceSidecar.getParent());
        Files.writeString(sourceSidecar, validLocalRecord(), StandardCharsets.UTF_8);
        Files.createLink(targetSidecar, sourceSidecar);

        store.moveSidecarOnly(sourceArtifact, targetArtifact);

        assertThat(Files.exists(sourceSidecar)).isFalse();
        assertThat(Files.exists(targetSidecar)).isTrue();
        assertThat(store.readRequiredForRecovery(targetArtifact).artifactSha256()).isEqualTo(SHA256);
    }

    private static String validLocalRecord() {
        return "formatVersion=1\n"
                + "source=LOCAL_UPLOAD\n"
                + "officialRepository=false\n"
                + "artifactSizeBytes=4\n"
                + "artifactSha256=" + SHA256 + "\n"
                + "status=UNSIGNED_ALLOWED\n"
                + "verifiedAt=2026-07-01T00:00:00Z\n"
                + "diagnosticCode=UNSIGNED_ALLOWED\n";
    }

    private static String validCatalogRecord() {
        return "formatVersion=1\n"
                + "source=MARKET_CATALOG\n"
                + "repositoryId=official\n"
                + "officialRepository=true\n"
                + "expectedSizeBytes=4\n"
                + "expectedSha256=" + SHA256 + "\n"
                + "artifactSizeBytes=4\n"
                + "artifactSha256=" + SHA256 + "\n"
                + "signature.formatVersion=1\n"
                + "signature.algorithm=Ed25519\n"
                + "signature.keyId=test-key\n"
                + "signature.value=c2ln\n"
                + "status=VERIFIED\n"
                + "keyId=test-key\n"
                + "publisher=Test Publisher\n"
                + "trustLabel=Test Trust\n"
                + "verifiedAt=2026-07-01T00:00:00Z\n"
                + "diagnosticCode=VERIFIED\n";
    }
}
