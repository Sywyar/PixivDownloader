package top.sywyar.pixivdownload.plugin.catalog.community;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.signature.*;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.sdk.community.directory.*;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("客户端目录验签、按需分片与持久化防回滚")
class CommunityDirectoryServiceTest {
    @TempDir Path temp;

    @Test
    @DisplayName("只下载目标桶并精确匹配描述符身份、地址、摘要和认证 key")
    void exactCertification() throws Exception {
        var f = new Fixture(temp);
        f.publish(2, DirectoryEntry.Status.IDENTITY_VERIFIED);
        var lookup = f.service().lookup("alpha");
        assertThat(lookup.cached()).isFalse();
        assertThat(lookup.certifies("alpha", Fixture.DESCRIPTOR, Fixture.HASH, List.of(f.key))).isTrue();
        assertThat(lookup.certifies("other", Fixture.DESCRIPTOR, Fixture.HASH, List.of(f.key))).isFalse();
        assertThat(lookup.certifies("alpha", Fixture.DESCRIPTOR + "?changed", Fixture.HASH, List.of(f.key))).isFalse();
        assertThat(lookup.certifies("alpha", Fixture.DESCRIPTOR, "cd".repeat(32), List.of(f.key))).isFalse();
        assertThat(lookup.certifies("alpha", Fixture.DESCRIPTOR, Fixture.HASH, List.of())).isFalse();
        assertThat(f.requests.stream().filter(url -> url.contains("/shards/"))).hasSize(1);
        assertThat(f.documents.keySet().stream().filter(url -> url.contains("/shards/"))).hasSize(2);
    }

    @Test
    @DisplayName("重启后离线保留已认证桶，回滚与坏分片不覆盖原代，损坏状态不重置水位")
    void durableLastKnownGood() throws Exception {
        var f = new Fixture(temp);
        f.publish(2, DirectoryEntry.Status.IDENTITY_VERIFIED);
        assertThat(f.service().lookup("alpha").sequence()).isEqualTo(2);
        f.documents.clear();
        assertThat(f.service().lookup("alpha").cached()).isTrue();
        assertThatThrownBy(() -> f.service().lookup("beta")).isInstanceOf(RuntimeException.class);
        f.publish(1, DirectoryEntry.Status.LISTED);
        assertThat(f.service().lookup("alpha").sequence()).isEqualTo(2);
        f.publish(3, DirectoryEntry.Status.SUSPENDED);
        f.documents.replaceAll((url, bytes) -> url.contains("/shards/") ? new byte[] { 1 } : bytes);
        assertThat(f.service().lookup("alpha").entry().status()).isEqualTo(DirectoryEntry.Status.IDENTITY_VERIFIED);
        f.publish(3, DirectoryEntry.Status.SUSPENDED);
        assertThat(f.service().lookup("alpha").entry().status()).isEqualTo(DirectoryEntry.Status.SUSPENDED);
        Files.write(f.state, new byte[] { 0, 1 });
        int before = f.requests.size();
        assertThatThrownBy(() -> f.service().lookup("alpha")).isInstanceOf(RuntimeException.class);
        assertThat(f.requests).hasSize(before);
    }

    @Test
    @DisplayName("同序号不同根被拒绝，未认证列出及暂停条目不能授予认证")
    void equivocationAndStatus() throws Exception {
        var f = new Fixture(temp);
        f.publish(1, DirectoryEntry.Status.LISTED);
        assertThat(f.service().lookup("alpha").certifies("alpha", Fixture.DESCRIPTOR, Fixture.HASH, List.of(f.key))).isFalse();
        f.publish(1, DirectoryEntry.Status.IDENTITY_VERIFIED);
        assertThat(f.service().lookup("alpha").entry().status()).isEqualTo(DirectoryEntry.Status.LISTED);
        f.publish(2, DirectoryEntry.Status.SUSPENDED);
        assertThat(f.service().lookup("alpha").certifies("alpha", Fixture.DESCRIPTOR, Fixture.HASH, List.of(f.key))).isFalse();
    }

    private static final class Fixture {
        static final URI BASE = URI.create("https://example.org/");
        static final String DESCRIPTOR = "https://example.org/repository.json";
        static final String HASH = "ab".repeat(32);
        final java.security.KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final TrustedPluginKey key = new TrustedPluginKey("test-community", "Ed25519",
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), TrustedPluginKey.State.ACTIVE, "test", "community", false);
        final Map<String, byte[]> documents = new HashMap<>();
        final List<String> requests = new ArrayList<>();
        final Path state;
        Fixture(Path temp) throws Exception { state = temp.resolve("directory.bin"); }
        CommunityDirectoryService service() {
            return new CommunityDirectoryService(state, BASE, (url, max) -> {
                requests.add(url);
                byte[] bytes = documents.get(url);
                if (bytes == null || bytes.length > max) throw new IllegalStateException("fixture unavailable");
                return bytes;
            }, new PluginSupplyChainVerifier(PluginTrustStores.community(List.of(key))), true);
        }
        void publish(long sequence, DirectoryEntry.Status status) throws Exception {
            documents.clear();
            String time = "2025-01-02T03:04:05Z";
            var owner = new CommunityValues.Owner("101", "User", "example");
            var evidence = new CommunityValues.Reference("evidence/certification.json", 1, HASH);
            var keys = List.of(new DirectoryEntry.CertifiedKey(key.keyId(), key.publicKeyFingerprint()));
            var entries = List.of(new DirectoryEntry("alpha", DESCRIPTOR, HASH, owner, "example", evidence, keys,
                    status, time, time, sequence, null, status == DirectoryEntry.Status.SUSPENDED ? "review" : null),
                    new DirectoryEntry("beta", DESCRIPTOR, HASH, owner, "example", evidence, keys,
                            DirectoryEntry.Status.LISTED, time, time, sequence, null, null));
            var candidate = DirectoryGeneration.generate(CommunityDirectoryService.REPOSITORY_ID, sequence, time, entries);
            String path = "generated/generations/" + sequence + "/directory.json";
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(EnvelopeV1Codec.communityDirectoryMessage(CommunityDirectoryService.REPOSITORY_ID, sequence,
                    candidate.root().bytes().length, HexFormat.of().parseHex(candidate.root().sha256())));
            var signature = new SignatureMetadata(1, "Ed25519", key.keyId(), Base64.getEncoder().encodeToString(signer.sign()));
            documents.put(BASE.resolve("generated/current.json").toString(), CommunityJson.encode(Map.of(
                    "schemaVersion", 1, "sequence", sequence, "directorySignature", signature,
                    "directory", Map.of("path", path, "size", candidate.root().bytes().length, "sha256", candidate.root().sha256()))));
            documents.put(BASE.resolve(path).toString(), candidate.root().bytes());
            candidate.root().as(DirectoryGeneration.Root.class).shards().forEach(ref -> documents.put(
                    ref.resolve(BASE.resolve(path)).toString(), candidate.shards().get(ref.sha256()).bytes()));
        }
    }
}
