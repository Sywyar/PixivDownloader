package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.List;
import java.util.Set;

/**
 * 操作归约需要的外部审核事实。调用方必须从受保护策略、原生 PR 和审核证据构造，
 * 不能把请求内的账号、路径或布尔值当成这些事实；这里仅检查彼此关联。
 */
public record OperationAuthority(CommunityPr proposalPr, Account actualAuthor, List<Representation> representations,
                                 Approval approval, Set<String> authorizedReviewers, SignedStatus signedStatus) {
    public OperationAuthority(CommunityPr proposalPr, Account actualAuthor, List<Representation> representations,
                              Approval approval, Set<String> authorizedReviewers) {
        this(proposalPr, actualAuthor, representations, approval, authorizedReviewers, null);
    }
    public OperationAuthority {
        representations = List.copyOf(representations);
        authorizedReviewers = Set.copyOf(authorizedReviewers);
    }
    public record Representation(Owner subject, String personAccountId, Evidence evidence) { }
    /** 来自受保护执行器的签名处置授权；实际活动密钥证明仍由 VersionStatus 验证。 */
    public record SignedStatus(String requestId, String headSha, Evidence evidence) { }
    public record Approval(String requestId, String headSha, Set<String> reviewerAccountIds,
                           boolean recoveryApproved, Evidence evidence) {
        public Approval { reviewerAccountIds = Set.copyOf(reviewerAccountIds); }
    }

    public void requireApproval(String requestId, boolean recovery) {
        proposalPr.requireHuman(actualAuthor);
        if (approval == null || approval.reviewerAccountIds.isEmpty()
                || !authorizedReviewers.containsAll(approval.reviewerAccountIds)) {
            throw new ContractException("APPROVAL_REQUIRED", "/approval");
        }
        if (!requestId.equals(approval.requestId) || !proposalPr.headSha().equals(approval.headSha)) {
            throw new ContractException("REVIEW_MISMATCH", "/approval");
        }
        if (approval.evidence == null) throw new ContractException("APPROVAL_REQUIRED", "/approval/evidence");
        if (recovery && !approval.recoveryApproved) throw new ContractException("RECOVERY_REVIEW_REQUIRED", "/approval");
    }

    public void requireAuthorization(CommunityJson.Kind kind, String requestId, boolean recovery) {
        if (signedStatus == null) { requireApproval(requestId, recovery); return; }
        proposalPr.requireHuman(actualAuthor);
        if (kind != CommunityJson.Kind.STATUS_REQUEST || approval != null || recovery) {
            throw new ContractException("APPROVAL_REQUIRED", "/authorization");
        }
        if (!requestId.equals(signedStatus.requestId) || !proposalPr.headSha().equals(signedStatus.headSha)
                || signedStatus.evidence == null) throw new ContractException("REVIEW_MISMATCH", "/authorization");
    }

    public Evidence decisionEvidence() { return signedStatus == null ? approval.evidence : signedStatus.evidence; }
    public List<String> reviewerIds() {
        return signedStatus == null ? approval.reviewerAccountIds.stream().sorted().toList() : List.of();
    }

    public boolean represents(Owner subject, String personAccountId) {
        if ("User".equals(subject.accountType())) return subject.accountId().equals(personAccountId);
        return "Organization".equals(subject.accountType()) && representations.stream().anyMatch(item ->
                item.subject.equals(subject) && item.personAccountId.equals(personAccountId) && item.evidence != null);
    }

    public void requireRepresentative(Owner subject, String personAccountId) {
        if (!represents(subject, personAccountId)) throw new ContractException("BINDING_MISMATCH", "/actorAccountId");
    }
}
