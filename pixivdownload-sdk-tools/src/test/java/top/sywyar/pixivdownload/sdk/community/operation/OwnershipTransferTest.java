package top.sywyar.pixivdownload.sdk.community.operation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.*;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.PluginBinding;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;
import top.sywyar.pixivdownload.sdk.community.review.HumanReviews;

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
    @DisplayName("接收方的原申请与所有者原生 Review 在一个 PR 内生效，旧 head、撤回与伪造作者均拒绝")
    void confirmsBothPartiesOnOriginalRequest() throws Exception {
        var binding = binding(new Owner("101", "User", "original"));
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var target = publisher("202", "target", "target-key", pair);
        var request = transfer(binding, target, target.document(), pair, OwnershipTransferRequest.Mode.REGULAR, null);
        var base = contextFor(request, "202", false);
        var original = base.authority().proposalPr();
        var pr = new top.sywyar.pixivdownload.sdk.community.format.CommunityPr(original.githubRepositoryId(), original.number(),
                original.authorAccountId(), original.headRepositoryId(), original.headSha(), original.baseSha(), null);
        var authority = new OperationAuthority(pr, base.authority().actualAuthor(), List.of(), base.authority().approval(), base.authority().authorizedReviewers());
        var context = new OperationContext(base.request(), authority, null, NOW, base.evidence(), Map.of());
        var bytes = CommunityJson.encode(Map.of("id", 70, "state", "APPROVED", "commit_id", HEAD, "user", Map.of("id", 101, "type", "User")));
        var evidence = new Evidence(Reference.of("records/" + CommunityJson.sha256(bytes) + ".json", bytes), bytes);
        var review = new HumanReviews.NativeReview("70", pr.githubRepositoryId(), pr.number(), new Account("101", "User"),
                HEAD, HumanReviews.NativeState.APPROVED, NOW, evidence, null);
        var previous = approvals(request, "101", "202", false);
        var from = new TransferApproval.Input(previous.get(0).document(), previous.get(0).path(), pr, review.reviewer(), true, review);
        var to = new TransferApproval.Input(previous.get(1).document(), previous.get(1).path(), pr, new Account("202", "User"), true);
        var result = OwnershipTransfer.apply(context, binding, target.document(), null, List.of(from, to)).result();
        var audit = OperationAudit.read(result.audit());
        assertThat(audit.result()).isEqualTo("PREPARED");
        assertThat(audit.prEvidence()).containsExactly(pr);
        assertThat(audit.relatedRecords()).contains(evidence.reference());
        assertThat(result.evidence().get(evidence.reference().path()).bytes()).isEqualTo(bytes);
        for (var state : List.of(HumanReviews.NativeState.COMMENTED, HumanReviews.NativeState.CHANGES_REQUESTED, HumanReviews.NativeState.DISMISSED)) {
            var invalid = new HumanReviews.NativeReview("70", pr.githubRepositoryId(), pr.number(), review.reviewer(), HEAD, state, NOW, evidence, null);
            var input = new TransferApproval.Input(from.document(), from.path(), pr, review.reviewer(), true, invalid);
            error("REVIEW_MISMATCH", () -> OwnershipTransfer.apply(context, binding, target.document(), null, List.of(input, to)));
        }
        var stale = new HumanReviews.NativeReview("70", pr.githubRepositoryId(), pr.number(), review.reviewer(), "ff".repeat(20),
                HumanReviews.NativeState.APPROVED, NOW, evidence, null);
        error("REVIEW_MISMATCH", () -> OwnershipTransfer.apply(context, binding, target.document(), null,
                List.of(new TransferApproval.Input(from.document(), from.path(), pr, review.reviewer(), true, stale), to)));
        error("BINDING_MISMATCH", () -> OwnershipTransfer.apply(context, binding, target.document(), null,
                List.of(new TransferApproval.Input(from.document(), from.path(), pr, review.reviewer(), true), to)));
        error("REVIEW_MISMATCH", () -> OwnershipTransfer.apply(context, binding, target.document(), null,
                List.of(new TransferApproval.Input(from.document(), from.path(), pr, new Account("202", "User"), true, review), to)));
        var sourcePair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var source = publisher("101", "original", "source-key", sourcePair).document();
        var ownerProof = new String(CommunityJson.encode(proof(request, "targetKey", "source-key", sourcePair)
                .value().get("proofs").get("targetKey")), java.nio.charset.StandardCharsets.UTF_8);
        var signedFrom = new TransferApproval.Input(from.document(), from.path(), pr, review.reviewer(), true, review, ownerProof);
        var automatic = new OperationAuthority(pr, authority.actualAuthor(), List.of(), null, java.util.Set.of(),
                new OperationAuthority.SignedStatus(request.value().get("requestId").textValue(), HEAD, evidence));
        var automaticContext = new OperationContext(base.request(), automatic, null, NOW, base.evidence(), Map.of());
        var signed = OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(signedFrom, to), source).result();
        assertThat(OperationAudit.read(signed.audit()).authorization()).isEqualTo("SIGNED_OWNER");
        assertThat(OperationAudit.read(signed.audit()).reviewerAccountIds()).isEmpty();
        assertThat(OperationAudit.read(signed.audit()).relatedRecords()).anyMatch(ref -> ref.sha256().equals(source.sha256()));
        error("PROOF_REQUIRED", () -> OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(from, to), source));
        error("UNKNOWN_KEY", () -> OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(signedFrom, to)));
        var rotated = publisher("101", "original", "rotated-key", KeyPairGenerator.getInstance("Ed25519").generateKeyPair()).document();
        error("UNKNOWN_KEY", () -> OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(signedFrom, to), rotated));
        var wrongSource = publisher("303", "original", "source-key", sourcePair).document();
        error("BINDING_MISMATCH", () -> OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(signedFrom, to), wrongSource));
        var broken = new TransferApproval.Input(from.document(), from.path(), pr, review.reviewer(), true, review,
                ownerProof.replace("source-key", "target-key"));
        error("UNKNOWN_KEY", () -> OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(broken, to), source));
        var forged = new TransferApproval.Input(from.document(), from.path(), pr, review.reviewer(), true, review,
                new String(CommunityJson.encode(proof(request, "targetKey", "source-key", pair).value().get("proofs").get("targetKey")),
                        java.nio.charset.StandardCharsets.UTF_8));
        error("INVALID_SIGNATURE", () -> OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(forged, to), source));
        var padded = new TransferApproval.Input(from.document(), from.path(), pr, review.reviewer(), true, review,
                ownerProof + " ".repeat((int) CommunityJson.Kind.APPROVAL.maximumBytes() - ownerProof.length()));
        assertThat(OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(padded, to), source).replayed()).isFalse();
        var oversized = new TransferApproval.Input(from.document(), from.path(), pr, review.reviewer(), true, review, padded.ownerProof() + " ");
        error("LIMIT_EXCEEDED", () -> OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(oversized, to), source));
        error("REVIEW_MISMATCH", () -> OwnershipTransfer.apply(automaticContext, binding, target.document(), null, List.of(signedFrom, previous.get(1)), source));
    }

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
