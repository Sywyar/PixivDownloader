package top.sywyar.pixivdownload.plugin.signature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PluginSignatureTool 发布链路签名 CLI")
class PluginSignatureToolTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("artifact / manifest 签名 JSON 可被统一 verifier 离线验证")
    void cliSignaturesVerify() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(SignatureMetadata.ED25519);
        KeyPair pair = generator.generateKeyPair();
        String keyId = "cli-test-key";
        Path privateKey = tempDir.resolve("ed25519.pem");
        Files.writeString(privateKey, pem(pair.getPrivate().getEncoded()));
        TrustedPluginKey key = new TrustedPluginKey(keyId, SignatureMetadata.ED25519,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                TrustedPluginKey.State.ACTIVE, "CLI Test Publisher", "CLI Test Root", false);
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)));

        Path artifact = tempDir.resolve("demo.jar");
        Files.writeString(artifact, "artifact-bytes");
        Path artifactSig = tempDir.resolve("demo.jar.sig");
        PluginSignatureTool.main(new String[]{
                "artifact",
                "--artifact", artifact.toString(),
                "--plugin-id", "demo",
                "--version", "1.0.0",
                "--key-id", keyId,
                "--private-key", privateKey.toString(),
                "--out", artifactSig.toString()
        });
        SignatureMetadata artifactMetadata = readMetadata(artifactSig);
        VerificationResult artifactResult = verifier.verifyArtifact(new ArtifactVerificationRequest(
                artifact, "demo", "1.0.0", Files.size(artifact), Hashing.hex(Hashing.sha256(artifact)),
                artifactMetadata, VerificationPolicy.customRepository()));
        assertThat(artifactResult.status()).isEqualTo(VerificationStatus.VERIFIED);
        PluginSignatureTool.main(new String[]{
                "verify-artifact",
                "--artifact", artifact.toString(),
                "--signature", artifactSig.toString(),
                "--plugin-id", "demo",
                "--version", "1.0.0",
                "--expected-size", Long.toString(Files.size(artifact)),
                "--sha256", Hashing.hex(Hashing.sha256(artifact)),
                "--policy", "custom",
                "--trusted-key-id", keyId,
                "--trusted-public-key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                "--trusted-publisher", "CLI Test Publisher",
                "--trusted-label", "CLI Test Root",
                "--trusted-official", "false"
        });

        Path manifest = tempDir.resolve("manifest.json");
        Files.writeString(manifest, "{\"schemaVersion\":\"1\",\"entries\":[]}");
        Path manifestSig = tempDir.resolve("manifest.json.sig");
        PluginSignatureTool.main(new String[]{
                "manifest",
                "--manifest", manifest.toString(),
                "--repository-id", "repo",
                "--key-id", keyId,
                "--private-key", privateKey.toString(),
                "--out", manifestSig.toString()
        });
        SignatureMetadata manifestMetadata = readMetadata(manifestSig);
        VerificationResult manifestResult = verifier.verifyManifest(new ManifestVerificationRequest(
                Files.readAllBytes(manifest), "repo", manifestMetadata, VerificationPolicy.customRepository()));
        assertThat(manifestResult.status()).isEqualTo(VerificationStatus.VERIFIED);
        PluginSignatureTool.main(new String[]{
                "verify-manifest",
                "--manifest", manifest.toString(),
                "--signature", manifestSig.toString(),
                "--repository-id", "repo",
                "--policy", "custom",
                "--trusted-key-id", keyId,
                "--trusted-public-key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                "--trusted-publisher", "CLI Test Publisher",
                "--trusted-label", "CLI Test Root",
                "--trusted-official", "false"
        });
    }

    @Test
    @DisplayName("verify-manifest 按用途选择官方公钥")
    void verifyManifestSelectsOfficialRootByPurpose() throws Exception {
        Path manifest = tempDir.resolve("ffmpeg-release.json");
        Files.writeString(manifest, "{}");
        Path signature = tempDir.resolve("ffmpeg-release.json.sig");
        Files.writeString(signature, "{\"formatVersion\":1,\"algorithm\":\"Ed25519\","
                + "\"keyId\":\"" + OfficialArtifactTrustRoots.FFMPEG_KEY_ID + "\","
                + "\"value\":\"" + Base64.getEncoder().encodeToString(new byte[64]) + "\"}");

        String[] command = {
                "verify-manifest",
                "--manifest", manifest.toString(),
                "--signature", signature.toString(),
                "--repository-id", "ffmpeg-stable",
                "--official-purpose", "ffmpeg",
                "--policy", "official"
        };
        assertThatThrownBy(() -> PluginSignatureTool.main(command))
                .hasMessageContaining("INVALID_SIGNATURE");
        command[8] = "plugin";
        assertThatThrownBy(() -> PluginSignatureTool.main(command))
                .hasMessageContaining("UNKNOWN_KEY");
    }

    private static String pem(byte[] pkcs8) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8)
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static SignatureMetadata readMetadata(Path path) throws Exception {
        String json = Files.readString(path);
        return new SignatureMetadata(
                Integer.parseInt(value(json, "formatVersion")),
                value(json, "algorithm"),
                value(json, "keyId"),
                value(json, "value"));
    }

    private static String value(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\"([^\"]*)\"|([0-9]+))");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("missing key: " + key);
        }
        return matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
    }
}
