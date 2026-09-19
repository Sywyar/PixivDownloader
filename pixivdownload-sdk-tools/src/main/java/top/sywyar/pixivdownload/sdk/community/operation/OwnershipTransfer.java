package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityOperation;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.PluginBinding;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** 转移只替换当前 binding；新目标才注册 publisher，不修改原发布者或任何历史版本。 */
public final class OwnershipTransfer {
    private OwnershipTransfer() { }

    /** targetLoginAtRegistration 来自平台账号快照，仅在目标首次注册时使用。 */
    public static OperationResult.Outcome apply(OperationContext context, CommunityJson.Document currentBinding,
                                                CommunityJson.Document targetPublisher, String targetLoginAtRegistration,
                                                List<TransferApproval.Input> approvals) {
        return apply(context, currentBinding, targetPublisher, targetLoginAtRegistration, approvals, null);
    }

    /** 自动转移另读原发布者当前活动密钥；原生确认与双方请求签名必须同时有效。 */
    public static OperationResult.Outcome apply(OperationContext context, CommunityJson.Document currentBinding,
                                                CommunityJson.Document targetPublisher, String targetLoginAtRegistration,
                                                List<TransferApproval.Input> approvals, CommunityJson.Document sourcePublisher) {
        var document = context.document(CommunityJson.Kind.TRANSFER);
        var request = OwnershipTransferRequest.read(document, context.request().reference().path());
        var replay = context.replay(document);
        if (replay != null) return replay;
        var p = request.payload();
        String bindingPath = "plugin-bindings/" + p.pluginId() + ".json";
        OperationChecks.baseline(p.pluginBindingSha256(), currentBinding, "/payload/pluginBindingSha256");
        var binding = PluginBinding.read(currentBinding, bindingPath);
        binding.requireOwner(p.from());
        var authority = context.authority();
        boolean recovery = p.mode() == OwnershipTransferRequest.Mode.RECOVERY;
        authority.requireAuthorization(document, recovery);
        if (!authority.represents(p.from(), authority.actualAuthor().id())
                && !authority.represents(p.to(), authority.actualAuthor().id())) {
            throw new ContractException("BINDING_MISMATCH", "/proposalPr/authorAccountId");
        }
        if (!Objects.equals(context.recoveryEvidence(), p.recoveryEvidence())) {
            throw new ContractException("REVIEW_MISMATCH", "/recoveryEvidence");
        }
        TransferApproval.requireApprovals(approvals, request, authority);
        if (authority.signedStatus() != null && approvals.stream().anyMatch(input -> !input.pr().equals(authority.proposalPr()))) {
            throw new ContractException("REVIEW_MISMATCH", "/approvals/pr");
        }
        var signedApprovals = approvals.stream().filter(input -> input.ownerProof() != null).toList();
        if (authority.signedStatus() != null && signedApprovals.isEmpty()) {
            throw new ContractException("PROOF_REQUIRED", "/approvals/ownerProof");
        }
        if (!signedApprovals.isEmpty()) {
            if (sourcePublisher == null) throw new ContractException("UNKNOWN_KEY", "/sourcePublisher");
            var source = Publisher.read(sourcePublisher);
            if (!source.owner().equals(p.from())) throw new ContractException("BINDING_MISMATCH", "/sourcePublisher");
            for (var input : signedApprovals) {
                var proof = CommunityJson.decode("signature", input.ownerProof().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        CommunityJson.Kind.APPROVAL.maximumBytes(), SignatureMetadata.class);
                OperationChecks.proof(document, CommunityOperation.OWNERSHIP_TRANSFER, proof,
                        source.activeKey().trusted(source.displayName()), "/approvals/ownerProof");
            }
        }
        boolean register = p.targetPublisherRecordSha256() == null;
        Publisher target;
        if (register) {
            if (targetPublisher != null) throw new ContractException("BASELINE_CHANGED", "/payload/targetPublisherRecordSha256");
            var key = p.targetKey();
            targetPublisher = new Publisher(1, p.to().publisherId(), p.targetPublisherDisplayName(),
                    new Publisher.GithubAccount(p.to().accountId(), p.to().accountType(), targetLoginAtRegistration),
                    List.of(new Publisher.SigningKey(key.keyId(), key.algorithm(), key.publicKeySpkiBase64(), TrustedPluginKey.State.ACTIVE))).document();
            target = Publisher.read(targetPublisher);
        } else {
            OperationChecks.baseline(p.targetPublisherRecordSha256(), targetPublisher, "/payload/targetPublisherRecordSha256");
            target = Publisher.read(targetPublisher);
            if (!target.owner().equals(p.to()) || !target.activeKey().keyId().equals(p.targetKey().keyId())) {
                throw new ContractException("BINDING_MISMATCH", "/payload/targetKey");
            }
        }
        OperationChecks.proof(document, CommunityOperation.OWNERSHIP_TRANSFER, request.proofs().targetKey(),
                target.activeKey().trusted(target.displayName()), "/proofs/targetKey");
        var updated = CommunityJson.parse(CommunityJson.Kind.BINDING, CommunityJson.encode(
                new PluginBinding(1, binding.pluginId(), target.owner(), request.requestId(), context.appliedAt())));
        var before = OperationContext.archive(currentBinding.bytes());
        var after = OperationContext.archive(updated.bytes());
        var frozenTarget = OperationContext.archive(targetPublisher.bytes());
        var outputs = new LinkedHashMap<String, Evidence>(); outputs.put(bindingPath, after);
        if (register) outputs.put(target.path(), frozenTarget);
        var records = new ArrayList<Evidence>(); records.add(frozenTarget);
        if (!signedApprovals.isEmpty()) records.add(OperationContext.archive(sourcePublisher.bytes()));
        var prs = new LinkedHashMap<String, CommunityPr>(); putPr(prs, authority.proposalPr());
        for (var approval : approvals) {
            // Review 派生的角色说明只归档为证据，不能伪装成申请者提交的批准文件。
            records.add(approval.review() != null ? OperationContext.archive(approval.document().bytes())
                    : new Evidence(top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference.of(
                    approval.path(), approval.document().bytes()), approval.document().bytes()));
            if (approval.review() != null) records.add(approval.review().evidence());
            putPr(prs, approval.pr());
        }
        return context.finish(document, before, after, outputs, records, List.copyOf(prs.values()), null);
    }

    private static void putPr(LinkedHashMap<String, CommunityPr> prs, CommunityPr pr) {
        String key = pr.githubRepositoryId() + "/" + pr.number();
        var previous = prs.putIfAbsent(key, pr);
        if (previous != null && !previous.equals(pr)) throw new ContractException("REVIEW_MISMATCH", "/prEvidence");
    }
}
