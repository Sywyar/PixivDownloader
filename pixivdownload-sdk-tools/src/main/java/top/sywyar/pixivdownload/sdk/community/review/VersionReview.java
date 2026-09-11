package top.sywyar.pixivdownload.sdk.community.review;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.submission.DescriptorSnapshot;
import top.sywyar.pixivdownload.sdk.community.submission.VersionSubmission;

import java.util.List;
import java.util.Map;

/** 已审版本的不可变记录；先归档证据，再形成 review，社区签名不写回本记录。 */
public record VersionReview(int schemaVersion, Owner owner, String ownerLoginSnapshot, String pluginId, String version,
                            long packageSize, String packageSha256, Reference submissionRef,
                            VersionSubmission.Source source, VersionSubmission.BuildProfile buildProfile,
                            DescriptorSnapshot descriptor, Scan riskScan, Reference sourceDiffRef, Reference sbomRef,
                            Reference dependencyReportRef, Reference licenseReportRef, Reference rebuildProofRef,
                            CommunityPr pr, HumanReviews.Approval humanReview, Reference publicationApprovalRef,
                            String reviewedAt, String assuranceLevel) {
    public record Scan(RiskReport.Status status, String scannerVersion, String rulesSha256, String runId, long runAttempt,
                       Reference reportRef, List<Reference> decisionRefs, String gate) {
        public Scan { decisionRefs = List.copyOf(decisionRefs); }
    }
    /** 身份、最终包描述符、平台批准与重建事实均由各自的实际执行边界提供。 */
    public record Facts(Owner owner, DescriptorSnapshot descriptor, CommunityPr pr, ReviewAdmission.Result admission,
                        RebuildProof rebuildProof, Reference publicationApprovalRef) { }

    public static VersionReview read(CommunityJson.Document document) {
        if (document.kind() != CommunityJson.Kind.REVIEW) throw new ContractException("SCHEMA_INVALID", "");
        var value = document.as(VersionReview.class);
        CommunityValues.version(value.version, "/version");
        value.source.validate(); value.buildProfile.validate(); value.descriptor.validate(); value.pr.validate(); value.humanReview.validate();
        return value;
    }

    /** 只核对原始存档和已验证事实，不把 JSON 中的 PASS 当成平台批准。 */
    public void verify(CommunityJson.Document submissionDocument, Facts actual, Map<String, Evidence> evidence,
                       int maximumReportBytes, int maximumRebuildBytes) {
        CommunityValues.requireEvidence(submissionRef, evidence).reference().verify(submissionDocument.bytes());
        VersionSubmission submission = VersionSubmission.read(submissionDocument);
        boolean approved = actual.admission.validationPassed() && actual.admission.riskPassed() && actual.admission.human().passed();
        var snapshot = actual.admission.snapshot();
        if (!approved || !"SOURCE_REVIEWED".equals(assuranceLevel) || !"PASS".equals(riskScan.gate)
                || snapshot.draft() || snapshot.state() == ReviewAdmission.PrState.CLOSED
                || !snapshot.pr().equals(pr) || !new ReviewDecision.Version(submissionDocument.sha256(), source.commit(), packageSha256).equals(snapshot.version())
                || !riskScan.reportRef.equals(actual.admission.reportRef())
                || !owner.equals(actual.owner) || !owner.publisherId().equals(submission.publisherId())
                || !pluginId.equals(submission.pluginId()) || !version.equals(submission.version())
                || packageSize != submission.artifact().expectedSize() || !packageSha256.equals(submission.artifact().sha256())
                || !source.equals(submission.source()) || !buildProfile.equals(submission.buildProfile())
                || !descriptor.equals(actual.descriptor) || !pr.equals(actual.pr)
                || !humanReview.equals(actual.admission.human().approval()) || !humanReview.headSha().equals(pr.headSha())
                || !humanReview.authorAccountId().equals(pr.authorAccountId())
                || !publicationApprovalRef.equals(actual.publicationApprovalRef)
                || !riskScan.decisionRefs.equals(actual.admission.decisionRefs())) {
            throw new ContractException("REVIEW_MISMATCH", "/review");
        }
        for (var ref : List.of(sourceDiffRef, sbomRef, dependencyReportRef, licenseReportRef, humanReview.evidenceRef(), publicationApprovalRef)) {
            CommunityValues.requireEvidence(ref, evidence);
        }
        CommunityValues.unique(riskScan.decisionRefs, Reference::path, "/riskScan/decisionRefs");
        riskScan.decisionRefs.forEach(ref -> CommunityValues.requireEvidence(ref, evidence));
        var report = RiskReport.read(CommunityValues.requireEvidence(riskScan.reportRef, evidence), maximumReportBytes);
        if (riskScan.status != report.status() || !riskScan.scannerVersion.equals(report.scannerVersion())
                || !riskScan.rulesSha256.equals(report.rulesSha256()) || !riskScan.runId.equals(report.runId())
                || riskScan.runAttempt != report.runAttempt() || !report.headSha().equals(pr.headSha())
                || !report.sourceCommit().equals(source.commit()) || !report.packageSha256().equals(packageSha256)
                || actual.admission.scanIncomplete() != (report.status() == RiskReport.Status.INCOMPLETE)) {
            throw new ContractException("REVIEW_MISMATCH", "/riskScan");
        }
        report.validate(descriptor.riskDeclaration(), evidence);
        var proof = RebuildProof.read(CommunityValues.requireEvidence(rebuildProofRef, evidence), maximumRebuildBytes);
        proof.requireMatches(actual.rebuildProof, evidence);
        if (!proof.sourceCommit().equals(source.commit()) || !proof.packageSha256().equals(packageSha256)
                || proof.packageSize() != packageSize || !proof.sbomRef().equals(sbomRef)) {
            throw new ContractException("REVIEW_MISMATCH", "/rebuildProof");
        }
    }
}
