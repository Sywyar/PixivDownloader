package top.sywyar.pixivdownload.ffmpeg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FFmpeg 稳定版资产选择")
class FfmpegInstallerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("按操作系统与架构选择对应的稳定版资产")
    void selectsStableAssetForSupportedPlatform() {
        assertAsset("Windows 11", "amd64", "ffmpeg-windows-x64.zip");
        assertAsset("Linux", "x86_64", "ffmpeg-linux-x64.zip");
        assertAsset("Linux", "aarch64", "ffmpeg-linux-arm64.zip");
        assertAsset("Mac OS X", "x86_64", "ffmpeg-macos-x64.zip");
        assertAsset("Mac OS X", "arm64", "ffmpeg-macos-arm64.zip");
        assertAsset("Darwin", "arm64", "ffmpeg-macos-arm64.zip");
    }

    @Test
    @DisplayName("没有对应构建的系统保持手动安装降级")
    void rejectsUnsupportedPlatform() {
        assertThat(FfmpegInstaller.archiveUri("Windows 11", "aarch64")).isEmpty();
        assertThat(FfmpegInstaller.archiveUri("Linux", "x86")).isEmpty();
        assertThat(FfmpegInstaller.archiveUri("FreeBSD", "amd64")).isEmpty();
    }

    @Test
    @DisplayName("只提取运行文件与随包许可证")
    void extractsRequiredFiles(@TempDir Path tempDir) throws IOException {
        Path archive = tempDir.resolve("ffmpeg.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            addEntry(zip, "ffmpeg-test/bin/" + FfmpegLocator.executableName());
            addEntry(zip, "ffmpeg-test/bin/" + FfmpegLocator.probeExecutableName());
            addEntry(zip, "ffmpeg-test/licenses/ffmpeg-LGPLv2.1.txt");
            addEntry(zip, "ffmpeg-test/licenses/libwebp-COPYING.txt");
            addEntry(zip, "ffmpeg-test/licenses/libwebp-PATENTS.txt");
            addEntry(zip, "ffmpeg-test/ignored.txt");
        }

        FfmpegInstaller.ExtractedFiles files = FfmpegInstaller.extractRequiredFiles(
                archive, tempDir.resolve("extracted"));
        assertThat(List.of(files.ffmpeg(), files.ffprobe(), files.ffmpegLicense(),
                files.libwebpLicense(), files.libwebpPatents())).allMatch(Files::isRegularFile);
        assertThat(tempDir.resolve("extracted/ignored.txt")).doesNotExist();
    }

    @Test
    @DisplayName("签名清单绑定精确资产名、长度与 SHA-256")
    void verifiesSignedManifestAndArchive(@TempDir Path tempDir) throws Exception {
        SigningSupport signing = new SigningSupport();
        byte[] archiveBytes = "verified archive".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = manifest("ffmpeg-windows-x64.zip", archiveBytes);
        FfmpegInstaller.AssetMetadata asset = FfmpegInstaller.verifyRelease(
                manifest, signing.signature(manifest), "ffmpeg-windows-x64.zip", signing.verifier());
        Path archive = tempDir.resolve(asset.assetName());
        Files.write(archive, archiveBytes);

        FfmpegInstaller.verifyArchive(archive, asset);

        assertThat(asset.expectedSizeBytes()).isEqualTo(archiveBytes.length);
        assertThat(asset.sha256()).isEqualTo(sha256(archiveBytes));
    }

    @Test
    @DisplayName("清单缺失、签名无效或资产名不匹配时拒绝安装")
    void rejectsUntrustedReleaseMetadata() throws Exception {
        SigningSupport signing = new SigningSupport();
        byte[] archiveBytes = "archive".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = manifest("ffmpeg-windows-x64.zip", archiveBytes);
        byte[] signature = signing.signature(manifest);

        assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                new byte[0], signature, "ffmpeg-windows-x64.zip", signing.verifier()))
                .hasMessageContaining("MANIFEST_MISSING");
        assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                manifest, new byte[0], "ffmpeg-windows-x64.zip", signing.verifier()))
                .hasMessageContaining("SIGNATURE_MISSING");

        byte[] changedManifest = manifest.clone();
        changedManifest[changedManifest.length - 2] ^= 1;
        assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                changedManifest, signature, "ffmpeg-windows-x64.zip", signing.verifier()))
                .hasMessageContaining("INVALID_SIGNATURE");

        byte[] otherAssetManifest = manifest("ffmpeg-linux-x64.zip", archiveBytes);
        assertThatThrownBy(() -> FfmpegInstaller.verifyRelease(
                otherAssetManifest, signing.signature(otherAssetManifest),
                "ffmpeg-windows-x64.zip", signing.verifier()))
                .hasMessageContaining("ASSET_NAME_MISMATCH");
    }

    @Test
    @DisplayName("长度或摘要不匹配时在解压和替换既有工具前失败")
    void rejectsTamperedArchiveWithoutReplacingExistingFiles(@TempDir Path tempDir) throws Exception {
        Path existing = tempDir.resolve("installed-ffmpeg");
        Files.writeString(existing, "existing", StandardCharsets.UTF_8);
        Path archive = tempDir.resolve("ffmpeg.zip");
        Files.writeString(archive, "tampered", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> FfmpegInstaller.verifyArchive(archive,
                new FfmpegInstaller.AssetMetadata("ffmpeg.zip", Files.size(archive) + 1L, sha256("tampered"))))
                .hasMessageContaining("ASSET_SIZE_MISMATCH");
        assertThatThrownBy(() -> FfmpegInstaller.verifyArchive(archive,
                new FfmpegInstaller.AssetMetadata("ffmpeg.zip", Files.size(archive), sha256("expected"))))
                .hasMessageContaining("ASSET_SHA256_MISMATCH");
        assertThat(existing).hasContent("existing");
        assertThat(tempDir.resolve("extracted")).doesNotExist();
    }

    private static void assertAsset(String osName, String osArch, String asset) {
        assertThat(FfmpegInstaller.archiveUri(osName, osArch))
                .contains(URI.create(FfmpegInstaller.RELEASE_BASE_URL + asset));
    }

    private static void addEntry(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(name.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static byte[] manifest(String assetName, byte[] archiveBytes) throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "assets": {
                    "%s": {
                      "expectedSizeBytes": %d,
                      "sha256": "%s"
                    }
                  }
                }
                """.formatted(assetName, archiveBytes.length, sha256(archiveBytes));
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(String value) throws Exception {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static final class SigningSupport {
        private final String keyId = "test-ffmpeg-root";
        private final KeyPair keyPair;
        private final PluginSupplyChainVerifier verifier;

        private SigningSupport() throws Exception {
            keyPair = KeyPairGenerator.getInstance(SignatureMetadata.ED25519).generateKeyPair();
            TrustedPluginKey key = new TrustedPluginKey(
                    keyId,
                    SignatureMetadata.ED25519,
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    TrustedPluginKey.State.ACTIVE,
                    "test",
                    "test FFmpeg root",
                    true);
            verifier = new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)));
        }

        private PluginSupplyChainVerifier verifier() {
            return verifier;
        }

        private byte[] signature(byte[] manifest) throws Exception {
            byte[] envelope = EnvelopeV1Codec.manifestMessage(
                    FfmpegInstaller.RELEASE_REPOSITORY_ID,
                    manifest.length,
                    MessageDigest.getInstance("SHA-256").digest(manifest));
            Signature signer = Signature.getInstance(SignatureMetadata.ED25519);
            signer.initSign(keyPair.getPrivate());
            signer.update(envelope);
            return MAPPER.writeValueAsBytes(new SignatureMetadata(
                    SignatureMetadata.FORMAT_VERSION,
                    SignatureMetadata.ED25519,
                    keyId,
                    Base64.getEncoder().encodeToString(signer.sign())));
        }
    }
}
