package top.sywyar.pixivdownload.sdk.community.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.sdk.community.operation.OperationTestInputs.*;

@DisplayName("密钥轮换的独立写入、持有证明、恢复与原结果重放")
class PublisherKeyRotationTest {
    @Test
    @DisplayName("三种轮换原因保留完整历史且只写同一 publisher")
    void rotatesWithoutChangingOtherState() throws Exception {
        var old = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var next = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var value = publisher("101", "example", "old", old);
        var retired = new Publisher.SigningKey("history", "Ed25519", spki(old), TrustedPluginKey.State.REVOKED);
        var current = new Publisher(1, value.publisherId(), value.displayName(), value.githubAccount(), List.of(retired, value.activeKey())).document();
        for (var reason : KeyRotationRequest.Reason.values()) {
            var request = rotation(current, reason, next, "new");
            request = proof(request, "oldKey", "old", old);
            var outcome = PublisherKeyRotation.apply(contextFor(request, "101", false), current);
            assertThat(outcome.replayed()).isFalse();
            assertThat(outcome.result().writes()).containsOnlyKeys(value.path());
            var updated = Publisher.read(outcome.result().written(value.path(), CommunityJson.Kind.PUBLISHER));
            assertThat(updated.owner()).isEqualTo(value.owner());
            assertThat(updated.githubAccount()).isEqualTo(value.githubAccount());
            assertThat(updated.signingKeys()).hasSize(3).contains(retired);
            assertThat(updated.activeKey().keyId()).isEqualTo("new");
            assertThat(updated.signingKeys().stream().filter(key -> key.keyId().equals("old")).findFirst().orElseThrow().state())
                    .isEqualTo(TrustedPluginKey.State.RETIRED);
            assertThat(OperationAudit.read(outcome.result().audit()).beforeRef().sha256()).isEqualTo(current.sha256());
        }
    }

    @Test
    @DisplayName("缺旧证明须恢复批准，存在的错误旧证明不能被忽略，历史 ID 不重绑")
    void requiresProofsAndProtectedRecovery() throws Exception {
        var old = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var next = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var current = publisher("101", "example", "old", old).document();
        var request = rotation(current, KeyRotationRequest.Reason.KEY_LOST, next, "new");
        error("RECOVERY_REVIEW_REQUIRED", () -> PublisherKeyRotation.apply(contextFor(request, "101", false), current));
        assertThat(PublisherKeyRotation.apply(contextFor(request, "101", true), current).replayed()).isFalse();
        var invalidOld = proof(request, "oldKey", "old", next);
        error("INVALID_SIGNATURE", () -> PublisherKeyRotation.apply(contextFor(invalidOld, "101", true), current));
        error("BINDING_MISMATCH", () -> PublisherKeyRotation.apply(contextFor(request, "202", true), current));
        var reused = rotation(current, KeyRotationRequest.Reason.KEY_LOST, next, "old");
        error("SCHEMA_INVALID", () -> PublisherKeyRotation.apply(contextFor(reused, "101", true), current));
        var invalidNew = proof(request, "newKey", "new", old);
        error("INVALID_SIGNATURE", () -> PublisherKeyRotation.apply(contextFor(invalidNew, "101", true), current));
    }

    @Test
    @DisplayName("个人例行换钥以新旧密钥证明自动授权，其它原因和身份不能套用")
    void authorizesOnlyProvenPersonalRoutineRotation() throws Exception {
        var old = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var next = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var current = publisher("101", "example", "old", old).document();
        var request = proof(rotation(current, KeyRotationRequest.Reason.ROUTINE_ROTATION, next, "new"), "oldKey", "old", old);
        var result = PublisherKeyRotation.apply(signedContext(request), current).result();
        assertThat(result.audit().value().get("authorization").textValue()).isEqualTo("SIGNED_OWNER");
        assertThat(result.audit().value().get("reviewerAccountIds")).isEmpty();
        assertThat(Publisher.read(result.written(Publisher.read(current).path(), CommunityJson.Kind.PUBLISHER)).activeKey().keyId()).isEqualTo("new");
        var invalidOld = proof(request, "oldKey", "old", next);
        error("INVALID_SIGNATURE", () -> PublisherKeyRotation.apply(signedContext(invalidOld), current));
        var invalidNew = proof(request, "newKey", "new", old);
        error("INVALID_SIGNATURE", () -> PublisherKeyRotation.apply(signedContext(invalidNew), current));
        var missingOld = rotation(current, KeyRotationRequest.Reason.ROUTINE_ROTATION, next, "new");
        error("APPROVAL_REQUIRED", () -> PublisherKeyRotation.apply(signedContext(missingOld), current));
        for (var reason : List.of(KeyRotationRequest.Reason.KEY_LOST, KeyRotationRequest.Reason.KEY_COMPROMISED)) {
            var recovery = proof(rotation(current, reason, next, "new"), "oldKey", "old", old);
            error("APPROVAL_REQUIRED", () -> PublisherKeyRotation.apply(signedContext(recovery), current));
        }
        var reused = proof(rotation(current, KeyRotationRequest.Reason.ROUTINE_ROTATION, old, "another-id"), "oldKey", "old", old);
        error("SCHEMA_INVALID", () -> PublisherKeyRotation.apply(signedContext(reused), current));
        var organization = (ObjectNode) current.value();
        ((ObjectNode) organization.get("githubAccount")).put("type", "Organization");
        var organizationDocument = CommunityJson.parse(CommunityJson.Kind.PUBLISHER, CommunityJson.encode(organization));
        var organizationRequest = proof(rotation(organizationDocument, KeyRotationRequest.Reason.ROUTINE_ROTATION, next, "new"), "oldKey", "old", old);
        error("APPROVAL_REQUIRED", () -> signedContext(organizationRequest).authority().requireAuthorization(organizationRequest, false));
    }

    private static OperationContext signedContext(CommunityJson.Document request) {
        var context = contextFor(request, "101", false);
        var authority = context.authority();
        var signed = new OperationAuthority(authority.proposalPr(), authority.actualAuthor(), List.of(), null, java.util.Set.of(),
                new OperationAuthority.SignedStatus(request.value().get("requestId").textValue(), HEAD, authority.approval().evidence()));
        return new OperationContext(context.request(), signed, null, NOW, context.evidence(), Map.of());
    }

    @Test
    @DisplayName("同 ID 重跑先返回原审计，新请求遇到陈旧 publisher 基线失败")
    void replaysBeforeCheckingCurrentBaseline() throws Exception {
        var old = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var next = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var value = publisher("101", "example", "old", old);
        var current = value.document();
        var request = rotation(current, KeyRotationRequest.Reason.KEY_LOST, next, "new");
        var context = contextFor(request, "101", true);
        var result = PublisherKeyRotation.apply(context, current).result();
        var after = result.written(value.path(), CommunityJson.Kind.PUBLISHER);
        var repeated = new OperationContext(context.request(), null, null, NOW, Map.of(),
                Map.of(request.value().get("requestId").textValue(), result));
        var replay = PublisherKeyRotation.apply(repeated, after);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result()).isSameAs(result);
        var changed = (ObjectNode) request.value();
        ((ObjectNode) changed.get("payload")).put("explanation", "Another request");
        var stale = request(CommunityJson.Kind.ROTATION, changed);
        error("BASELINE_CHANGED", () -> PublisherKeyRotation.apply(contextFor(stale, "101", true), after));
        assertThat(result.audit().bytes()).isEqualTo(replay.result().audit().bytes());
    }

    private static CommunityJson.Document rotation(CommunityJson.Document current, KeyRotationRequest.Reason reason,
                                                   KeyPair next, String newId) throws Exception {
        var value = Publisher.read(current);
        var payload = new KeyRotationRequest.Payload(value.publisherId(), value.owner().account(), current.sha256(), value.activeKey().keyId(),
                new KeyRotationRequest.PublicKey(newId, "Ed25519", spki(next)), reason, "Replace the signing key");
        var document = request(CommunityJson.Kind.ROTATION, new KeyRotationRequest(1, payload, HASH,
                new KeyRotationRequest.Proofs(PLACEHOLDER, null)));
        return proof(document, "newKey", newId, next);
    }
    private static OperationContext contextFor(CommunityJson.Document document, String actor, boolean recovery) {
        return context(document, "key-rotations/101/example/" + document.value().get("requestId").textValue() + ".json", actor, recovery);
    }
    private static void error(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
