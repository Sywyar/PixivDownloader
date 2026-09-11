package top.sywyar.pixivdownload.sdk.community.directory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("目录完整代的分桶、签名、预算与原子采用")
class DirectoryGenerationTest {
    private static final String HASH = "ab".repeat(32);
    private static final String TIME = "2025-01-02T03:04:05Z";
    private static final URI ROOT_URL = URI.create("https://example.org/catalog/root.json");

    @Test
    @DisplayName("按 UTF-8 哈希分桶且仅生成非空桶，不同输入顺序生成相同字节")
    void deterministicGeneration() throws Exception {
        var f = new Fixture();
        var entries = List.of(entry("alpha", DirectoryEntry.Status.IDENTITY_VERIFIED, null),
                entry("beta", DirectoryEntry.Status.LISTED, null), entry("removed", DirectoryEntry.Status.REMOVED, null));
        var first = generate(1, entries);
        var reversed = new ArrayList<>(entries); java.util.Collections.reverse(reversed);
        var second = generate(1, reversed);
        assertThat(first.root().bytes()).isEqualTo(second.root().bytes());
        var generation = f.store.adopt(first, f.sign(first));
        assertThat(generation.root().shards()).hasSize(2);
        assertThat(generation.root().shards()).extracting(DirectoryGeneration.ShardReference::prefix).isSorted();
        assertThat(first.shards().values().stream().flatMap(d -> d.as(DirectoryGeneration.Shard.class).entries().stream()))
                .extracting(DirectoryEntry::repositoryId).containsExactlyInAnyOrder("alpha", "beta");
        for (var ref : generation.root().shards()) {
            assertThat(ref.resolve(ROOT_URL).toString()).isEqualTo("https://example.org/catalog/shards/" + ref.sha256() + ".json");
        }
        var higher = generate(2, entries);
        assertThat(higher.shards().keySet()).isEqualTo(first.shards().keySet());
        assertThat(f.store.adopt(higher, f.sign(higher)).root().sequence()).isEqualTo(2);
    }

    @Test
    @DisplayName("同代同摘要返回原记录，回滚或同代不同字节拒绝并保留上一代")
    void monotonicAndIdempotent() throws Exception {
        var f = new Fixture();
        var first = generate(2, List.of(entry("alpha", DirectoryEntry.Status.LISTED, null)));
        var original = f.store.adopt(first, f.sign(first));
        assertThat(f.store.adopt(first, f.sign(first))).isSameAs(original);
        var older = generate(1, List.of());
        assertThatThrownBy(() -> f.store.adopt(older, f.sign(older))).isInstanceOf(ContractException.class);
        var conflict = generate(2, List.of(entry("beta", DirectoryEntry.Status.LISTED, null)));
        assertThatThrownBy(() -> f.store.adopt(conflict, f.sign(conflict))).isInstanceOf(ContractException.class);
        assertThat(f.store.current()).isSameAs(original);
    }

    @Test
    @DisplayName("缺失分片、错摘要、错桶及签名失败均不能部分切换入口")
    void failedCandidateKeepsPrevious() throws Exception {
        var f = new Fixture();
        var first = generate(1, List.of(entry("alpha", DirectoryEntry.Status.LISTED, null)));
        var original = f.store.adopt(first, f.sign(first));
        var next = generate(2, List.of(entry("beta", DirectoryEntry.Status.LISTED, null)));
        assertThatThrownBy(() -> f.store.adopt(new DirectoryGeneration.Candidate(next.root(), Map.of()), f.sign(next)))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> f.store.adopt(next, f.sign(first))).isInstanceOf(ContractException.class);
        var shard = next.shards().values().iterator().next();
        var data = (ObjectNode) shard.value();
        ((ObjectNode) data.get("entries").get(0)).put("repositoryId", "alpha");
        var changed = CommunityJson.parse(CommunityJson.Kind.DIRECTORY_SHARD, CommunityJson.encode(data));
        var root = (ObjectNode) next.root().value();
        ((ObjectNode) root.get("shards").get(0)).put("size", changed.bytes().length).put("sha256", changed.sha256());
        var wrongBucket = new DirectoryGeneration.Candidate(CommunityJson.parse(CommunityJson.Kind.DIRECTORY_ROOT, CommunityJson.encode(root)),
                Map.of(changed.sha256(), changed));
        assertThatThrownBy(() -> f.store.adopt(wrongBucket, f.sign(wrongBucket))).isInstanceOf(ContractException.class);
        var mismatch = new DirectoryGeneration.Candidate(next.root(), Map.of(shard.sha256(), changed));
        assertThatThrownBy(() -> f.store.adopt(mismatch, f.sign(next))).isInstanceOf(ContractException.class);
        assertThat(f.store.current()).isSameAs(original);
    }

    @Test
    @DisplayName("每条记录最多四把当前认证 key，重复 ID 或指纹及未认证状态不获得信任")
    void keyAndStatusSemantics() {
        var base = entry("alpha", DirectoryEntry.Status.IDENTITY_VERIFIED, null);
        var keys = new ArrayList<DirectoryEntry.CertifiedKey>();
        for (int i = 1; i <= 4; i++) keys.add(new DirectoryEntry.CertifiedKey("Key:" + i, String.valueOf(i).repeat(64)));
        withKeys(base, keys).validate();
        keys.add(new DirectoryEntry.CertifiedKey("Key:5", "5".repeat(64)));
        assertThatThrownBy(() -> withKeys(base, keys).validate()).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> withKeys(base, List.of(keys.get(0), keys.get(0))).validate()).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> withKeys(base, List.of(keys.get(0), new DirectoryEntry.CertifiedKey("Other", keys.get(0).spkiSha256()))).validate())
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> withKeys(base, List.of()).validate()).isInstanceOf(ContractException.class);
        var listed = withKeys(entry("alpha", DirectoryEntry.Status.LISTED, null), List.of()); listed.validate();
        assertThat(listed.discoverable()).isTrue(); assertThat(listed.offersCertifiedIdentity()).isFalse();
        assertThat(listed.allowsManualRepositoryAddition()).isTrue();
        var suspended = entry("alpha", DirectoryEntry.Status.SUSPENDED, "Awaiting verification"); suspended.validate();
        assertThat(suspended.discoverable()).isTrue(); assertThat(suspended.allowsManualRepositoryAddition()).isFalse();
        assertThat(entry("alpha", DirectoryEntry.Status.REMOVED, null).discoverable()).isFalse();
        assertThatThrownBy(() -> entry("alpha", DirectoryEntry.Status.SUSPENDED, null).validate()).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("单桶可以超过五百一十二条，整体超字节预算失败而不截断")
    void shardBudgetWithoutCountCap() throws Exception {
        var f = new Fixture();
        var entries = new ArrayList<DirectoryEntry>();
        for (int i = 0; entries.size() < 513; i++) {
            String id = "repository-" + i;
            if (DirectoryGeneration.prefix(id).equals("ab")) entries.add(entry(id, DirectoryEntry.Status.LISTED, null));
        }
        var candidate = generate(1, entries);
        var original = f.store.adopt(candidate, f.sign(candidate));
        assertThat(candidate.shards()).hasSize(1);
        assertThat(candidate.shards().values().iterator().next().as(DirectoryGeneration.Shard.class).entries()).hasSize(513);
        var oversized = entries.stream().map(e -> entry(e.repositoryId(), DirectoryEntry.Status.LISTED, "x".repeat(2048))).toList();
        assertThatThrownBy(() -> generate(2, oversized)).isInstanceOfSatisfying(ContractException.class,
                error -> assertThat(error.code()).isEqualTo("LIMIT_EXCEEDED"));
        assertThat(f.store.current()).isSameAs(original);
    }

    @Test
    @DisplayName("分片地址按受认证 root 解析并继续拒绝凭据、非 HTTPS 和非法地址")
    void shardLocations() {
        for (String location : List.of("http://example.org/a", "https://user:password@example.org/a", "file:///a", "a#fragment", "a b", "")) {
            assertThatThrownBy(() -> new DirectoryGeneration.ShardReference("ab", location, 1, HASH).resolve(ROOT_URL))
                    .isInstanceOf(ContractException.class);
        }
        assertThat(new DirectoryGeneration.ShardReference("ab", "shards/data.json?version=1", 1, HASH).resolve(ROOT_URL).getQuery())
                .isEqualTo("version=1");
    }

    private static DirectoryGeneration.Candidate generate(long sequence, List<DirectoryEntry> entries) {
        return DirectoryGeneration.generate("test-catalog", sequence, TIME, entries);
    }
    private static DirectoryEntry entry(String id, DirectoryEntry.Status status, String reason) {
        return new DirectoryEntry(id, "https://example.org/repository.json", HASH, new Owner("101", "User", "example"), "example",
                new Reference("evidence/certification.json", 1, HASH), List.of(new DirectoryEntry.CertifiedKey("Example:Key", HASH)),
                status, TIME, TIME, 1, null, reason);
    }
    private static DirectoryEntry withKeys(DirectoryEntry value, List<DirectoryEntry.CertifiedKey> keys) {
        return new DirectoryEntry(value.repositoryId(), value.descriptorUrl(), value.descriptorSha256(), value.publisher(),
                value.publisherLoginSnapshot(), value.certificationRef(), keys, value.status(), value.firstReviewedAt(), value.lastReviewedAt(),
                value.directorySequence(), value.githubRepositoryId(), value.reason());
    }
    private static final class Fixture {
        final KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final DirectoryGenerations store;
        Fixture() throws Exception {
            var key = new TrustedPluginKey("Test:Directory", "Ed25519", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    TrustedPluginKey.State.ACTIVE, "example", "community", false);
            store = new DirectoryGenerations("test-catalog", ROOT_URL, new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key))));
        }
        SignatureMetadata sign(DirectoryGeneration.Candidate candidate) throws Exception {
            var root = candidate.root().as(DirectoryGeneration.Root.class);
            var signer = Signature.getInstance("Ed25519"); signer.initSign(pair.getPrivate());
            signer.update(EnvelopeV1Codec.communityDirectoryMessage(root.repositoryId(), root.sequence(),
                    candidate.root().bytes().length, HexFormat.of().parseHex(candidate.root().sha256())));
            return new SignatureMetadata(1, "Ed25519", "Test:Directory", Base64.getEncoder().encodeToString(signer.sign()));
        }
    }
}
