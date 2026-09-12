package top.sywyar.pixivdownload.sdk.community.review;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginRiskDeclaration;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.submission.DescriptorSnapshot;
import top.sywyar.pixivdownload.sdk.community.submission.VersionSubmission;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

@DisplayName("不可变版本审核交叉核对投稿、构建、描述符、门禁与可取回证据")
class VersionReviewTest {
    private static final String HASH = "ab".repeat(32);
    private static final String HEAD = "12".repeat(20);
    private static final String TIME = "2025-01-02T03:04:05Z";

    @Test
    @DisplayName("真实解析并回读独立构建证明和审核记录，原始字节与引用保持一致")
    void validReview() throws Exception {
        var f = new Fixture();
        f.verify(f.review);
        assertThat(f.review.descriptor().dependencies()).containsExactly(new PluginDependencyRef("another", "1.0", true));
        assertThat(f.review.descriptor().riskDeclaration().present()).isFalse();
        byte[] original = f.reviewDocument.bytes();
        f.verify(VersionReview.read(CommunityJson.parse(CommunityJson.Kind.REVIEW, original)));
        assertThat(f.reviewDocument.bytes()).isEqualTo(original);
    }

    @Test
    @DisplayName("不同安全字段不能借用已通过的审核，构建证明不可伪报一致")
    void rejectsMismatchedFacts() throws Exception {
        var f = new Fixture();
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                n -> n.put("packageSize", 2), n -> n.put("packageSha256", "cd".repeat(32)),
                n -> n.put("version", "9.8.7"), n -> n.with("owner").put("accountId", "999"),
                n -> n.with("source").put("commit", "34".repeat(20)),
                n -> n.with("descriptor").put("requiredSdk", "9.0"),
                n -> n.with("riskScan").put("runAttempt", 2), n -> n.put("assuranceLevel", "COMMUNITY_VERIFIED"),
                n -> n.with("humanReview").put("headSha", "34".repeat(20)))) {
            var data = (ObjectNode) f.reviewDocument.value();
            mutation.accept(data);
            assertThatThrownBy(() -> f.verify(VersionReview.read(CommunityJson.parse(CommunityJson.Kind.REVIEW, CommunityJson.encode(data)))))
                    .isInstanceOf(ContractException.class);
        }
        var wrongProof = new RebuildProof(1, HEAD, "17-test", "tool-test", HASH, f.lock, 1, HASH, f.sbom, "MISMATCH");
        assertThatThrownBy(() -> wrongProof.requireMatches(wrongProof, f.evidence)).isInstanceOf(ContractException.class);
        f.actualProof = new RebuildProof(1, HEAD, "different-jdk", "tool-test", HASH, f.lock, 1, HASH, f.sbom, "MATCH");
        assertThatThrownBy(() -> f.verify(f.review)).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("每种原始证据缺失都失败，不用孤立摘要或当前可变文件替代")
    void allEvidenceMustBeRetrievable() throws Exception {
        var f = new Fixture();
        for (String path : List.copyOf(f.evidence.keySet())) {
            var removed = f.evidence.remove(path);
            assertThatThrownBy(() -> f.verify(f.review)).as(path).isInstanceOf(ContractException.class);
            f.evidence.put(path, removed);
        }
        var alternate = new Evidence(f.sbom, f.evidence.get(f.sbom.path()).bytes());
        assertThat(alternate.reference()).isEqualTo(f.sbom);
        assertThatThrownBy(() -> new Evidence(f.sbom, "changed".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("同一门禁成功不能嫁接另一份报告、投稿或未完成的人工审核")
    void admissionRemainsBound() throws Exception {
        var f = new Fixture();
        var admitted = f.admission;
        var s = admitted.snapshot();
        var wrongSnapshot = new ReviewAdmission.Snapshot(s.repositoryId(), s.pr(),
                new ReviewDecision.Version("cd".repeat(32), HEAD, HASH), s.inputSha256(), s.bindingSha256(), s.policySha256(),
                s.state(), s.draft(), s.scan(), s.apply());
        f.admission = new ReviewAdmission.Result(wrongSnapshot, admitted.reportRef(), admitted.decisionRefs(), true, true,
                admitted.human(), false, false, List.of(), admitted.flow(), admitted.labels());
        assertThatThrownBy(() -> f.verify(f.review)).isInstanceOf(ContractException.class);
        f.admission = new ReviewAdmission.Result(s, admitted.reportRef(), admitted.decisionRefs(), true, true,
                new HumanReviews.Result(HumanReviews.Status.PENDING, null, List.of()), false, false, List.of(), ReviewAdmission.Flow.NONE, Set.of());
        assertThatThrownBy(() -> f.verify(f.review)).isInstanceOf(ContractException.class);
    }

    static final class Fixture {
        final Map<String, Evidence> evidence = new HashMap<>();
        final CommunityJson.Document submissionDocument;
        final CommunityJson.Document reviewDocument;
        final VersionReview review;
        final Owner owner = new Owner("101", "User", "example");
        final DescriptorSnapshot descriptor;
        final CommunityPr pr;
        final Reference lock, sbom, publication;
        RebuildProof actualProof;
        ReviewAdmission.Result admission;

        Fixture() throws Exception {
            this(sampleSubmission(), new CommunityPr("1001", 17, "101", "1002", HEAD, HEAD, null), HASH,
                    new DescriptorSnapshot("1.0", "declarative-process",
                            List.of(new PluginDependencyRef("another", "1.0", true)), PluginRiskDeclaration.absent()));
        }
        static CommunityJson.Document sampleSubmission() throws Exception {
            try (var input = VersionReviewTest.class.getResourceAsStream("/community/v1/vectors/submission.json")) {
                return CommunityJson.read(CommunityJson.Kind.SUBMISSION, input);
            }
        }
        Fixture(CommunityJson.Document submissionDocument, CommunityPr pr, String bindingSha256, DescriptorSnapshot descriptor) {
            this.submissionDocument = submissionDocument; this.pr = pr; this.descriptor = descriptor;
            var submission = VersionSubmission.read(submissionDocument);
            var submissionRef = put("history/submission.json", submissionDocument.bytes());
            lock = put("evidence/lock.json", "locked inputs");
            sbom = put("evidence/sbom.json", "sbom");
            publication = put("evidence/publication.json", "protected approval");
            var human = put("evidence/human.json", "native approve");
            actualProof = new RebuildProof(1, submission.source().commit(), "17-test", "tool-test", HASH, lock,
                    submission.artifact().expectedSize(), submission.artifact().sha256(), sbom, "MATCH");
            var proof = put("evidence/build.json", CommunityJson.encode(actualProof));
            var report = put("evidence/scan.json", CommunityJson.encode(new RiskReport(1, RiskReport.Status.COMPLETE,
                    "test-scanner", HASH, "300", 1, HEAD, submission.source().commit(), submission.artifact().sha256(), List.of(), List.of(), null)));
            var snapshot = new ReviewAdmission.Snapshot("test-catalog", pr,
                    new ReviewDecision.Version(submissionDocument.sha256(), submission.source().commit(), submission.artifact().sha256()),
                    submissionDocument.sha256(), bindingSha256, HASH,
                    pr.mergeSha() == null ? ReviewAdmission.PrState.OPEN : ReviewAdmission.PrState.MERGED, false,
                    new ReviewAdmission.Scan("300", 1, "test-scanner", HASH, ReviewAdmission.Conclusion.SUCCESS), null);
            var validation = new ReviewAdmission.Validation(ReviewAdmission.Conclusion.SUCCESS, "90", HEAD, HEAD,
                    submissionDocument.sha256(), bindingSha256, HASH);
            var policy = new ReviewPolicy(Set.of("202"), Set.of("202"), "workflows/review.yml", Set.of(HEAD));
            var nativeReview = new HumanReviews.NativeReview("20", "1001", 17, new Account("202", "User"), HEAD,
                    HumanReviews.NativeState.APPROVED, TIME, evidence.get(human.path()), null);
            admission = ReviewAdmission.evaluate(snapshot, snapshot, validation, "90", policy, List.of(nativeReview), List.of(),
                    evidence.get(report.path()), (int) report.size(), descriptor.riskDeclaration(), evidence);
            var record = new VersionReview(1, owner, "example", submission.pluginId(), submission.version(),
                    submission.artifact().expectedSize(), submission.artifact().sha256(), submissionRef, submission.source(),
                    submission.buildProfile(), descriptor, new VersionReview.Scan(RiskReport.Status.COMPLETE, "test-scanner", HASH,
                    "300", 1, report, List.of(), "PASS"), put("evidence/source.diff", "source difference"), sbom,
                    put("evidence/deps.json", "dependencies"), put("evidence/license.json", "license reviewed"), proof,
                    pr, admission.human().approval(), publication, TIME, "SOURCE_REVIEWED");
            reviewDocument = CommunityJson.parse(CommunityJson.Kind.REVIEW, CommunityJson.encode(record));
            review = VersionReview.read(reviewDocument);
        }
        void verify(VersionReview value) {
            value.verify(submissionDocument, new VersionReview.Facts(owner, descriptor, pr, admission, actualProof, publication),
                    evidence, CommunityJson.Kind.REVIEW.maximumBytes(), CommunityJson.Kind.REVIEW.maximumBytes());
        }
        Reference put(String path, String text) { return put(path, text.getBytes(StandardCharsets.UTF_8)); }
        Reference put(String path, byte[] bytes) {
            var ref = Reference.of(path, bytes);
            evidence.put(path, new Evidence(ref, bytes));
            return ref;
        }
    }
}
