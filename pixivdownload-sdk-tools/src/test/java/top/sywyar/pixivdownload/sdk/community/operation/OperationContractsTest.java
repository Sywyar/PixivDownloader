package top.sywyar.pixivdownload.sdk.community.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityOperation;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.*;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.PluginBinding;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("社区操作正文、版本转换与独立角色批准")
class OperationContractsTest {
    private static final String HASH = "ab".repeat(32);
    private static final String NEXT = "cd".repeat(32);
    private static final String HEAD = "ab".repeat(20);

    @Test
    @DisplayName("三类请求核对规范正文摘要与动作专属路径")
    void bindsRequestIdAndPath() throws Exception {
        for (var kind : List.of(CommunityJson.Kind.ROTATION, CommunityJson.Kind.STATUS_REQUEST, CommunityJson.Kind.TRANSFER)) {
            var document = request(kind, fixture(kind));
            String path = path(document);
            read(document, path);
            error("PATH_MISMATCH", () -> read(document, "wrong/" + path));
            var changed = (ObjectNode) document.value();
            changed.put("requestId", HASH);
            var invalid = CommunityJson.parse(kind, CommunityJson.encode(changed));
            error("REQUEST_ID_MISMATCH", () -> read(invalid, path));
            ((ObjectNode) changed.get("payload")).put("explanation", "Another intent");
            var tampered = CommunityJson.parse(kind, CommunityJson.encode(changed));
            error("REQUEST_ID_MISMATCH", () -> read(tampered, path));
        }
    }

    @Test
    @DisplayName("请求证明经现有验签门面验证并拒绝跨域及退休签名")
    void verifiesRealOperationProofs() throws Exception {
        var document = request(CommunityJson.Kind.ROTATION, fixture(CommunityJson.Kind.ROTATION));
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var key = new TrustedPluginKey("Key:One", "Ed25519", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                TrustedPluginKey.State.ACTIVE, "publisher", "community", false);
        byte[] body = CommunityJson.canonicalBody(document);
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(EnvelopeV1Codec.communityOperationMessage(CommunityOperation.PUBLISHER_KEY_ROTATION,
                "Ed25519", key.keyId(), body.length, java.util.HexFormat.of().parseHex(document.value().get("requestId").textValue())));
        var signature = new SignatureMetadata(1, "Ed25519", key.keyId(), Base64.getEncoder().encodeToString(signer.sign()));
        OperationChecks.proof(document, CommunityOperation.PUBLISHER_KEY_ROTATION, signature, key, "/proofs/newKey");
        error("PROOF_REQUIRED", () -> OperationChecks.proof(document, CommunityOperation.PUBLISHER_KEY_ROTATION, null, key, "/proofs/newKey"));
        assertThatThrownBy(() -> OperationChecks.proof(document, CommunityOperation.OWNERSHIP_TRANSFER, signature, key, "/proofs/newKey"))
                .isInstanceOf(ContractException.class);
        var retired = new TrustedPluginKey(key.keyId(), key.algorithm(), key.publicKeySpkiBase64(),
                TrustedPluginKey.State.RETIRED, key.publisher(), key.trustLabel(), false);
        assertThatThrownBy(() -> OperationChecks.proof(document, CommunityOperation.PUBLISHER_KEY_ROTATION, signature, retired, "/proofs/newKey"))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("版本状态矩阵与再次下架拒绝旧恢复请求")
    void preservesSpecificYankAndIrreversibleRevocation() throws Exception {
        try (var input = getClass().getResourceAsStream("/community/v1/vectors/state-transitions.json")) {
            byte[] bytes = java.util.Objects.requireNonNull(input).readAllBytes();
            var vectors = CommunityJson.strictTree(bytes, bytes.length);
            CommunityJson.validateStructure("stateVectors", vectors);
            for (var vector : vectors.get("versionStatus")) {
                var before = new VersionState("demo", "2.3.4", HASH,
                        VersionState.State.valueOf(vector.get("before").textValue()), vector.get("decisionSha256").textValue());
                var request = status(vector.get("action").textValue(), "OTHER", vector.get("yankedDecisionSha256").textValue());
                if (vector.has("error")) {
                    error(vector.get("error").textValue(), () -> before.transition(request, vector.get("newDecisionSha256").textValue()));
                } else {
                    var after = before.transition(request, vector.get("newDecisionSha256").textValue());
                    assertThat(after.state().name()).as(vector.get("id").textValue()).isEqualTo(vector.get("after").textValue());
                    assertThat(after.decisionSha256()).isEqualTo(after.state() == VersionState.State.ACTIVE
                            ? null : vector.get("newDecisionSha256").textValue());
                }
            }
        }
        var active = new VersionState("demo", "2.3.4", HASH, VersionState.State.ACTIVE, null);
        var yanked = active.transition(status("YANK", "FUNCTIONAL_DEFECT", null), HASH);
        var restore = status("UNYANK", "ISSUE_RESOLVED", HASH);
        assertThat(yanked.state()).isEqualTo(VersionState.State.YANKED);
        var restored = yanked.transition(restore, NEXT);
        assertThat(restored).isEqualTo(active);
        var again = restored.transition(status("YANK", "OTHER", null), NEXT);
        error("BASELINE_CHANGED", () -> again.transition(restore, HASH));
        var otherPackage = new VersionState("demo", "2.3.4", NEXT, VersionState.State.ACTIVE, null);
        error("BINDING_MISMATCH", () -> otherPackage.transition(status("YANK", "OTHER", null), HASH));
        error("SCHEMA_INVALID", () -> status("YANK", "ISSUE_RESOLVED", null));
    }

    @Test
    @DisplayName("当前绑定按完整身份区分账号与发布者，原始基线变化可检测")
    void distinguishesBindingAndBaseline() throws Exception {
        var document = CommunityJson.parse(CommunityJson.Kind.BINDING, CommunityJson.encode(fixture(CommunityJson.Kind.BINDING)));
        var binding = PluginBinding.read(document, "plugin-bindings/demo.json");
        binding.requireOwner(new Owner("101", "User", "example"));
        error("BINDING_MISMATCH", () -> binding.requireOwner(new Owner("101", "Organization", "example")));
        error("BINDING_MISMATCH", () -> binding.requireOwner(new Owner("202", "User", "example")));
        error("PATH_MISMATCH", () -> PluginBinding.read(document, "plugin-bindings/other.json"));
        OperationChecks.baseline(document.sha256(), document, "/baseline");
        error("BASELINE_CHANGED", () -> OperationChecks.baseline(NEXT, document, "/baseline"));
        var transfer = new PluginBinding(1, binding.pluginId(), binding.owner(), NEXT, binding.updatedAt());
        assertThat(CommunityJson.sha256(CommunityJson.encode(transfer))).isNotEqualTo(document.sha256());
    }

    @Test
    @DisplayName("跨账号批准必须来自不同自然人与独立 PR，路径不能冒用组织 ID")
    void requiresIndependentCrossAccountApprovals() throws Exception {
        var request = transfer("100", "200", "Organization");
        var authority = authority(request, true);
        var from = approval(request, TransferApproval.Role.FROM, "101", 2);
        var to = approval(request, TransferApproval.Role.TO, "202", 3);
        TransferApproval.requireApprovals(List.of(from, to), request, authority);
        error("APPROVAL_REQUIRED", () -> TransferApproval.requireApprovals(List.of(to), request, authority));
        error("APPROVAL_REQUIRED", () -> TransferApproval.requireApprovals(
                List.of(from, approval(request, TransferApproval.Role.TO, "101", 3)), request, authority));
        error("APPROVAL_REQUIRED", () -> TransferApproval.requireApprovals(
                List.of(from, approval(request, TransferApproval.Role.TO, "202", 2)), request, authority));
        var badPath = new TransferApproval.Input(from.document(), from.path().replace("/101.json", "/100.json"),
                from.pr(), from.actualAuthor(), true);
        error("PATH_MISMATCH", () -> TransferApproval.read(badPath, request, authority));
        var nonPerson = new TransferApproval.Input(from.document(), from.path(), from.pr(), new Account("101", "Organization"), true);
        error("BINDING_MISMATCH", () -> TransferApproval.read(nonPerson, request, authority));
        var noRepresentation = new OperationAuthority(authority.proposalPr(), authority.actualAuthor(), List.of(), authority.approval(), Set.of("999"));
        error("BINDING_MISMATCH", () -> TransferApproval.read(from, request, noRepresentation));
    }

    @Test
    @DisplayName("同数字账号可用一份 PR 明确批准两角色，恢复仍需受保护批准")
    void acceptsSameAccountRolesAndRequiresRecoveryReview() throws Exception {
        var request = transfer("100", "100", "Organization");
        var authority = authority(request, true);
        var from = approval(request, TransferApproval.Role.FROM, "101", 2);
        var to = approval(request, TransferApproval.Role.TO, "101", 2);
        TransferApproval.requireApprovals(List.of(from, to), request, authority);
        error("APPROVAL_REQUIRED", () -> TransferApproval.requireApprovals(List.of(to), request, authority));
        var tree = fixture(CommunityJson.Kind.TRANSFER);
        var payload = (ObjectNode) tree.get("payload");
        payload.put("mode", "RECOVERY");
        payload.putArray("recoveryEvidence").addObject().put("path", "evidence/control.json").put("size", 2).put("sha256", HASH);
        var document = request(CommunityJson.Kind.TRANSFER, tree);
        var recovery = OwnershipTransferRequest.read(document, path(document));
        var target = approval(recovery, TransferApproval.Role.TO, "202", 3);
        error("RECOVERY_REVIEW_REQUIRED", () -> TransferApproval.requireApprovals(List.of(target), recovery, authority(recovery, false)));
        TransferApproval.requireApprovals(List.of(target), recovery, authority(recovery, true));
        var approved = authority(recovery, true);
        var unauthorized = new OperationAuthority(approved.proposalPr(), approved.actualAuthor(), approved.representations(), approved.approval(), Set.of());
        error("APPROVAL_REQUIRED", () -> unauthorized.requireApproval(recovery.requestId(), true));
    }

    private static OperationAuthority authority(OwnershipTransferRequest request, boolean recovery) {
        var evidence = evidence();
        var from = request.payload().from();
        var to = request.payload().to();
        return new OperationAuthority(pr("101", 1), new Account("101", "User"), List.of(
                new OperationAuthority.Representation(from, "101", evidence),
                new OperationAuthority.Representation(to, "101", evidence),
                new OperationAuthority.Representation(to, "202", evidence)),
                new OperationAuthority.Approval(request.requestId(), HEAD, Set.of("999"), recovery, evidence), Set.of("999"));
    }

    private static Evidence evidence() {
        byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new Evidence(Reference.of("evidence/review.json", bytes), bytes);
    }

    private static CommunityPr pr(String author, long number) { return new CommunityPr("300", number, author, "400", HEAD, HEAD, null); }

    private static TransferApproval.Input approval(OwnershipTransferRequest request, TransferApproval.Role role, String author, long number) {
        var document = CommunityJson.parse(CommunityJson.Kind.APPROVAL, CommunityJson.encode(new TransferApproval(1, request.requestId(), role)));
        String path = "ownership-transfers/" + request.payload().pluginId() + "/" + request.requestId() + "/approvals/"
                + (role == TransferApproval.Role.FROM ? "from" : "to") + "/" + author + ".json";
        return new TransferApproval.Input(document, path, pr(author, number), new Account(author, "User"), true);
    }

    private static OwnershipTransferRequest transfer(String from, String to, String type) throws Exception {
        var tree = fixture(CommunityJson.Kind.TRANSFER);
        var p = (ObjectNode) tree.get("payload");
        ((ObjectNode) p.get("from")).put("accountId", from).put("accountType", type);
        ((ObjectNode) p.get("to")).put("accountId", to).put("accountType", type);
        var document = request(CommunityJson.Kind.TRANSFER, tree);
        return OwnershipTransferRequest.read(document, path(document));
    }

    private static VersionStatusRequest status(String action, String reason, String yank) throws Exception {
        var tree = fixture(CommunityJson.Kind.STATUS_REQUEST);
        var payload = (ObjectNode) tree.get("payload");
        payload.put("action", action).put("reasonCode", reason);
        if (yank != null) payload.put("yankedDecisionSha256", yank);
        var document = request(CommunityJson.Kind.STATUS_REQUEST, tree);
        return VersionStatusRequest.read(document, path(document));
    }

    private static ObjectNode fixture(CommunityJson.Kind kind) throws Exception {
        try (var input = OperationContractsTest.class.getResourceAsStream("/community/v1/vectors/structure/" + kind.definition() + ".json")) {
            return (ObjectNode) CommunityJson.parse(kind, java.util.Objects.requireNonNull(input).readAllBytes()).value();
        }
    }

    private static CommunityJson.Document request(CommunityJson.Kind kind, ObjectNode value) {
        var first = CommunityJson.parse(kind, CommunityJson.encode(value));
        value.put("requestId", CommunityJson.sha256(CommunityJson.canonicalBody(first)));
        return CommunityJson.parse(kind, CommunityJson.encode(value));
    }

    private static String path(CommunityJson.Document document) {
        var p = document.value().get("payload");
        String id = document.value().get("requestId").textValue();
        return switch (document.kind()) {
            case ROTATION -> "key-rotations/" + p.get("githubAccount").get("id").textValue() + "/" + p.get("publisherId").textValue() + "/" + id + ".json";
            case STATUS_REQUEST -> "version-status-requests/" + p.get("owner").get("accountId").textValue() + "/" + p.get("pluginId").textValue() + "/" + p.get("version").textValue() + "/" + id + ".json";
            case TRANSFER -> "ownership-transfers/" + p.get("pluginId").textValue() + "/" + id + "/proposal.json";
            default -> throw new IllegalArgumentException();
        };
    }

    private static void read(CommunityJson.Document document, String path) {
        switch (document.kind()) {
            case ROTATION -> KeyRotationRequest.read(document, path);
            case STATUS_REQUEST -> VersionStatusRequest.read(document, path);
            case TRANSFER -> OwnershipTransferRequest.read(document, path);
            default -> throw new IllegalArgumentException();
        }
    }

    private static void error(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
