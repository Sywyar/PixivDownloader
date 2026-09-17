package top.sywyar.pixivdownload.sdk.community.emergency;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;
import top.sywyar.pixivdownload.sdk.community.operation.OperationAuthority;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("紧急密钥声明按原生身份授权，仅停用后续操作")
class EmergencyKeyDeclarationTest {
    private static final String SHA = "ab".repeat(20);

    @Test
    @DisplayName("本人可批量停用当前及历史密钥，生成精确请求引用且不修改历史信任记录")
    void declaresRegisteredKeysWithoutPrivateProofs() throws Exception {
        var publisher = publisher();
        var before = publisher.document();
        var request = request(publisher);
        var declaration = request.as(EmergencyKeyDeclaration.class);
        var blocks = declaration.authorize(request, before, authority("101"));
        assertThat(blocks).hasSize(2);
        for (var block : blocks) {
            block.requestRef().verify(request.bytes());
            block.verifyRequest(request);
            assertThat(EmergencyKeyDeclaration.Block.read(block.document(), block.path())).isEqualTo(block);
            assertThat(block.pr().authorAccountId()).isEqualTo("101");
        }
        assertThat(publisher.document().bytes()).isEqualTo(before.bytes());
        assertThat(publisher.signingKeys().stream().map(Publisher.SigningKey::state))
                .containsExactly(TrustedPluginKey.State.ACTIVE, TrustedPluginKey.State.RETIRED);
    }

    @Test
    @DisplayName("不接受其它账号、组织自报授权、陈旧发布者或冒充其它公钥")
    void rejectsUnverifiedAuthorityAndChangedKeys() throws Exception {
        var publisher = publisher();
        var request = request(publisher);
        var declaration = request.as(EmergencyKeyDeclaration.class);
        error("BINDING_MISMATCH", () -> declaration.authorize(request, publisher.document(), authority("202")));
        var other = publisher();
        error("BASELINE_CHANGED", () -> declaration.authorize(request, other.document(), authority("101")));
        var block = declaration.authorize(request, publisher.document(), authority("101")).get(0);
        var mismatched = new EmergencyKeyDeclaration.Block(1, block.owner(),
                new EmergencyKeyDeclaration.Key("other", block.key().fingerprint()), block.requestRef(), block.pr());
        error("EMERGENCY_RECORD_MISMATCH", () -> mismatched.verifyRequest(request));
        var organization = new Publisher(1, publisher.publisherId(), publisher.displayName(),
                new Publisher.GithubAccount("101", "Organization", "org"), publisher.signingKeys());
        var orgRequest = request(organization);
        error("BINDING_MISMATCH", () -> orgRequest.as(EmergencyKeyDeclaration.class)
                .authorize(orgRequest, organization.document(), authority("101")));
        var tree = (ObjectNode) request.value();
        ((ObjectNode) tree.get("payload").get("keys").get(0)).put("fingerprint", "00".repeat(32));
        var forged = canonical(tree);
        error("UNKNOWN_KEY", () -> forged.as(EmergencyKeyDeclaration.class)
                .authorize(forged, publisher.document(), authority("101")));
    }

    @Test
    @DisplayName("封禁按公钥指纹生效，改标识不能绕过，不波及其它密钥")
    void blocksFingerprintsIndependentlyOfKeyIds() throws Exception {
        var publisher = publisher();
        var request = request(publisher);
        var blocks = request.as(EmergencyKeyDeclaration.class).authorize(request, publisher.document(), authority("101"));
        var key = publisher.activeKey().trusted("Publisher");
        error("KEY_DECLARED_COMPROMISED", () -> EmergencyKeyDeclaration.requireAllowed(key, blocks));
        var renamed = new TrustedPluginKey("renamed", key.algorithm(), key.publicKeySpkiBase64(), key.state(), "Publisher", "community", false);
        error("KEY_DECLARED_COMPROMISED", () -> EmergencyKeyDeclaration.requireAllowed(renamed, blocks));
        assertThatCode(() -> EmergencyKeyDeclaration.requireAllowed(publisher().activeKey().trusted("Publisher"), blocks)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("拒绝未知操作、重复选择、错误路径和超过请求预算的字节")
    void rejectsAmbiguousOrOversizedRequests() throws Exception {
        var request = request(publisher());
        var declaration = request.as(EmergencyKeyDeclaration.class);
        error("PATH_MISMATCH", () -> EmergencyKeyDeclaration.read(request, "requests/wrong.json"));
        var tree = (ObjectNode) request.value();
        ((ObjectNode) tree.get("payload")).put("operation", "UNBLOCK_KEY");
        error("SCHEMA_INVALID", () -> canonical(tree));
        var duplicate = (ObjectNode) request.value();
        var keys = (com.fasterxml.jackson.databind.node.ArrayNode) duplicate.get("payload").get("keys");
        keys.add(keys.get(0).deepCopy());
        var repeated = canonical(duplicate);
        error("SCHEMA_INVALID", () -> EmergencyKeyDeclaration.read(repeated, repeated.as(EmergencyKeyDeclaration.class).path()));
        for (var kind : List.of(CommunityJson.Kind.EMERGENCY_REQUEST, CommunityJson.Kind.EMERGENCY_KEY_BLOCK, CommunityJson.Kind.EMERGENCY_STATE)) {
            error("LIMIT_EXCEEDED", () -> CommunityJson.parse(kind, new byte[kind.maximumBytes() + 1]));
        }
        assertThat(EmergencyKeyDeclaration.read(request, declaration.path())).isEqualTo(declaration);
    }

    private static Publisher publisher() throws Exception {
        var generator = KeyPairGenerator.getInstance("Ed25519");
        return new Publisher(1, "example", "Publisher", new Publisher.GithubAccount("101", "User", "login"), List.of(
                new Publisher.SigningKey("current", "Ed25519", Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()), TrustedPluginKey.State.ACTIVE),
                new Publisher.SigningKey("historical", "Ed25519", Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()), TrustedPluginKey.State.RETIRED)));
    }

    private static CommunityJson.Document request(Publisher publisher) {
        var keys = publisher.signingKeys().stream().map(key -> new EmergencyKeyDeclaration.Key(key.keyId(),
                key.trusted(publisher.displayName()).publicKeyFingerprint())).toList();
        var payload = new EmergencyKeyDeclaration.Payload("DECLARE_KEY_COMPROMISE", publisher.owner(), publisher.document().sha256(), keys);
        return canonical(CommunityJson.strictTree(CommunityJson.encode(new EmergencyKeyDeclaration(1, payload, "00".repeat(32))), 65536));
    }

    private static CommunityJson.Document canonical(com.fasterxml.jackson.databind.JsonNode value) {
        var document = CommunityJson.parse(CommunityJson.Kind.EMERGENCY_REQUEST, CommunityJson.encode(value));
        var tree = (ObjectNode) document.value();
        tree.put("requestId", CommunityJson.sha256(CommunityJson.canonicalBody(document)));
        return CommunityJson.parse(CommunityJson.Kind.EMERGENCY_REQUEST, CommunityJson.encode(tree));
    }

    private static OperationAuthority authority(String author) {
        var pr = new CommunityPr("300", 1, author, "400", SHA, SHA, null);
        return new OperationAuthority(pr, new Account(author, "User"), List.of(), null, Set.of());
    }

    private static void error(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(ContractException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
