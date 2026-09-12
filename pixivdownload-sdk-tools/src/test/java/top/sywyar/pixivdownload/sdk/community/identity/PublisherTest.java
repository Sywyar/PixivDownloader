package top.sywyar.pixivdownload.sdk.community.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("发布者历史的身份、公钥及完整文档预算")
class PublisherTest {
    @Test
    @DisplayName("核对账号路径与唯一活动 key，拒绝重复 ID、非规范和错误公钥")
    void validatesIdentityAndKeys() throws Exception {
        var original = fixture();
        var publisher = Publisher.read(original, "publishers/101/example.json");
        assertThat(publisher.trustStore().findByKeyId(publisher.activeKey().keyId())).isPresent();
        error("PATH_MISMATCH", () -> Publisher.read(original, "publishers/102/example.json"));
        var key = publisher.activeKey();
        error("SCHEMA_INVALID", () -> withKeys(publisher, List.of(key, key)).document());
        for (var state : List.of(TrustedPluginKey.State.RETIRED, TrustedPluginKey.State.REVOKED)) {
            error("SCHEMA_INVALID", () -> withKeys(publisher, List.of(key.withState(state))).document());
        }
        error("SCHEMA_INVALID", () -> withKeys(publisher, List.of(key,
                new Publisher.SigningKey("other", key.algorithm(), key.publicKeySpkiBase64(), key.state()))).document());
        for (String invalid : List.of(key.publicKeySpkiBase64().replace("=", ""), "AA==")) {
            error("MALFORMED_SIGNATURE", () -> withKeys(publisher,
                    List.of(new Publisher.SigningKey(key.keyId(), key.algorithm(), invalid, key.state()))).document());
        }
    }

    @Test
    @DisplayName("读取保留原始字节，生成按 keyId 排序且不改写历史 key 状态")
    void preservesHistoryAndSortsOnlyGeneration() throws Exception {
        var document = fixture();
        byte[] raw = document.bytes();
        var publisher = Publisher.read(document);
        var active = publisher.activeKey();
        var older = new Publisher.SigningKey("A:old", active.algorithm(), active.publicKeySpkiBase64(), TrustedPluginKey.State.RETIRED);
        var generated = withKeys(publisher, List.of(active, older)).document();
        assertThat(Publisher.read(generated).signingKeys()).containsExactly(older, active);
        assertThat(document.bytes()).isEqualTo(raw);
        assertThat(Publisher.read(generated).trustStore().findByKeyId(older.keyId()).orElseThrow().state())
                .isEqualTo(TrustedPluginKey.State.RETIRED);
    }

    @Test
    @DisplayName("历史可超过二百五十六把，整体字节超限时完整失败而不截断")
    void historyHasOnlyTheDocumentBudget() throws Exception {
        var publisher = Publisher.read(fixture());
        var active = publisher.activeKey();
        var keys = new ArrayList<Publisher.SigningKey>();
        keys.add(active);
        for (int i = 0; i < 256; i++) keys.add(new Publisher.SigningKey("old:" + i, active.algorithm(),
                active.publicKeySpkiBase64(), i % 2 == 0 ? TrustedPluginKey.State.RETIRED : TrustedPluginKey.State.REVOKED));
        var previous = withKeys(publisher, keys).document();
        assertThat(Publisher.read(previous).signingKeys()).hasSize(257);
        int count = CommunityJson.Kind.PUBLISHER.maximumBytes() / CommunityJson.encode(active).length + 1;
        for (int i = keys.size(); i < count + 257; i++) keys.add(new Publisher.SigningKey("old:" + i, active.algorithm(),
                active.publicKeySpkiBase64(), TrustedPluginKey.State.RETIRED));
        var tooLarge = withKeys(publisher, keys);
        assertThat(CommunityJson.encode(tooLarge).length).isGreaterThan(CommunityJson.Kind.PUBLISHER.maximumBytes());
        error("LIMIT_EXCEEDED", tooLarge::document);
        assertThat(Publisher.read(previous).signingKeys()).hasSize(257);
    }

    private static Publisher withKeys(Publisher value, List<Publisher.SigningKey> keys) {
        return new Publisher(value.schemaVersion(), value.publisherId(), value.displayName(), value.githubAccount(), keys);
    }
    private static CommunityJson.Document fixture() throws Exception {
        try (var input = PublisherTest.class.getResourceAsStream("/community/v1/vectors/structure/publisher.json")) {
            return CommunityJson.parse(CommunityJson.Kind.PUBLISHER, java.util.Objects.requireNonNull(input).readAllBytes());
        }
    }
    private static void error(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
