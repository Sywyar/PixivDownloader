package top.sywyar.pixivdownload.sdk.community.review;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.signature.*;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.*;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.PluginBinding;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;
import top.sywyar.pixivdownload.sdk.community.submission.DescriptorSnapshot;
import top.sywyar.pixivdownload.sdk.community.submission.VersionSubmission;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

class PublishedVersionTest {
    @TempDir Path temp;
    private static final String HEAD = "12".repeat(20);
    private static final String TIME = "2025-01-02T03:04:05Z";

    @Test @DisplayName("发布冻结原包原签及完整审核，历史退役可验而吊销失败")
    void verifiesBothSignaturesAndHistoricalKeyStates() throws Exception {
        var f = new Fixture("1.0.0", null, true);
        var result = PublishedVersion.publish(f.input(), Map.of(), null);
        var published = PublishedVersion.read(result.document());
        assertThat(result.replayed()).isFalse();
        assertThat(published.artifact()).isEqualTo(f.submission.artifact());
        assertThat(published.historicalPublisherRef()).isEqualTo(f.publisherEvidence.reference());
        published.verifyHistory(f.jar, f.publisher.document(), f.verifier, "sample.repo", f.review.evidence);
        var successor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var keys = List.of(f.publisher.signingKeys().get(0).withState(TrustedPluginKey.State.RETIRED),
                new Publisher.SigningKey("next:key", "Ed25519", spki(successor), TrustedPluginKey.State.ACTIVE));
        var retired = new Publisher(1, "example", "Publisher", f.publisher.githubAccount(), keys);
        published.verifyHistory(f.jar, retired.document(), f.verifier, "sample.repo", f.review.evidence);
        f.publisherEvidence = evidence("history/retired-publisher.json", retired.document().bytes());
        f.review.evidence.put(f.publisherEvidence.reference().path(), f.publisherEvidence);
        assertThatThrownBy(() -> PublishedVersion.publish(f.input(), Map.of(), null)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("RETIRED_KEY");
        var revoked = new Publisher(1, "example", "Publisher", f.publisher.githubAccount(),
                List.of(keys.get(0).withState(TrustedPluginKey.State.REVOKED), keys.get(1)));
        assertThatThrownBy(() -> published.verifyHistory(f.jar, revoked.document(), f.verifier, "sample.repo", f.review.evidence))
                .isInstanceOf(ContractException.class).extracting("code").isEqualTo("REVOKED_KEY");
        var wrongKey = new Publisher(1, "example", "Publisher", f.publisher.githubAccount(),
                List.of(new Publisher.SigningKey("publisher:key", "Ed25519", spki(successor), TrustedPluginKey.State.ACTIVE)));
        assertThatThrownBy(() -> published.verifyHistory(f.jar, wrongKey.document(), f.verifier, "sample.repo", f.review.evidence))
                .isInstanceOf(ContractException.class).extracting("code").isEqualTo("BINDING_MISMATCH");
    }

    @Test @DisplayName("同版本同包先返回旧字节，不重新审核或替换源码许可证及签名")
    void replayPreservesOriginalRecordBeforeCurrentChecks() throws Exception {
        var f = new Fixture("1.0.0", null, true);
        var original = PublishedVersion.publish(f.input(), Map.of(), null).document();
        var index = Map.of(new PublishedVersion.Key(f.submission.pluginId(), f.submission.version()), original);
        var tree = (ObjectNode) f.review.submissionDocument.value();
        tree.put("publisherId", "new-owner");
        ((ObjectNode) tree.get("source")).put("commit", "34".repeat(20));
        ((ObjectNode) tree.get("license")).put("expression", "Apache-2.0");
        ((ObjectNode) tree.get("package").get("signature")).put("value", Base64.getEncoder().encodeToString(new byte[64]));
        var replacement = evidence("new-submission.json", CommunityJson.encode(tree));
        var replayInput = new PublishedVersion.Publication(replacement, null, null, null, null, null,
                null, null, null, null, Map.of(), 0, 0);
        var replay = PublishedVersion.publish(replayInput, index, null);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.document()).isSameAs(original);
        ((ObjectNode) tree.get("package")).put("sha256", "cd".repeat(32));
        var changed = new PublishedVersion.Publication(evidence("new-submission.json", CommunityJson.encode(tree)), null,
                null, null, null, null, null, null, null, null, Map.of(), 0, 0);
        assertThatThrownBy(() -> PublishedVersion.publish(changed, index, null)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("VERSION_ALREADY_PUBLISHED");
    }

    @Test @DisplayName("缺证据、未合并、错包及社区错误签名均不能形成发布记录")
    void rejectsMissingOrMismatchedPublicationFacts() throws Exception {
        var unmerged = new Fixture("1.0.0", null, false);
        assertThatThrownBy(() -> PublishedVersion.publish(unmerged.input(), Map.of(), null)).isInstanceOf(ContractException.class);
        var f = new Fixture("1.0.0", null, true);
        for (String path : List.copyOf(f.review.evidence.keySet())) {
            var old = f.review.evidence.remove(path);
            assertThatThrownBy(() -> PublishedVersion.publish(f.input(), Map.of(), null)).as(path).isInstanceOf(ContractException.class);
            f.review.evidence.put(path, old);
        }
        var signature = f.communitySignature;
        f.communitySignature = new SignatureMetadata(1, "Ed25519", "community:key", Base64.getEncoder().encodeToString(new byte[64]));
        assertThatThrownBy(() -> PublishedVersion.publish(f.input(), Map.of(), null)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("INVALID_SIGNATURE");
        f.communitySignature = signature;
        byte[] original = Files.readAllBytes(f.jar);
        Files.write(f.jar, new byte[]{1});
        assertThatThrownBy(() -> PublishedVersion.publish(f.input(), Map.of(), null)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("HASH_MISMATCH");
        Files.write(f.jar, original);
        var changedBinding = new PluginBinding(1, f.submission.pluginId(), f.publisher.owner(), "cd".repeat(32), TIME);
        f.binding = CommunityJson.parse(CommunityJson.Kind.BINDING, CommunityJson.encode(changedBinding));
        assertThatThrownBy(() -> PublishedVersion.publish(f.input(), Map.of(), null)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("REVIEW_MISMATCH");
    }

    @Test @DisplayName("后续版本核对前次源码而转移后首版要求完整审阅")
    void requiresPreviousSourceOnlyForSameOwner() throws Exception {
        var first = new Fixture("1.0.0", null, true);
        var previous = PublishedVersion.publish(first.input(), Map.of(), null).document();
        var next = new Fixture("1.0.1", PublishedVersion.read(previous).sourceCommit(), true);
        assertThat(PublishedVersion.publish(next.input(), Map.of(), previous).replayed()).isFalse();
        var full = new Fixture("1.0.1", null, true);
        assertThatThrownBy(() -> PublishedVersion.publish(full.input(), Map.of(), previous)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("BASELINE_CHANGED");
        var tree = (ObjectNode) previous.value();
        ((ObjectNode) tree.get("owner")).put("accountId", "303").put("publisherId", "predecessor");
        var former = CommunityJson.parse(CommunityJson.Kind.PUBLISHED, CommunityJson.encode(tree));
        assertThat(PublishedVersion.publish(full.input(), Map.of(), former).replayed()).isFalse();
        assertThatThrownBy(() -> PublishedVersion.publish(next.input(), Map.of(), former)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("BASELINE_CHANGED");
    }

    private final class Fixture {
        final Path jar;
        final Publisher publisher;
        final VersionSubmission submission;
        final VersionReviewTest.Fixture review;
        final PluginSupplyChainVerifier verifier;
        final Evidence reviewEvidence;
        Evidence publisherEvidence;
        SignatureMetadata communitySignature;
        CommunityJson.Document binding;

        Fixture(String version, String previous, boolean merged) throws Exception {
            var publisherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var communityKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            publisher = new Publisher(1, "example", "Publisher", new Publisher.GithubAccount("101", "User", "example"),
                    List.of(new Publisher.SigningKey("publisher:key", "Ed25519", spki(publisherKey), TrustedPluginKey.State.ACTIVE)));
            var tree = (ObjectNode) VersionReviewTest.Fixture.sampleSubmission().value();
            tree.put("version", version);
            ((ObjectNode) tree.get("source")).put("previousReviewedCommit", previous);
            String pluginId = tree.get("pluginId").textValue();
            jar = Files.createTempFile(temp, "published-", ".jar");
            try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
                zip.putNextEntry(new ZipEntry("plugin.properties"));
                zip.write(("plugin.id=" + pluginId + "\nplugin.version=" + version
                        + "\nplugin.class=example.Plugin\nplugin.requires=1.0\npixiv.execution-mode=declarative-process\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            byte[] bytes = Files.readAllBytes(jar);
            String hash = CommunityJson.sha256(bytes);
            var originalSignature = sign(publisherKey, "publisher:key", EnvelopeV1Codec.artifactMessage("Ed25519",
                    "publisher:key", pluginId, version, bytes.length, HexFormat.of().parseHex(hash)));
            var artifact = (ObjectNode) tree.get("package");
            artifact.put("expectedSize", bytes.length).put("sha256", hash);
            artifact.set("signature", CommunityJson.strictTree(CommunityJson.encode(originalSignature), 1024));
            var submissionDocument = CommunityJson.parse(CommunityJson.Kind.SUBMISSION, CommunityJson.encode(tree));
            submission = VersionSubmission.read(submissionDocument);
            binding = CommunityJson.parse(CommunityJson.Kind.BINDING, CommunityJson.encode(new PluginBinding(1,
                    pluginId, publisher.owner(), "ab".repeat(32), TIME)));
            review = new VersionReviewTest.Fixture(submissionDocument,
                    new CommunityPr("1001", 17, "101", "1002", HEAD, HEAD, merged ? "56".repeat(20) : null), binding.sha256(),
                    DescriptorSnapshot.from(PluginPackageReader.inspect(jar).descriptor(), submission));
            reviewEvidence = evidence("history/review.json", review.reviewDocument.bytes());
            publisherEvidence = evidence("history/publisher.json", publisher.document().bytes());
            review.evidence.put(reviewEvidence.reference().path(), reviewEvidence);
            review.evidence.put(publisherEvidence.reference().path(), publisherEvidence);
            communitySignature = sign(communityKey, "community:key", EnvelopeV1Codec.communityPackageMessage("Ed25519",
                    "community:key", "sample.repo", pluginId, version, bytes.length, HexFormat.of().parseHex(hash),
                    "SOURCE_REVIEWED", submission.source().commit(), HexFormat.of().parseHex(review.reviewDocument.sha256())));
            verifier = new PluginSupplyChainVerifier(PluginTrustStores.community(List.of(new TrustedPluginKey("community:key",
                    "Ed25519", spki(communityKey), TrustedPluginKey.State.ACTIVE, "Community", "community", false))));
        }
        PublishedVersion.Publication input() {
            return new PublishedVersion.Publication(evidence("history/submission.json", review.submissionDocument.bytes()),
                    reviewEvidence, binding, publisherEvidence,
                    new VersionReview.Facts(publisher.owner(), review.descriptor, review.pr, review.admission, review.actualProof, review.publication),
                    jar, "sample.repo", verifier, communitySignature, TIME, review.evidence, 256 * 1024, 256 * 1024);
        }
    }

    private static Evidence evidence(String path, byte[] bytes) { return new Evidence(Reference.of(path, bytes), bytes); }
    private static String spki(KeyPair key) { return Base64.getEncoder().encodeToString(key.getPublic().getEncoded()); }
    private static SignatureMetadata sign(KeyPair key, String keyId, byte[] message) throws Exception {
        var signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate()); signer.update(message);
        return new SignatureMetadata(1, "Ed25519", keyId, Base64.getEncoder().encodeToString(signer.sign()));
    }
}
