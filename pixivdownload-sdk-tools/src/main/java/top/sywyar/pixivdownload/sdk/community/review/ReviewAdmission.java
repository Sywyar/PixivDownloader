package top.sywyar.pixivdownload.sdk.community.review;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginRiskDeclaration;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 归约已取得的平台事实，输出门禁与标签投影；不发布检查、评论或文件。 */
public final class ReviewAdmission {
    private ReviewAdmission() { }
    public enum Conclusion { SUCCESS, FAILURE, PENDING, SKIPPED, NEUTRAL, CANCELLED }
    public enum PrState { OPEN, CLOSED, MERGED }
    public enum ApplyState { WAITING, FAILED, APPLIED }
    public enum Flow { NONE, READY, CLOSED, AWAITING_APPLY, APPLY_FAILED, COMPLETED }
    public record Scan(String runId, long runAttempt, String scannerVersion, String rulesSha256, Conclusion conclusion) { }
    public record Apply(ApplyState state, String expectedGenerationSha256, String readbackGenerationSha256) { }
    /** version 为空表示已由输入校验确认的非版本操作，此时扫描明确不适用。 */
    public record Snapshot(String repositoryId, CommunityPr pr, ReviewDecision.Version version, String inputSha256,
                            String bindingSha256, String policySha256, PrState state, boolean draft, Scan scan, Apply apply) { }
    /** 发布来源与这些绑定均须来自原生检查元数据，不能按显示名称猜测来源。 */
    public record Validation(Conclusion conclusion, String publisherId, String headSha, String baseSha, String inputSha256,
                              String bindingSha256, String policySha256) {
        boolean matches(Snapshot snapshot, String expectedPublisherId) {
            return conclusion == Conclusion.SUCCESS && Objects.equals(publisherId, expectedPublisherId)
                    && Objects.equals(headSha, snapshot.pr.headSha()) && Objects.equals(baseSha, snapshot.pr.baseSha())
                    && Objects.equals(inputSha256, snapshot.inputSha256)
                    && Objects.equals(bindingSha256, snapshot.bindingSha256) && Objects.equals(policySha256, snapshot.policySha256);
        }
    }
    public record Result(Snapshot snapshot, Reference reportRef, List<Reference> decisionRefs,
                         boolean validationPassed, boolean riskPassed, HumanReviews.Result human,
                         boolean scanIncomplete, boolean manualScanAccepted, List<String> blockingFindingIds,
                         Flow flow, Set<String> labels) {
        public Result {
            decisionRefs = List.copyOf(decisionRefs);
            blockingFindingIds = List.copyOf(blockingFindingIds); labels = Set.copyOf(labels);
        }
    }

    public static Result evaluate(Snapshot before, Snapshot after, Validation validation, String expectedPublisherId,
                                  ReviewPolicy policy, List<HumanReviews.NativeReview> reviews,
                                  List<ReviewDecision.Input> decisionInputs, Evidence reportEvidence,
                                  int maximumReportBytes, PluginRiskDeclaration declaration, Map<String, Evidence> evidence) {
        if (!before.equals(after)) throw new ContractException("REVIEW_MISMATCH", "/current");
        Snapshot snapshot = after;
        snapshot.pr.validate();
        scalar("repositoryId", snapshot.repositoryId);
        scalar("sha256", snapshot.inputSha256);
        scalar("sha256", snapshot.bindingSha256);
        scalar("sha256", snapshot.policySha256);
        scalar("id", expectedPublisherId);
        if (snapshot.state == null || validation.conclusion == null) throw new ContractException("REVIEW_MISMATCH", "/current");
        if (snapshot.version != null) {
            scalar("sha256", snapshot.version.submissionSha256());
            scalar("commit", snapshot.version.sourceCommit());
            scalar("sha256", snapshot.version.packageSha256());
        }
        boolean validated = validation.matches(snapshot, expectedPublisherId);
        RiskReport report = null;
        if (snapshot.version != null) {
            if (reportEvidence == null || snapshot.scan == null) throw new ContractException("REVIEW_MISMATCH", "/riskScan/reportRef");
            report = RiskReport.read(reportEvidence, maximumReportBytes);
            Scan scan = snapshot.scan;
            if (!report.headSha().equals(snapshot.pr.headSha()) || !report.sourceCommit().equals(snapshot.version.sourceCommit())
                    || !report.packageSha256().equals(snapshot.version.packageSha256()) || !report.runId().equals(scan.runId)
                    || report.runAttempt() != scan.runAttempt || !report.scannerVersion().equals(scan.scannerVersion)
                    || !report.rulesSha256().equals(scan.rulesSha256)
                    || report.status() == RiskReport.Status.COMPLETE && scan.conclusion != Conclusion.SUCCESS) {
                throw new ContractException("REVIEW_MISMATCH", "/riskScan");
            }
            report.validate(declaration, evidence);
        } else if (snapshot.scan != null || reportEvidence != null) {
            throw new ContractException("REVIEW_MISMATCH", "/riskScan");
        }
        var decisions = ReviewDecisions.evaluate(snapshot.repositoryId, snapshot.pr, snapshot.version, report,
                reportEvidence == null ? null : reportEvidence.reference(), policy, decisionInputs);
        boolean incomplete = report != null && report.status() == RiskReport.Status.INCOMPLETE;
        List<String> blockers = report == null ? List.of() : report.findings().stream()
                .filter(f -> !decisions.falsePositives().contains(f.findingId())).map(RiskReport.Finding::findingId).sorted().toList();
        boolean riskPassed = blockers.isEmpty() && (!incomplete || decisions.manualScanAccepted());
        var human = HumanReviews.evaluate(snapshot.pr, policy, reviews, decisions);
        Flow flow = flow(snapshot, validated && riskPassed && human.passed());
        var labels = new HashSet<String>();
        labels.add(validation.conclusion == Conclusion.PENDING ? "ci:running" : validated && riskPassed ? "ci:passed" : "ci:blocked");
        labels.add(switch (human.status()) {
            case PENDING -> "review:pending";
            case APPROVED -> "review:approved";
            case SELF_APPROVED -> "review:self-approved";
            case CHANGES_REQUESTED -> "review:changes-requested";
        });
        if (incomplete) labels.add("scan:incomplete");
        if (decisions.manualScanAccepted()) labels.add("scan:manual-accepted");
        if (!decisions.falsePositives().isEmpty()) labels.add("scan:false-positive");
        switch (flow) {
            case READY -> labels.add("state:ready");
            case AWAITING_APPLY -> labels.add("state:awaiting-apply");
            case APPLY_FAILED -> labels.add("state:apply-failed");
            case COMPLETED -> labels.add("state:completed");
            case NONE, CLOSED -> { }
        }
        return new Result(snapshot, reportEvidence == null ? null : reportEvidence.reference(), decisions.references(),
                validated, riskPassed, human, incomplete, decisions.manualScanAccepted(), blockers, flow, labels);
    }

    private static Flow flow(Snapshot snapshot, boolean ready) {
        if (snapshot.state == PrState.CLOSED) return Flow.CLOSED;
        if (snapshot.state == PrState.OPEN) return !snapshot.draft && ready ? Flow.READY : Flow.NONE;
        if (snapshot.pr.mergeSha() == null) throw new ContractException("REVIEW_MISMATCH", "/pr/mergeSha");
        Apply apply = snapshot.apply;
        if (apply == null || apply.state == ApplyState.WAITING) return Flow.AWAITING_APPLY;
        if (apply.state != ApplyState.APPLIED) return Flow.APPLY_FAILED;
        if (apply.expectedGenerationSha256 == null || !apply.expectedGenerationSha256.equals(apply.readbackGenerationSha256)) {
            return Flow.APPLY_FAILED;
        }
        scalar("sha256", apply.expectedGenerationSha256);
        return Flow.COMPLETED;
    }

    private static void scalar(String definition, String value) {
        byte[] bytes = CommunityJson.encode(value);
        CommunityJson.validateStructure(definition, CommunityJson.strictTree(bytes, bytes.length));
    }
}
