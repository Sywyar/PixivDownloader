package top.sywyar.pixivdownload.plugin.signature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PluginSupplyChainVerifier Ed25519 artifact / manifest verification")
class PluginSupplyChainVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("artifact：Ed25519 正例，绑定 pluginId / version / size / sha256 / algorithm / keyId")
    void verifiesSignedArtifact() throws Exception {
        Fixture fixture = Fixture.create(TrustedPluginKey.State.ACTIVE);
        Path artifact = artifact("payload");
        SignatureMetadata signature = fixture.artifactSignature("demo", "1.0.0", artifact);

        VerificationResult result = fixture.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), Hashing.hex(Hashing.sha256(artifact)), signature,
                VerificationPolicy.customRepository()));

        assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(result.accepted()).isTrue();
        assertThat(result.keyId()).isEqualTo(fixture.key.keyId());
        assertThat(result.sha256()).isEqualTo(Hashing.hex(Hashing.sha256(artifact)));
    }

    @Test
    @DisplayName("artifact：任一绑定字段被改即拒绝")
    void rejectsTamperedEnvelopeInputs() throws Exception {
        Fixture fixture = Fixture.create(TrustedPluginKey.State.ACTIVE);
        Path artifact = artifact("payload");
        String sha256 = Hashing.hex(Hashing.sha256(artifact));
        SignatureMetadata signature = fixture.artifactSignature("demo", "1.0.0", artifact);

        assertStatus(fixture.verifier.verifyArtifact(request(
                artifact, "other", "1.0.0", Files.size(artifact), sha256, signature,
                VerificationPolicy.customRepository())), VerificationStatus.INVALID_SIGNATURE);
        assertStatus(fixture.verifier.verifyArtifact(request(
                artifact, "demo", "2.0.0", Files.size(artifact), sha256, signature,
                VerificationPolicy.customRepository())), VerificationStatus.INVALID_SIGNATURE);
        assertStatus(fixture.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact) + 1, sha256, signature,
                VerificationPolicy.customRepository())), VerificationStatus.HASH_MISMATCH);
        assertStatus(fixture.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), "00" + sha256.substring(2), signature,
                VerificationPolicy.customRepository())), VerificationStatus.HASH_MISMATCH);

        SignatureMetadata wrongAlgorithm = new SignatureMetadata(1, "RSA", fixture.key.keyId(), signature.value());
        assertStatus(fixture.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), sha256, wrongAlgorithm,
                VerificationPolicy.customRepository())), VerificationStatus.UNSUPPORTED_ALGORITHM);

        SignatureMetadata wrongKeyId = new SignatureMetadata(1, SignatureMetadata.ED25519,
                "unknown", signature.value());
        assertStatus(fixture.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), sha256, wrongKeyId,
                VerificationPolicy.customRepository())), VerificationStatus.UNKNOWN_KEY);

        SignatureMetadata wrongValue = new SignatureMetadata(1, SignatureMetadata.ED25519,
                fixture.key.keyId(), Base64.getEncoder().encodeToString("bad".getBytes()));
        assertStatus(fixture.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), sha256, wrongValue,
                VerificationPolicy.customRepository())), VerificationStatus.INVALID_SIGNATURE);
    }

    @Test
    @DisplayName("artifact：坏 Base64、缺签、未知 key、revoked key 均 fail-closed")
    void rejectsMalformedMissingUnknownAndRevoked() throws Exception {
        Fixture active = Fixture.create(TrustedPluginKey.State.ACTIVE);
        Path artifact = artifact("payload");
        String sha256 = Hashing.hex(Hashing.sha256(artifact));

        SignatureMetadata malformed = new SignatureMetadata(1, SignatureMetadata.ED25519,
                active.key.keyId(), "not-base64!!");
        assertStatus(active.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), sha256, malformed,
                VerificationPolicy.customRepository())), VerificationStatus.MALFORMED_SIGNATURE);

        assertStatus(active.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), sha256, null,
                VerificationPolicy.customRepository())), VerificationStatus.SIGNATURE_REQUIRED);

        Fixture revoked = Fixture.create(TrustedPluginKey.State.REVOKED);
        SignatureMetadata revokedSignature = revoked.artifactSignature("demo", "1.0.0", artifact);
        assertStatus(revoked.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), sha256, revokedSignature,
                VerificationPolicy.customRepository())), VerificationStatus.REVOKED_KEY);
    }

    @Test
    @DisplayName("artifact：本地 unsigned 只能按显式策略返回 UNSIGNED_ALLOWED")
    void localUnsignedAllowedOnlyByPolicy() throws Exception {
        Fixture fixture = Fixture.create(TrustedPluginKey.State.ACTIVE);
        Path artifact = artifact("payload");

        VerificationResult result = fixture.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", null, null, null,
                VerificationPolicy.localUnsignedAllowed()));

        assertThat(result.status()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
        assertThat(result.accepted()).isTrue();
    }

    @Test
    @DisplayName("trust：ACTIVE 可验证新包，RETIRED 只允许离线复验，REVOKED 恒拒绝")
    void keyLifecycleSemantics() throws Exception {
        Path artifact = artifact("payload");
        String sha256 = Hashing.hex(Hashing.sha256(artifact));
        Fixture retired = Fixture.create(TrustedPluginKey.State.RETIRED);
        SignatureMetadata signature = retired.artifactSignature("demo", "1.0.0", artifact);

        assertStatus(retired.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), sha256, signature,
                VerificationPolicy.customRepository())), VerificationStatus.RETIRED_KEY);
        VerificationResult offline = retired.verifier.verifyArtifact(request(
                artifact, "demo", "1.0.0", Files.size(artifact), sha256, signature,
                VerificationPolicy.installedCustom()));
        assertThat(offline.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(offline.accepted()).isTrue();
    }

    @Test
    @DisplayName("trust：重复 keyId 与非法 trust root 构造期 fail-fast")
    void trustStoreRejectsDuplicateAndInvalidKeys() throws Exception {
        Fixture fixture = Fixture.create(TrustedPluginKey.State.ACTIVE);
        TrustedPluginKey duplicate = new TrustedPluginKey(fixture.key.keyId(), SignatureMetadata.ED25519,
                fixture.key.publicKeySpkiBase64(), TrustedPluginKey.State.ACTIVE,
                "Other Publisher", "Other Root", false);

        assertThatThrownBy(() -> PluginTrustStores.of(List.of(fixture.key, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> PluginTrustStores.withBuiltInOfficial(List.of(
                new TrustedPluginKey(PluginTrustStores.builtInOfficialRoot().keyId(), SignatureMetadata.ED25519,
                        fixture.key.publicKeySpkiBase64(), TrustedPluginKey.State.ACTIVE,
                        "Other Publisher", "Other Root", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> PluginTrustStores.of(java.util.Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        assertThatThrownBy(() -> PluginTrustStores.of(List.of(new TrustedPluginKey(" ", SignatureMetadata.ED25519,
                fixture.key.publicKeySpkiBase64(), TrustedPluginKey.State.ACTIVE,
                "Test Publisher", "Test Root", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key id");
        assertThatThrownBy(() -> PluginTrustStores.of(List.of(new TrustedPluginKey("bad-key", " ",
                fixture.key.publicKeySpkiBase64(), TrustedPluginKey.State.ACTIVE,
                "Test Publisher", "Test Root", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("algorithm");
        assertThatThrownBy(() -> PluginTrustStores.of(List.of(new TrustedPluginKey("bad-key", "RSA",
                fixture.key.publicKeySpkiBase64(), TrustedPluginKey.State.ACTIVE,
                "Test Publisher", "Test Root", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> PluginTrustStores.of(List.of(new TrustedPluginKey("bad-key",
                SignatureMetadata.ED25519, " ", TrustedPluginKey.State.ACTIVE,
                "Test Publisher", "Test Root", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key");
        assertThatThrownBy(() -> PluginTrustStores.of(List.of(new TrustedPluginKey("bad-key",
                SignatureMetadata.ED25519, "not-base64!!", TrustedPluginKey.State.ACTIVE,
                "Test Publisher", "Test Root", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key");
        assertThatThrownBy(() -> PluginTrustStores.of(List.of(new TrustedPluginKey("bad-key",
                SignatureMetadata.ED25519,
                Base64.getEncoder().encodeToString("not-spki".getBytes(StandardCharsets.UTF_8)),
                TrustedPluginKey.State.ACTIVE, "Test Publisher", "Test Root", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key");
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        String rsaPublicKey = Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded());
        assertThatThrownBy(() -> PluginTrustStores.of(List.of(new TrustedPluginKey("bad-key",
                SignatureMetadata.ED25519, rsaPublicKey, TrustedPluginKey.State.ACTIVE,
                "Test Publisher", "Test Root", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key");
        assertThatThrownBy(() -> PluginTrustStores.of(List.of(new TrustedPluginKey("bad-key",
                SignatureMetadata.ED25519, fixture.key.publicKeySpkiBase64(), null,
                "Test Publisher", "Test Root", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @Test
    @DisplayName("trust：官方公钥集中由 OfficialPluginTrustRoots 提供并被默认 trust store 继承")
    void officialTrustRootIsCentralized() {
        TrustedPluginKey root = OfficialPluginTrustRoots.activeRoot();

        assertThat(root).isSameAs(PluginTrustStores.builtInOfficialRoot());
        assertThat(OfficialPluginTrustRoots.all()).containsExactly(root);
        assertThat(root.keyId()).isEqualTo(OfficialPluginTrustRoots.OFFICIAL_KEY_ID);
        assertThat(root.algorithm()).isEqualTo(SignatureMetadata.ED25519);
        assertThat(root.publicKeySpkiBase64()).isEqualTo(OfficialPluginTrustRoots.OFFICIAL_PUBLIC_KEY_SPKI_BASE64);
        assertThat(root.state()).isEqualTo(TrustedPluginKey.State.ACTIVE);
        assertThat(root.official()).isTrue();
        assertThat(PluginTrustStores.builtInOfficial().findByKeyId(root.keyId())).contains(root);
    }

    @Test
    @DisplayName("manifest：验证原始 manifest 字节及 detached signature，raw bytes 任一位变化即拒绝")
    void verifiesManifestRawBytes() throws Exception {
        Fixture fixture = Fixture.create(TrustedPluginKey.State.ACTIVE);
        byte[] manifest = "{\"schemaVersion\":\"1\",\"entries\":[]}".getBytes();
        SignatureMetadata signature = fixture.manifestSignature("repo", manifest);

        VerificationResult result = fixture.verifier.verifyManifest(
                new ManifestVerificationRequest(manifest, "repo", signature, VerificationPolicy.customRepository()));
        assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);

        byte[] tampered = "{\"entries\":[],\"schemaVersion\":\"1\"}".getBytes();
        assertStatus(fixture.verifier.verifyManifest(
                new ManifestVerificationRequest(tampered, "repo", signature, VerificationPolicy.customRepository())),
                VerificationStatus.INVALID_SIGNATURE);
    }

    private Path artifact(String text) throws Exception {
        Path path = tempDir.resolve("plugin.jar");
        Files.writeString(path, text);
        return path;
    }

    private static ArtifactVerificationRequest request(Path artifact, String pluginId, String version, Long size,
                                                       String sha256, SignatureMetadata signature,
                                                       VerificationPolicy policy) {
        return new ArtifactVerificationRequest(artifact, pluginId, version, size, sha256, signature, policy);
    }

    private static void assertStatus(VerificationResult result, VerificationStatus status) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.accepted()).isFalse();
    }

    private record Fixture(KeyPair pair, TrustedPluginKey key, PluginSupplyChainVerifier verifier) {

        static Fixture create(TrustedPluginKey.State state) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();
            String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            TrustedPluginKey key = new TrustedPluginKey("test-key", SignatureMetadata.ED25519, publicKey, state,
                    "Test Publisher", "Test Root", false);
            return new Fixture(pair, key, new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key))));
        }

        SignatureMetadata artifactSignature(String pluginId, String version, Path artifact) throws Exception {
            byte[] sha256 = Hashing.sha256(artifact);
            byte[] message = EnvelopeV1Codec.artifactMessage(SignatureMetadata.ED25519, key.keyId(), pluginId,
                    version, Files.size(artifact), sha256);
            return metadata(sign(message));
        }

        SignatureMetadata manifestSignature(String repositoryId, byte[] manifest) throws Exception {
            byte[] sha256 = Hashing.sha256(manifest);
            byte[] message = EnvelopeV1Codec.manifestMessage(repositoryId, manifest.length, sha256);
            return metadata(sign(message));
        }

        private SignatureMetadata metadata(byte[] signature) {
            return new SignatureMetadata(1, SignatureMetadata.ED25519, key.keyId(),
                    Base64.getEncoder().encodeToString(signature));
        }

        private byte[] sign(byte[] message) throws Exception {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(message);
            return signer.sign();
        }
    }
}
