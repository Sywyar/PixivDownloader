package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.review.HumanReviews;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 正文只表达角色；身份来自文件所属 PR 或原申请上的原生 Review。 */
public record TransferApproval(int schemaVersion, String requestId, Role role) {
    public enum Role { FROM, TO }
    public record Input(CommunityJson.Document document, String path, CommunityPr pr, Account actualAuthor,
                        boolean newlyAdded, HumanReviews.NativeReview review, String ownerProof) {
        public Input(CommunityJson.Document document, String path, CommunityPr pr, Account actualAuthor, boolean newlyAdded) {
            this(document, path, pr, actualAuthor, newlyAdded, null, null);
        }
        public Input(CommunityJson.Document document, String path, CommunityPr pr, Account actualAuthor,
                     boolean newlyAdded, HumanReviews.NativeReview review) {
            this(document, path, pr, actualAuthor, newlyAdded, review, null);
        }
    }

    public static TransferApproval read(Input input, OwnershipTransferRequest request, OperationAuthority authority) {
        if (input.document.kind() != CommunityJson.Kind.APPROVAL) throw new ContractException("SCHEMA_INVALID", "");
        var value = input.document.as(TransferApproval.class);
        if (!value.requestId.equals(request.requestId())) throw new ContractException("REQUEST_ID_MISMATCH", "/requestId");
        if (input.review == null) {
            if (input.ownerProof != null) throw new ContractException("REVIEW_MISMATCH", "/approvals/ownerProof");
            input.pr.requireHuman(input.actualAuthor);
        }
        else {
            input.pr.validate();
            var review = input.review;
            // 原生来源及最新决定由平台重读；合同不接受把申请作者改写成批准人。
            if (value.role != Role.FROM || !input.pr.equals(authority.proposalPr())
                    || !"User".equals(review.reviewer().type()) || !review.reviewer().equals(input.actualAuthor)
                    || !input.pr.githubRepositoryId().equals(review.githubRepositoryId()) || input.pr.number() != review.prNumber()
                    || !input.pr.headSha().equals(review.headSha()) || review.state() != HumanReviews.NativeState.APPROVED
                    || review.evidence() == null || review.dismissal() != null) {
                throw new ContractException("REVIEW_MISMATCH", "/approvals/review");
            }
            CommunityJson.validateStructure("id", CommunityJson.strictTree(CommunityJson.encode(review.id()), 1024));
            CommunityJson.validateStructure("externalTime", CommunityJson.strictTree(CommunityJson.encode(review.submittedAt()), 1024));
        }
        if (!input.newlyAdded) throw new ContractException("APPROVAL_REQUIRED", "/approval");
        String rolePath = value.role == Role.FROM ? "from" : "to";
        OperationChecks.path(input.path, "ownership-transfers/" + request.payload().pluginId() + "/" + request.requestId()
                + "/approvals/" + rolePath + "/" + input.actualAuthor.id() + ".json");
        if (!input.pr.githubRepositoryId().equals(authority.proposalPr().githubRepositoryId())) {
            throw new ContractException("BINDING_MISMATCH", "/pr/githubRepositoryId");
        }
        authority.requireRepresentative(value.role == Role.FROM ? request.payload().from() : request.payload().to(),
                input.actualAuthor.id());
        return value;
    }

    /** 跨账号要求不同自然人；原所有者可以在接收方的同一申请 PR 上确认。 */
    public static void requireApprovals(List<Input> inputs, OwnershipTransferRequest request, OperationAuthority authority) {
        var from = new ArrayList<Input>();
        var to = new ArrayList<Input>();
        var paths = new HashSet<String>();
        for (var input : inputs) {
            if (!paths.add(input.path)) throw ContractException.invalid("DUPLICATE_KEY", "/approvals");
            var approval = read(input, request, authority);
            (approval.role == Role.FROM ? from : to).add(input);
        }
        if (to.isEmpty()) throw new ContractException("APPROVAL_REQUIRED", "/approvals/to");
        if (request.payload().mode() == OwnershipTransferRequest.Mode.RECOVERY) {
            authority.requireApproval(request.requestId(), true);
            return;
        }
        if (from.isEmpty()) throw new ContractException("APPROVAL_REQUIRED", "/approvals/from");
        if (request.payload().from().accountId().equals(request.payload().to().accountId())) return;
        if (from.stream().noneMatch(left -> to.stream().anyMatch(right ->
                !left.actualAuthor.id().equals(right.actualAuthor.id())
                        && (left.pr.number() != right.pr.number() || left.review != null)))) {
            throw new ContractException("APPROVAL_REQUIRED", "/approvals");
        }
    }
}
