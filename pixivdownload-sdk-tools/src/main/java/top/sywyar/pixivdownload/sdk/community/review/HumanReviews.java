package top.sywyar.pixivdownload.sdk.community.review;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** 原生 Review 的确定性归约；评论与线程状态不改变批准或拒绝。 */
public final class HumanReviews {
    private HumanReviews() { }
    public enum NativeState { APPROVED, CHANGES_REQUESTED, COMMENTED, DISMISSED }
    public enum Status { PENDING, APPROVED, SELF_APPROVED, CHANGES_REQUESTED }
    public record Dismissal(String actorAccountId, String reason, Evidence evidence) { }
    /** 平台适配器核对原生来源后提供；submittedAt 保留 API 原始时间精度。 */
    public record NativeReview(String id, String githubRepositoryId, long prNumber, Account reviewer,
                               String headSha, NativeState state, String submittedAt, Evidence evidence, Dismissal dismissal) { }
    public record Approval(String reviewMode, boolean selfReview, String authorAccountId, String reviewerAccountId,
                            String headSha, String approvedAt, Reference evidenceRef) {
        public void validate() {
            byte[] bytes = CommunityJson.encode(this);
            CommunityJson.validateStructure("humanReview", CommunityJson.strictTree(bytes, bytes.length));
            boolean same = authorAccountId.equals(reviewerAccountId);
            if (selfReview != same || selfReview != "SELF".equals(reviewMode)) {
                throw new ContractException("REVIEW_MISMATCH", "/humanReview");
            }
        }
    }
    public record Result(Status status, Approval approval, List<String> blockingReviewIds) {
        public Result { blockingReviewIds = List.copyOf(blockingReviewIds); }
        public boolean passed() { return status == Status.APPROVED || status == Status.SELF_APPROVED; }
    }

    public static Result evaluate(CommunityPr pr, ReviewPolicy policy, List<NativeReview> reviews, ReviewDecisions decisions) {
        pr.validate();
        var byReviewer = new HashMap<String, NativeReview>();
        var ids = new java.util.HashSet<String>();
        Comparator<NativeReview> order = Comparator.comparing((NativeReview r) -> Instant.parse(r.submittedAt))
                .thenComparing(r -> new BigInteger(r.id));
        for (var review : reviews) {
            validateScalar("id", review.id);
            if (!ids.add(review.id)) throw ContractException.invalid("DUPLICATE_KEY", "/reviews");
            if (!review.githubRepositoryId.equals(pr.githubRepositoryId()) || review.prNumber != pr.number()) {
                throw new ContractException("REVIEW_MISMATCH", "/reviews/pr");
            }
            if (!"User".equals(review.reviewer.type()) || !policy.reviewerAccountIds().contains(review.reviewer.id())) continue;
            validateScalar("externalTime", review.submittedAt);
            validateScalar("commit", review.headSha);
            if (review.state == null) throw new ContractException("REVIEW_MISMATCH", "/reviews/state");
            if (review.evidence == null) throw new ContractException("REVIEW_MISMATCH", "/reviews/evidence");
            if (review.state == NativeState.COMMENTED) continue;
            if (review.state == NativeState.DISMISSED) {
                var dismissal = review.dismissal;
                if (dismissal == null || dismissal.evidence == null || dismissal.reason == null || dismissal.reason.isBlank()
                        || !policy.dismissalAccountIds().contains(dismissal.actorAccountId)) {
                    throw new ContractException("REVIEW_MISMATCH", "/reviews/dismissal");
                }
                continue;
            }
            byReviewer.merge(review.reviewer.id(), review, (left, right) -> order.compare(left, right) < 0 ? right : left);
        }
        List<String> blockers = byReviewer.values().stream().filter(r -> r.state == NativeState.CHANGES_REQUESTED)
                .sorted(order).map(NativeReview::id).toList();
        if (!blockers.isEmpty()) return new Result(Status.CHANGES_REQUESTED, null, blockers);
        var peer = byReviewer.values().stream().filter(r -> r.state == NativeState.APPROVED
                && r.headSha.equals(pr.headSha()) && !r.reviewer.id().equals(pr.authorAccountId())).max(order);
        if (peer.isPresent()) {
            var review = peer.get();
            var approval = new Approval("PEER", false, pr.authorAccountId(), review.reviewer.id(), pr.headSha(),
                    review.submittedAt, review.evidence.reference());
            approval.validate();
            return new Result(Status.APPROVED, approval, List.of());
        }
        if (decisions.selfReview() != null) {
            var applied = decisions.selfReview();
            var approval = new Approval("SELF", true, pr.authorAccountId(), applied.decision().actorAccountId(), pr.headSha(),
                    applied.decision().decisionAt(), applied.reference());
            approval.validate();
            return new Result(Status.SELF_APPROVED, approval, List.of());
        }
        return new Result(Status.PENDING, null, List.of());
    }

    private static void validateScalar(String definition, String value) {
        byte[] bytes = CommunityJson.encode(value);
        CommunityJson.validateStructure(definition, CommunityJson.strictTree(bytes, bytes.length));
    }
}
