package top.sywyar.pixivdownload.sdk.community.operation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.*;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.PluginBinding;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.sdk.community.operation.OperationTestInputs.*;

class VersionStatusTest {
    private static final String NEXT = "2025-02-04T04:05:06Z";

    @Test @DisplayName("恢复快照与旧客户端共用向量且仅含既有撤销状态")
    void sharesSnapshotVectorsWithExistingClient() throws Exception {
        try (var input = getClass().getResourceAsStream("/community/v1/vectors/revocation-snapshots.json")) {
            var vectors = CommunityJson.strictTree(input.readAllBytes(), VersionRevocations.MAX_BYTES);
            for (var item : vectors.get("cases")) {
                var f = new Fixture();
                if (!item.get("independentAction").isNull()) f.current = VersionRevocations.generate("sample.repo", 1, NOW, NEXT,
                        List.of(f.independent(item.get("independentAction").textValue())));
                f.apply(VersionStatusRequest.Action.YANK, null, "withdraw");
                var result = f.apply(VersionStatusRequest.Action.UNYANK, f.state.decisionSha256(), "restore");
                assertThat(CommunityJson.strictTree(result.revocations().archive().bytes(), VersionRevocations.MAX_BYTES))
                        .as(item.get("id").textValue()).isEqualTo(item.get("after"));
            }
        }
    }

    @Test @DisplayName("下架恢复只移除指定决定并保留独立限制及原审计")
    void restoresOnlyManagedYankAndReplaysWithoutPublishingOldSnapshot() throws Exception {
        var f = new Fixture();
        var independent = f.independent("YANKED");
        f.current = VersionRevocations.generate("sample.repo", 1, NOW, NEXT, List.of(independent));
        var yank = f.apply(VersionStatusRequest.Action.YANK, null, "first");
        assertThat(yank.state().state()).isEqualTo(VersionState.State.YANKED);
        assertThat(yank.operation().result().writes()).containsOnlyKeys("revocations.json");
        assertThat(yank.revocations().restrictions()).hasSize(2);
        assertThat(yank.revocations().document().entries()).hasSize(1);
        var unyankRequest = f.request(VersionStatusRequest.Action.UNYANK, f.state.decisionSha256(), "restore");
        var unyank = f.apply(unyankRequest, false);
        assertThat(unyank.state().state()).isEqualTo(VersionState.State.ACTIVE);
        assertThat(unyank.revocations().restrictions()).containsExactly(independent);
        assertThat(unyank.revocations().document().entries()).containsExactly(independent.entry());
        assertThat(OperationAudit.read(unyank.operation().result().audit()).revocationSequence()).isEqualTo(3);
        var second = f.apply(VersionStatusRequest.Action.YANK, null, "again");
        var replayContext = new OperationContext(f.context(unyankRequest, false).request(), null, null, NOW,
                Map.of(), Map.of(unyankRequest.value().get("requestId").textValue(), unyank.operation().result()));
        var replay = VersionStatus.apply(replayContext, null, null, f.state, f.current, 1, NEXT);
        assertThat(replay.operation().replayed()).isTrue();
        assertThat(replay.operation().result()).isSameAs(unyank.operation().result());
        assertThat(replay.revocations()).isSameAs(second.revocations());
        assertThat(replay.state()).isSameAs(second.state());
        assertThatThrownBy(() -> f.apply(f.request(VersionStatusRequest.Action.UNYANK,
                yank.state().decisionSha256(), "stale restore"), false)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("BASELINE_CHANGED");
    }

    @Test @DisplayName("当前所有者才能管理历史包且错误证明不能退化为恢复")
    void checksCurrentOwnershipProofRecoveryAndBaseline() throws Exception {
        var f = new Fixture();
        var doc = f.request(VersionStatusRequest.Action.YANK, null, "managed by successor");
        var context = f.context(doc, false);
        var predecessor = publisher("101", "former", "old:key", KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertThatThrownBy(() -> VersionStatus.apply(context, f.binding, predecessor.document(), f.state, f.current, 2, NEXT))
                .isInstanceOf(ContractException.class).extracting("code").isEqualTo("BINDING_MISMATCH");
        var stale = new PluginBinding(1, "sample-plugin", f.owner, "cd".repeat(32), NOW);
        var changed = CommunityJson.parse(CommunityJson.Kind.BINDING, CommunityJson.encode(stale));
        assertThatThrownBy(() -> VersionStatus.apply(context, changed, f.publisher, f.state, f.current, 2, NEXT))
                .isInstanceOf(ContractException.class).extracting("code").isEqualTo("BASELINE_CHANGED");
        var wrong = proof(doc, "activeKey", "current:key", KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertThatThrownBy(() -> f.apply(wrong, true)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("INVALID_SIGNATURE");
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) doc.value();
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("proofs")).remove("activeKey");
        var recovery = CommunityJson.parse(doc.kind(), CommunityJson.encode(tree));
        assertThatThrownBy(() -> f.apply(recovery, false)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("RECOVERY_REVIEW_REQUIRED");
        assertThat(f.apply(recovery, true).state().state()).isEqualTo(VersionState.State.YANKED);
        f.apply(VersionStatusRequest.Action.REVOKE, null, "unsafe");
        assertThatThrownBy(() -> f.apply(VersionStatusRequest.Action.UNYANK, f.state.decisionSha256(), "cannot restore"))
                .isInstanceOf(ContractException.class).extracting("code").isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test @DisplayName("撤销快照拒绝回退、决定缺失和无法无损表达的限制组合")
    void preservesPriorGenerationOnInvalidProjection() throws Exception {
        var f = new Fixture();
        var doc = f.request(VersionStatusRequest.Action.YANK, null, "withdraw");
        assertThatThrownBy(() -> VersionStatus.apply(f.context(doc, false), f.binding, f.publisher, f.state, f.current, 1, NEXT))
                .isInstanceOf(ContractException.class).extracting("code").isEqualTo("REVOCATION_REJECTED");
        var restriction = f.independent("REVOKED");
        f.current = VersionRevocations.generate("sample.repo", 1, NOW, NEXT, List.of(restriction));
        var before = f.current;
        f.evidence.clear();
        assertThatThrownBy(() -> f.apply(doc, false)).isInstanceOf(ContractException.class)
                .extracting("code").isEqualTo("REVIEW_MISMATCH");
        assertThat(f.current).isSameAs(before);
        var entry = restriction.entry();
        var future = new VersionRevocations.Restriction(restriction.decisionRef(), true,
                new VersionRevocations.Entry(entry.scope(), entry.pluginId(), entry.version(), entry.packageSha256(),
                        null, null, "REVOKED", "CRITICAL_VULNERABILITY", NEXT));
        var yank = new VersionRevocations.Restriction(Reference.of("other.json", new byte[]{1}), false,
                new VersionRevocations.Entry(entry.scope(), entry.pluginId(), entry.version(), entry.packageSha256(),
                        null, null, "YANKED", "FUNCTIONAL_DEFECT", NOW));
        assertThatThrownBy(() -> VersionRevocations.generate("sample.repo", 2, NOW, NEXT, List.of(yank, future)))
                .isInstanceOf(ContractException.class).extracting("code").isEqualTo("REVOCATION_REJECTED");
        assertThat(VersionRevocations.generate("sample.repo", 2, NEXT, "2025-02-05T04:05:06Z", List.of(yank, future))
                .document().entries()).containsExactly(future.entry());
        byte[] bytes = before.archive().bytes();
        byte[] boundary = java.util.Arrays.copyOf(bytes, VersionRevocations.MAX_BYTES);
        java.util.Arrays.fill(boundary, bytes.length, boundary.length, (byte) ' ');
        assertThat(VersionRevocations.read(OperationContext.archive(boundary), before.restrictions()).archive().bytes()).isEqualTo(boundary);
        assertThatThrownBy(() -> VersionRevocations.read(OperationContext.archive(java.util.Arrays.copyOf(boundary, boundary.length + 1)),
                before.restrictions())).isInstanceOf(ContractException.class).extracting("code").isEqualTo("LIMIT_EXCEEDED");
    }

    private static final class Fixture {
        final KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Owner owner = new Owner("202", "User", "successor");
        final CommunityJson.Document publisher = publisher("202", "successor", "current:key", key).document();
        final CommunityJson.Document binding = CommunityJson.parse(CommunityJson.Kind.BINDING,
                CommunityJson.encode(new PluginBinding(1, "sample-plugin", owner, HASH, NOW)));
        final Map<String, Evidence> evidence = new HashMap<>();
        VersionState state = new VersionState("sample-plugin", "1.0.0", HASH, VersionState.State.ACTIVE, null);
        VersionRevocations current = VersionRevocations.generate("sample.repo", 1, NOW, NEXT, List.of());
        Fixture() throws Exception { }

        CommunityJson.Document request(VersionStatusRequest.Action action, String yank, String explanation) throws Exception {
            String reason = switch (action) { case YANK -> "FUNCTIONAL_DEFECT"; case UNYANK -> "ISSUE_RESOLVED"; case REVOKE -> "CRITICAL_VULNERABILITY"; };
            var data = new VersionStatusRequest(1, new VersionStatusRequest.Payload(owner, owner.account(), binding.sha256(),
                    "sample-plugin", "1.0.0", HASH, action, reason, explanation, yank), HASH,
                    new VersionStatusRequest.Proofs(PLACEHOLDER));
            return proof(OperationTestInputs.request(CommunityJson.Kind.STATUS_REQUEST, data), "activeKey", "current:key", key);
        }
        OperationContext context(CommunityJson.Document doc, boolean recovery) {
            var c = OperationTestInputs.context(doc, "version-status-requests/202/sample-plugin/1.0.0/"
                    + doc.value().get("requestId").textValue() + ".json", "202", recovery);
            var records = new HashMap<>(evidence); records.putAll(c.evidence());
            return new OperationContext(c.request(), c.authority(), c.recoveryEvidence(), c.appliedAt(), records, Map.of());
        }
        VersionStatus.Outcome apply(VersionStatusRequest.Action action, String yank, String explanation) throws Exception {
            return apply(request(action, yank, explanation), false);
        }
        VersionStatus.Outcome apply(CommunityJson.Document doc, boolean recovery) {
            var result = VersionStatus.apply(context(doc, recovery), binding, publisher, state, current, current.document().sequence() + 1, NEXT);
            state = result.state(); current = result.revocations(); evidence.putAll(result.operation().result().evidence());
            return result;
        }
        VersionRevocations.Restriction independent(String action) {
            var decision = OperationContext.archive(CommunityJson.encode(Map.of("independentAction", action)));
            evidence.put(decision.reference().path(), decision);
            return new VersionRevocations.Restriction(decision.reference(), true,
                    new VersionRevocations.Entry("PACKAGE_SHA256", "sample-plugin", "1.0.0", HASH,
                            null, null, action, "OTHER", NOW));
        }
    }
}
