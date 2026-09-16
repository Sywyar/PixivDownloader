package top.sywyar.pixivdownload.plugin.catalog.community;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogRevocationAdmissionPolicy;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogRevocationService;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionRequest;
import top.sywyar.pixivdownload.sdk.community.review.CommunityReview;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.signature.*;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.submission.DescriptorSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("社区包双签与审核事实进入客户端安装链")
class CommunityPackageServiceTest {
    @TempDir Path temp;

    @Test
    @DisplayName("真实包和两种签名通过，伪造审核摘要、包内声明及原发布者签名被拒绝")
    void packageReviewBindings() throws Exception {
        var publisher = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var community = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var artifact = temp.resolve("demo.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(artifact))) {
            zip.putNextEntry(new ZipEntry("plugin.properties"));
            zip.write(("plugin.id=demo\nplugin.version=2.3.4\nplugin.class=example.DemoPlugin\n"
                    + "pixiv.execution-mode=host-process-full-trust\npixiv.risk-signals=NETWORK\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(artifact);
        String sha = CommunityJson.sha256(bytes);
        var owner = vector("structure/publisher.json");
        ((ObjectNode) owner.path("signingKeys").get(0)).put("publicKeySpkiBase64",
                Base64.getEncoder().encodeToString(publisher.getPublic().getEncoded()));
        var submission = vector("submission.json");
        submission.put("pluginId", "demo");
        ((ObjectNode) submission.path("source")).put("commit", "12".repeat(20));
        var original = sign(publisher, "Example:Key", EnvelopeV1Codec.artifactMessage(
                "Ed25519", "Example:Key", "demo", "2.3.4", bytes.length, HexFormat.of().parseHex(sha)));
        ((ObjectNode) submission.path("package")).put("expectedSize", bytes.length).put("sha256", sha)
                .set("signature", tree(original));
        Map<String, byte[]> documents = new HashMap<>();
        var review = vector("structure/review.json");
        review.put("packageSize", bytes.length).put("packageSha256", sha);
        review.set("submissionRef", reference(documents, "history/submission.json", submission));
        review.set("descriptor", tree(DescriptorSnapshot.from(PluginPackageReader.inspect(artifact).descriptor(), "demo", "2.3.4")));
        var reviewRef = reference(documents, "history/review.json", review);
        var communitySignature = sign(community, "community-test", EnvelopeV1Codec.communityPackageMessage(
                "Ed25519", "community-test", PluginRepository.COMMUNITY_ID, "demo", "2.3.4", bytes.length,
                HexFormat.of().parseHex(sha), "SOURCE_REVIEWED", "12".repeat(20),
                HexFormat.of().parseHex(reviewRef.path("sha256").asText())));
        var published = vector("structure/published.json");
        published.set("package", submission.get("package").deepCopy());
        published.set("communitySignature", tree(communitySignature));
        published.set("reviewRef", reviewRef);
        published.set("submissionRef", review.get("submissionRef"));
        published.set("historicalPublisherRef", reference(documents, "history/publisher.json", owner));
        reference(documents, "published/demo/2.3.4.json", published);
        var packageJson = tree(Map.of("version", "2.3.4", "packageUrl", "https://example.org/plugin.jar",
                "expectedSizeBytes", bytes.length, "sha256", sha, "signature", communitySignature,
                "assuranceLevel", "SOURCE_REVIEWED", "sourceCommit", "12".repeat(20),
                "reviewRef", reviewRef, "historicalOwner", review.get("owner")));
        var pkg = new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(packageJson, PluginCatalogPackage.class);
        var http = mock(PluginCatalogHttpClient.class);
        when(http.fetchBytes(anyString(), anyLong())).thenAnswer(call -> {
            byte[] result = documents.get(call.getArgument(0, String.class).substring(CommunityDirectoryService.BASE_URL.length()));
            if (result == null || result.length > call.getArgument(1, Long.class)) throw new IllegalStateException("fixture missing");
            return result;
        });
        var key = new TrustedPluginKey("community-test", "Ed25519", Base64.getEncoder().encodeToString(community.getPublic().getEncoded()),
                TrustedPluginKey.State.ACTIVE, "test", "test", false);
        var service = new CommunityPackageService(ignored -> http,
                ignored -> new PluginSupplyChainVerifier(PluginTrustStores.community(List.of(key))));
        var repository = PluginRepository.community(true, 1000, 1000, 1024 * 1024, 1024 * 1024);
        var evidence = service.verify(artifact, repository, "demo", pkg);
        assertThat(evidence.reviewSha256()).isEqualTo(reviewRef.path("sha256").asText());
        var changedReview = review.deepCopy();
        ((ObjectNode) changedReview.path("descriptor").path("riskDeclaration")).putArray("signals").add("PROCESS_EXECUTION");
        assertThatThrownBy(() -> CommunityReview.read(CommunityJson.parse(CommunityJson.Kind.REVIEW,
                CommunityJson.encode(changedReview))).requireDescriptor(PluginPackageReader.inspect(artifact).descriptor()))
                .isInstanceOf(RuntimeException.class);

        var store = new PluginCatalogTrustStateStore(temp.resolve("trust.json"));
        var registry = new PluginRepositoryRegistry(new PluginCatalogProperties());
        var revocations = new PluginCatalogRevocationService(ignored -> http, store, registry);
        var admission = new PluginCatalogRevocationAdmissionPolicy(registry, store);
        var request = new PluginArtifactAdmissionRequest(repository.repositoryId(), "demo", "2.3.4",
                sha, communitySignature.keyId(), "community", evidence);
        String now = Instant.now().toString();
        store.acceptRevocations(repository.repositoryId(), new PluginCatalogTrustStateStore.RevocationSnapshot(
                1L, "ab".repeat(32), now, Instant.now().minusSeconds(1).toString(), now, List.of()));
        assertThat(revocations.status(repository, "demo", pkg)).isEqualTo("STALE");
        store.acceptRevocations(repository.repositoryId(), new PluginCatalogTrustStateStore.RevocationSnapshot(
                2L, "cd".repeat(32), now, Instant.now().plusSeconds(3600).toString(), now, List.of(
                new PluginCatalogTrustStateStore.RevocationEntry("PUBLISHER", null, null, null, null,
                        review.path("owner").path("publisherId").asText(), "REVOKED", "MALWARE", now))));
        assertThat(revocations.status(repository, "demo", pkg)).isEqualTo("REVOKED");
        assertThat(admission.evaluate(request).code()).isEqualTo("PLUGIN_REVOKED");
        assertThat(admission.evaluate(new PluginArtifactAdmissionRequest(repository.repositoryId(), "demo", "2.3.4",
                sha, communitySignature.keyId(), "community")).allowed()).isTrue();
        assertThatThrownBy(() -> service.verify(artifact, repository, "other", pkg)).isInstanceOf(RuntimeException.class);
        documents.put("history/review.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.verify(artifact, repository, "demo", pkg)).isInstanceOf(RuntimeException.class);
        documents.put("history/review.json", CommunityJson.encode(review));
        ((ObjectNode) owner.path("signingKeys").get(0)).put("publicKeySpkiBase64",
                Base64.getEncoder().encodeToString(community.getPublic().getEncoded()));
        published.set("historicalPublisherRef", reference(documents, "history/publisher.json", owner));
        reference(documents, "published/demo/2.3.4.json", published);
        assertThatThrownBy(() -> service.verify(artifact, repository, "demo", pkg)).isInstanceOf(RuntimeException.class);
        Files.write(artifact, new byte[]{1});
        assertThatThrownBy(() -> service.verify(artifact, repository, "demo", pkg)).isInstanceOf(RuntimeException.class);
    }

    private static ObjectNode vector(String name) throws Exception {
        return (ObjectNode) CommunityJson.strictTree(Files.readAllBytes(Path.of("../contracts/community/v1/vectors/" + name)), 256 * 1024);
    }
    private static ObjectNode tree(Object value) {
        return (ObjectNode) CommunityJson.strictTree(CommunityJson.encode(value), 256 * 1024);
    }
    private static ObjectNode reference(Map<String, byte[]> documents, String path, ObjectNode value) {
        byte[] bytes = CommunityJson.encode(value);
        documents.put(path, bytes);
        return tree(Map.of("path", path, "size", bytes.length, "sha256", CommunityJson.sha256(bytes)));
    }
    private static SignatureMetadata sign(KeyPair pair, String id, byte[] message) throws Exception {
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(message);
        return new SignatureMetadata(1, "Ed25519", id, Base64.getEncoder().encodeToString(signer.sign()));
    }
}
