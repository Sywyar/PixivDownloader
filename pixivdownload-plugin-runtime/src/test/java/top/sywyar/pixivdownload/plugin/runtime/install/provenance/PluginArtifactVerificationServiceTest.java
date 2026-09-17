package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件工件统一复验服务")
class PluginArtifactVerificationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("开发模式切换不改变未签名本地包的字节复验结果")
    void developmentSwitchDoesNotChangeUnsignedByteVerification() throws Exception {
        Path artifact = temporaryDirectory.resolve("demo.jar");
        Files.write(artifact, new byte[]{1, 2, 3, 4});
        PluginDescriptor descriptor = descriptor();
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        AtomicBoolean developmentMode = new AtomicBoolean(true);
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        PluginArtifactVerificationService service = new PluginArtifactVerificationService(
                ignored -> verifier, developmentMode::get);

        VerificationResult installed = service.verifyForInstall(artifact, descriptor, origin);
        PluginProvenanceRecord provenance = PluginProvenanceRecord.from(origin, installed);
        developmentMode.set(false);
        VerificationResult installAfterDisable = service.verifyForInstall(artifact, descriptor, origin);
        VerificationResult installedAfterDisable = service.verifyInstalled(artifact, descriptor, provenance);
        developmentMode.set(true);
        VerificationResult installedAfterEnable = service.verifyInstalled(artifact, descriptor, provenance);

        assertThat(installed.status()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
        assertThat(provenance.developmentOnly()).isTrue();
        assertThat(installAfterDisable.status()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
        assertThat(installedAfterDisable.status()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
        assertThat(installedAfterEnable.status()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
    }

    @Test
    @DisplayName("社区复核签名及审核摘要跨重启复验，旧签名域和篡改来源均被拒绝")
    void communityEvidenceSurvivesOfflineReverification() throws Exception {
        var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var key = new top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey("community-test", "Ed25519",
                java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey.State.ACTIVE, "test", "community", false);
        var verifier = new PluginSupplyChainVerifier(top.sywyar.pixivdownload.plugin.signature.PluginTrustStores.community(List.of(key)));
        var service = new PluginArtifactVerificationService(verifier);
        Path artifact = Files.write(temporaryDirectory.resolve("demo.jar"), new byte[]{1, 2, 3, 4});
        String sha = hash(Files.readAllBytes(artifact));
        String review = "{}";
        var evidence = new top.sywyar.pixivdownload.plugin.runtime.install.model.CommunityPackageEvidence(
                "SOURCE_REVIEWED", "ab".repeat(20), hash(review.getBytes(java.nio.charset.StandardCharsets.UTF_8)), review);
        var signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec.communityPackageMessage(
                "Ed25519", key.keyId(), "community-test", "demo", "1.0.0", Files.size(artifact),
                java.util.HexFormat.of().parseHex(sha), evidence.assuranceLevel(), evidence.sourceCommit(),
                java.util.HexFormat.of().parseHex(evidence.reviewSha256())));
        var signature = new top.sywyar.pixivdownload.plugin.signature.SignatureMetadata(1, "Ed25519", key.keyId(),
                java.util.Base64.getEncoder().encodeToString(signer.sign()));
        var generic = PluginPackageOrigin.forTrustedCatalog("community-test", false, Files.size(artifact), sha, signature);
        var origin = generic.withCommunityEvidence(evidence);
        var result = service.verifyForInstall(artifact, descriptor(), origin);
        assertThat(result.accepted()).isTrue();
        assertThat(service.verifyForInstall(artifact, descriptor(), generic).accepted()).isFalse();
        var store = new PluginProvenanceStore(temporaryDirectory);
        store.write(artifact, origin, result);
        var restored = new PluginProvenanceStore(temporaryDirectory).read(artifact).orElseThrow();
        assertThat(restored.communityEvidence()).isEqualTo(evidence);
        assertThat(service.verifyInstalled(artifact, descriptor(), restored).accepted()).isTrue();
        var changedReview = new top.sywyar.pixivdownload.plugin.runtime.install.model.CommunityPackageEvidence(
                "SOURCE_REVIEWED", evidence.sourceCommit(), hash("{ }".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "{ }");
        assertThat(service.verifyForInstall(artifact, descriptor(), origin.withCommunityEvidence(changedReview)).accepted()).isFalse();
        Files.write(artifact, new byte[]{4, 3, 2, 1});
        assertThat(service.verifyInstalled(artifact, descriptor(), restored).accepted()).isFalse();
    }

    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor(
                "demo", "demo", "1.0.0", VersionRequirement.unspecified(), List.of(),
                "example.DemoPlugin", "demo", "plugin.demo.name", null, null, null,
                PluginKind.FEATURE);
    }
}
