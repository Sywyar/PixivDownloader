package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.plugin.signature.community.CommunityOperation;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.PluginBinding;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 当前管理者的版本处置；历史发布者归属与包签名不参与当前授权。 */
public final class VersionStatus {
    private VersionStatus() { }

    /** 重跑保留当前快照，只返回已存档的原操作结果，不把旧快照重新发布。 */
    public record Outcome(OperationResult.Outcome operation, VersionState state, VersionRevocations revocations) { }

    public static Outcome apply(OperationContext context, CommunityJson.Document currentBinding,
                                 CommunityJson.Document currentPublisher, VersionState state,
                                 VersionRevocations current, long sequence, String nextUpdate) {
        var document = context.document(CommunityJson.Kind.STATUS_REQUEST);
        var request = VersionStatusRequest.read(document, context.request().reference().path());
        var replay = context.replay(document);
        if (replay != null) return new Outcome(replay, state, current);
        var p = request.payload();
        OperationChecks.baseline(p.pluginBindingSha256(), currentBinding, "/payload/pluginBindingSha256");
        var binding = PluginBinding.read(currentBinding, "plugin-bindings/" + p.pluginId() + ".json");
        binding.requireOwner(p.owner());
        var publisher = Publisher.read(currentPublisher);
        if (!publisher.owner().equals(binding.owner()) || !p.requester().equals(context.authority().actualAuthor())) {
            throw new ContractException("BINDING_MISMATCH", "/payload/requester");
        }
        var authority = context.authority();
        authority.requireApproval(request.requestId(), request.proofs().activeKey() == null);
        authority.requireRepresentative(binding.owner(), authority.actualAuthor().id());
        if (request.proofs().activeKey() != null) OperationChecks.proof(document, CommunityOperation.VERSION_STATUS_REQUEST,
                request.proofs().activeKey(), publisher.activeKey().trusted(publisher.displayName()), "/proofs/activeKey");
        var managed = current.managed(state);
        var decision = authority.approval().evidence();
        var next = state.transition(request, decision.reference().sha256());
        if (sequence <= current.document().sequence()) throw new ContractException("REVOCATION_REJECTED", "/sequence");
        var restrictions = new ArrayList<>(current.restrictions());
        if (managed != null) restrictions.remove(managed);
        if (next.state() != VersionState.State.ACTIVE) {
            restrictions.add(new VersionRevocations.Restriction(decision.reference(), false,
                    new VersionRevocations.Entry("PACKAGE_SHA256", p.pluginId(), p.version(), p.packageSha256(),
                            null, null, next.state().name(), p.reasonCode(), context.appliedAt())));
        }
        var updated = VersionRevocations.generate(current.document().repositoryId(), sequence, context.appliedAt(),
                nextUpdate, restrictions);
        var related = new ArrayList<CommunityValues.Evidence>();
        related.add(OperationContext.archive(currentBinding.bytes()));
        related.add(OperationContext.archive(currentPublisher.bytes()));
        for (var restriction : current.restrictions()) {
            related.add(CommunityValues.requireEvidence(restriction.decisionRef(), context.evidence()));
        }
        var operation = context.finish(document, current.archive(), updated.archive(), Map.of("revocations.json", updated.archive()),
                related, List.of(authority.proposalPr()), sequence);
        return new Outcome(operation, next, updated);
    }
}
