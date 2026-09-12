package top.sywyar.pixivdownload.sdk.community.operation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.*;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.PluginBinding;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.sdk.community.operation.OperationTestInputs.*;

@DisplayName("所有权转移的完整身份、目标公钥、独立批准与写入范围")
class OwnershipTransferTest {
    @Test
    @DisplayName("跨账号使用独立双方批准，只替换 binding，已有目标变化使请求过期")
    void transfersToExistingPublisher() throws Exception {
        var from = new Owner("101", "User", "original");
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var target = publisher("202", "target", "target-key", pair);
        var binding = binding(from);
        var publisher = target.document();
        var request = transfer(binding, target, publisher, pair, OwnershipTransferRequest.Mode.REGULAR, null);
        var context = contextFor(request, "101", false);
        var approvals = approvals(request, "101", "202", false);
        var result = OwnershipTransfer.apply(context, binding, publisher, null, approvals).result();
        assertThat(result.writes()).containsOnlyKeys("plugin-bindings/demo.json");
        var updated = PluginBinding.read(result.written("plugin-bindings/demo.json", CommunityJson.Kind.BINDING), "plugin-bindings/demo.json");
        assertThat(updated.owner()).isEqualTo(target.owner());
        assertThat(updated.effectiveRequestId()).isEqualTo(request.value().get("requestId").textValue());
        error("APPROVAL_REQUIRED", () -> OwnershipTransfer.apply(context, binding, publisher, null, List.of(approvals.get(1))));
        error("BINDING_MISMATCH", () -> OwnershipTransfer.apply(contextFor(request, "303", false), binding, publisher, null, approvals));
        var other = publisher("202", "target", "rotated", KeyPairGenerator.getInstance("Ed25519").generateKeyPair()).document();
        error("BASELINE_CHANGED", () -> OwnershipTransfer.apply(context, binding, other, null, approvals));
        var changedBinding = binding(new Owner("303", "User", "another"));
        error("BASELINE_CHANGED", () -> OwnershipTransfer.apply(context, changedBinding, publisher, null, approvals));
        var repeat = new OperationContext(context.request(), null, null, NOW, Map.of(), Map.of(updated.effectiveRequestId(), result));
        assertThat(OwnershipTransfer.apply(repeat, changedBinding, other, null, List.of()).result()).isSameAs(result);
    }

    @Test
    @DisplayName("同账号的双角色可共用一个 PR，新 publisher 与 binding 一起生成且不能覆盖已注册记录")
    void registersNewTargetInOneCompleteResult() throws Exception {
        var binding = binding(new Owner("101", "User", "original"));
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var target = publisher("101", "target", "target-key", pair);
        var request = transfer(binding, target, null, pair, OwnershipTransferRequest.Mode.REGULAR, null);
        var context = contextFor(request, "101", false);
        var approvals = approvals(request, "101", "101", true);
        var result = OwnershipTransfer.apply(context, binding, null, "current-login", approvals).result();
        assertThat(result.writes()).containsOnlyKeys("plugin-bindings/demo.json", target.path());
        var registered = Publisher.read(result.written(target.path(), CommunityJson.Kind.PUBLISHER));
        assertThat(registered.owner()).isEqualTo(target.owner());
        assertThat(registered.githubAccount().loginAtRegistration()).isEqualTo("current-login");
        var audit = OperationAudit.read(result.audit());
        assertThat(audit.relatedRecords()).contains(result.writes().get(target.path()));
        for (var reference : audit.relatedRecords()) {
            var missing = new HashMap<>(result.evidence()); missing.remove(reference.path());
            error("REVIEW_MISMATCH", () -> new OperationResult(result.audit(), missing, result.writes()));
        }
        error("BASELINE_CHANGED", () -> OwnershipTransfer.apply(context, binding, target.document(), "login", approvals));
        error("APPROVAL_REQUIRED", () -> OwnershipTransfer.apply(context, binding, null, "login", List.of(approvals.get(1))));
        var invalidProof = proof(request, "targetKey", "target-key", KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        error("INVALID_SIGNATURE", () -> OwnershipTransfer.apply(contextFor(invalidProof, "101", false), binding, null, "login", approvals));
        assertThat(PluginBinding.read(binding, "plugin-bindings/demo.json").owner().publisherId()).isEqualTo("original");
    }

    @Test
    @DisplayName("恢复转移明确缺失原方，控制证据和受保护批准缺一不可")
    void recoveryRequiresRetrievableControlEvidence() throws Exception {
        var binding = binding(new Owner("101", "User", "original"));
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var target = publisher("202", "target", "target-key", pair);
        byte[] bytes = CommunityJson.encode(Map.of("repositoryControl", "verified"));
        var control = new Evidence(Reference.of("evidence/control.json", bytes), bytes);
        var request = transfer(binding, target, target.document(), pair, OwnershipTransferRequest.Mode.RECOVERY, List.of(control.reference()));
        var base = contextFor(request, "202", true);
        var evidence = new HashMap<>(base.evidence()); evidence.put(control.reference().path(), control);
        var context = new OperationContext(base.request(), base.authority(), List.of(control.reference()), NOW, evidence, Map.of());
        var targetApproval = List.of(approvals(request, "101", "202", false).get(1));
        assertThat(OwnershipTransfer.apply(context, binding, target.document(), null, targetApproval).replayed()).isFalse();
        var missing = new OperationContext(base.request(), base.authority(), List.of(control.reference()), NOW, base.evidence(), Map.of());
        error("REVIEW_MISMATCH", () -> OwnershipTransfer.apply(missing, binding, target.document(), null, targetApproval));
        error("RECOVERY_REVIEW_REQUIRED", () -> OwnershipTransfer.apply(contextFor(request, "202", false), binding, target.document(), null, targetApproval));
    }

    private static CommunityJson.Document binding(Owner owner) {
        return CommunityJson.parse(CommunityJson.Kind.BINDING, CommunityJson.encode(new PluginBinding(1, "demo", owner, null, NOW)));
    }
    private static CommunityJson.Document transfer(CommunityJson.Document binding, Publisher target, CommunityJson.Document existing,
                                                   KeyPair pair, OwnershipTransferRequest.Mode mode, List<Reference> recovery) throws Exception {
        var from = PluginBinding.read(binding, "plugin-bindings/demo.json").owner();
        var key = target.activeKey();
        var payload = new OwnershipTransferRequest.Payload("demo", binding.sha256(), from, target.owner(), existing == null ? null : existing.sha256(),
                new OwnershipTransferRequest.TargetKey(key.keyId(), existing == null ? key.algorithm() : null, existing == null ? key.publicKeySpkiBase64() : null),
                existing == null ? target.displayName() : null, mode, "Transfer stewardship", recovery);
        return proof(request(CommunityJson.Kind.TRANSFER, new OwnershipTransferRequest(1, payload, HASH,
                new OwnershipTransferRequest.Proofs(PLACEHOLDER))), "targetKey", key.keyId(), pair);
    }
    private static OperationContext contextFor(CommunityJson.Document request, String author, boolean recovery) {
        return context(request, "ownership-transfers/demo/" + request.value().get("requestId").textValue() + "/proposal.json", author, recovery);
    }
    private static List<TransferApproval.Input> approvals(CommunityJson.Document request, String from, String to, boolean samePr) {
        String id = request.value().get("requestId").textValue();
        var result = new java.util.ArrayList<TransferApproval.Input>();
        for (var role : TransferApproval.Role.values()) {
            String actor = role == TransferApproval.Role.FROM ? from : to;
            String rolePath = role == TransferApproval.Role.FROM ? "from" : "to";
            var document = CommunityJson.parse(CommunityJson.Kind.APPROVAL, CommunityJson.encode(new TransferApproval(1, id, role)));
            result.add(new TransferApproval.Input(document, "ownership-transfers/demo/" + id + "/approvals/" + rolePath + "/" + actor + ".json",
                    pr(actor, samePr || role == TransferApproval.Role.FROM ? 2 : 3), new Account(actor, "User"), true));
        }
        return result;
    }
    private static void error(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
