package top.sywyar.pixivdownload.sdk.community.review;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.List;
import java.util.Objects;

/** 受保护表单裁决；原始报告与已生成裁决均不在归约时改写。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewDecision(int schemaVersion, Action action, String reason, String githubRepositoryId,
                             String repositoryId, long prNumber, String headRepositoryId, String headSha, String baseSha,
                             String submissionSha256, String sourceCommit, String packageSha256,
                             String actorAccountId, String actorLoginSnapshot, String prAuthorAccountId,
                             String triggeringActorAccountId, String workflowPath, String workflowSha,
                             String runId, long runAttempt, String decisionAt, String createdAt,
                             String scanRunId, Long scanRunAttempt, String scannerVersion, String rulesSha256,
                             Reference reportRef, List<String> findingIds, String targetDecisionSha256,
                             String reviewMode, Boolean selfReview) {
    public enum Action { FALSE_POSITIVE, MANUAL_SCAN_ACCEPTED, SELF_REVIEW_APPROVED, REVOKE_DECISION }
    public ReviewDecision { findingIds = findingIds == null ? null : List.copyOf(findingIds); }
    public record Version(String submissionSha256, String sourceCommit, String packageSha256) { }

    /** 平台适配器独立核对的运行事实，不接受裁决文件自报这些字段后自行认证。 */
    public record Execution(CommunityPr pr, String repositoryId, Version version, String workflowPath, String workflowSha,
                            String runId, long runAttempt, Account originalActor, Account triggeringActor, String decisionAt) { }
    public record Input(CommunityJson.Document document, Evidence archive, Execution execution) { }

    public static ReviewDecision verifySource(Input input, ReviewPolicy policy) {
        if (input.document.kind() != CommunityJson.Kind.DECISION) throw new ContractException("SCHEMA_INVALID", "");
        input.archive.reference().verify(input.document.bytes());
        var value = input.document.as(ReviewDecision.class);
        var source = input.execution;
        source.pr.validate();
        if (!policy.decisionWorkflowPath().equals(value.workflowPath) || !policy.decisionWorkflowShas().contains(value.workflowSha)
                || !value.workflowPath.equals(source.workflowPath) || !value.workflowSha.equals(source.workflowSha)
                || !value.runId.equals(source.runId) || value.runAttempt != source.runAttempt
                || !value.decisionAt.equals(source.decisionAt) || !value.repositoryId.equals(source.repositoryId)
                || !value.baseSha.equals(source.pr.baseSha()) || !value.targets(source.pr, source.version)
                || !"User".equals(source.originalActor.type()) || !"User".equals(source.triggeringActor.type())
                || !value.actorAccountId.equals(source.originalActor.id())
                || !value.triggeringActorAccountId.equals(source.triggeringActor.id()) || value.reason.isBlank()) {
            throw new ContractException("REVIEW_MISMATCH", "/decision/source");
        }
        return value;
    }

    /** base 的更新由输入校验重新核对 binding 与策略，不使相同源码和 head 自动失去审核。 */
    public boolean targets(CommunityPr pr, Version version) {
        return githubRepositoryId.equals(pr.githubRepositoryId()) && prNumber == pr.number()
                && headRepositoryId.equals(pr.headRepositoryId()) && headSha.equals(pr.headSha())
                && prAuthorAccountId.equals(pr.authorAccountId()) && Objects.equals(version(), version);
    }

    public Version version() { return submissionSha256 == null ? null : new Version(submissionSha256, sourceCommit, packageSha256); }

    public boolean authorized(ReviewPolicy policy) {
        return policy.reviewerAccountIds().contains(actorAccountId) && policy.reviewerAccountIds().contains(triggeringActorAccountId);
    }

    public boolean matchesScan(RiskReport report, Reference reference) {
        return Objects.equals(scanRunId, report.runId()) && Objects.equals(scanRunAttempt, report.runAttempt())
                && Objects.equals(scannerVersion, report.scannerVersion()) && Objects.equals(rulesSha256, report.rulesSha256())
                && Objects.equals(reportRef, reference);
    }
}
