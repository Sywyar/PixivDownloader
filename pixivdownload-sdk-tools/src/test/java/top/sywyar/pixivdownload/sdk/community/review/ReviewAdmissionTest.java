package top.sywyar.pixivdownload.sdk.community.review;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginRiskDeclaration;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.sdk.community.review.HumanReviews.NativeState.*;
import static top.sywyar.pixivdownload.sdk.community.review.ReviewAdmission.*;
import static top.sywyar.pixivdownload.sdk.community.review.ReviewDecision.Action.*;

@DisplayName("审核门禁绑定当前事实，人工拒绝、扫描裁决与发布状态分别生效")
class ReviewAdmissionTest {
    private static final String HASH = "ab".repeat(32);
    private static final String HEAD = "cd".repeat(20);
    private static final String BASE = "ef".repeat(20);
    private static final String SOURCE = "12".repeat(20);
    private static final String WORKFLOW = "34".repeat(20);
    private static final String TIME = "2025-01-02T03:04:05Z";
    private static final String PATH = "workflows/review.yml";
    private static final CommunityPr PR = new CommunityPr("100", 7, "1", "200", HEAD, BASE, null);
    private static final ReviewDecision.Version VERSION = new ReviewDecision.Version(HASH, SOURCE, HASH);
    private static final ReviewPolicy POLICY = new ReviewPolicy(Set.of("1", "2", "3"), Set.of("3"), PATH, Set.of(WORKFLOW));

    @Test
    @DisplayName("完整无命中允许未声明，标签只是确定性结果且重复或乱序输入不改变准入")
    void cleanAndRepeatedFacts() {
        var f = new Fixture();
        var result = f.evaluate();
        assertThat(result.flow()).isEqualTo(Flow.READY);
        assertThat(result.labels()).containsExactlyInAnyOrder("ci:passed", "review:approved", "state:ready");
        f.reviews.add(f.review("30", "3", COMMENTED, HEAD, null));
        assertThat(f.evaluate()).isEqualTo(result);
        java.util.Collections.reverse(f.reviews);
        assertThat(f.evaluate()).isEqualTo(result);
        f.reviews.add(f.review("31", "99", CHANGES_REQUESTED, HEAD, null));
        assertThat(f.evaluate()).isEqualTo(result);
    }

    @Test
    @DisplayName("授权拒绝优先于成功 CI；同一审核者新批准或有理由的原生撤销才能解除")
    void humanVetoAndResolution() {
        var f = new Fixture();
        f.reviews.add(f.review("20", "3", CHANGES_REQUESTED, HEAD, null));
        assertThat(f.evaluate().human().status()).isEqualTo(HumanReviews.Status.CHANGES_REQUESTED);
        assertThat(f.evaluate().labels()).contains("ci:passed", "review:changes-requested").doesNotContain("state:ready");
        f.reviews.add(f.review("21", "3", COMMENTED, HEAD, null));
        assertThat(f.evaluate().flow()).isEqualTo(Flow.NONE);
        f.reviews.add(f.review("22", "3", APPROVED, HEAD, null));
        assertThat(f.evaluate().flow()).isEqualTo(Flow.READY);
        f.reviews.remove(f.reviews.size() - 1);
        f.reviews.set(1, f.review("20", "3", DISMISSED, HEAD,
                new HumanReviews.Dismissal("3", "Evidence corrected", bytes("dismissal"))));
        assertThat(f.evaluate().flow()).isEqualTo(Flow.READY);
        f.reviews.set(1, f.review("20", "3", DISMISSED, HEAD,
                new HumanReviews.Dismissal("2", "Evidence corrected", bytes("dismissal"))));
        assertThatThrownBy(f::evaluate).isInstanceOf(ContractException.class);
        f.reviews.set(1, f.review("20", "3", DISMISSED, HEAD,
                new HumanReviews.Dismissal("3", " ", bytes("dismissal"))));
        assertThatThrownBy(f::evaluate).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("新 head 使旧批准失效但保留人工拒绝，非授权人员没有批准权或否决权")
    void newHeadAndPermissions() {
        var f = new Fixture();
        f.pr = new CommunityPr("100", 7, "1", "200", "56".repeat(20), BASE, null);
        assertThat(f.evaluate().human().status()).isEqualTo(HumanReviews.Status.PENDING);
        f.reviews.add(f.review("20", "3", CHANGES_REQUESTED, HEAD, null));
        assertThat(f.evaluate().human().status()).isEqualTo(HumanReviews.Status.CHANGES_REQUESTED);
        f.policy = new ReviewPolicy(Set.of("1", "2"), Set.of("3"), PATH, Set.of(WORKFLOW));
        assertThat(f.evaluate().human().status()).isEqualTo(HumanReviews.Status.PENDING);
        f.reviews.add(f.review("30", "2", APPROVED, f.pr.headSha(), null));
        assertThat(f.evaluate().flow()).isEqualTo(Flow.READY);
    }

    @Test
    @DisplayName("显式自审记录同一作者和审核者，不替代扫描且不能覆盖他人拒绝")
    void explicitSelfReview() {
        var f = new Fixture();
        f.reviews.clear();
        f.reviews.add(f.review("10", "1", APPROVED, HEAD, null));
        assertThat(f.evaluate().human().status()).isEqualTo(HumanReviews.Status.PENDING);
        var self = f.decision(SELF_REVIEW_APPROVED, "1", "400", n -> { });
        f.decisions.add(self);
        var result = f.evaluate();
        assertThat(result.human().status()).isEqualTo(HumanReviews.Status.SELF_APPROVED);
        assertThat(result.human().approval().selfReview()).isTrue();
        assertThat(result.human().approval().authorAccountId()).isEqualTo(result.human().approval().reviewerAccountId());
        f.reviews.add(f.review("20", "3", CHANGES_REQUESTED, HEAD, null));
        assertThat(f.evaluate().flow()).isEqualTo(Flow.NONE);
        f.reviews.clear();
        f.incomplete = true;
        assertThat(f.evaluate().riskPassed()).isFalse();
        f.decisions.clear();
        f.decisions.add(f.decision(SELF_REVIEW_APPROVED, "2", "401", n -> { }));
        assertThatThrownBy(f::evaluate).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> f.decision(SELF_REVIEW_APPROVED, "1", "402", n -> n.remove("selfReview")))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("指定误报只解除对应命中，保留原始报告和另一条有效违规")
    void falsePositiveIsSpecific() {
        var f = new Fixture();
        f.violation("first"); f.violation("second");
        byte[] original = f.report().bytes();
        assertThat(f.evaluate().blockingFindingIds()).containsExactly("first", "second");
        f.decisions.add(f.decision(FALSE_POSITIVE, "2", "400", n -> n.putArray("findingIds").add("first")));
        assertThat(f.evaluate().blockingFindingIds()).containsExactly("second");
        assertThat(f.evaluate().labels()).contains("scan:false-positive").doesNotContain("state:ready");
        assertThat(f.report().bytes()).isEqualTo(original);
        f.decisions.add(f.decision(FALSE_POSITIVE, "2", "401", n -> n.putArray("findingIds").add("second")));
        assertThat(f.evaluate().flow()).isEqualTo(Flow.READY);
        f.scanAttempt++;
        assertThat(f.evaluate().blockingFindingIds()).containsExactly("first", "second");
        f.scanAttempt--;
        f.rules = "56".repeat(32);
        assertThat(f.evaluate().blockingFindingIds()).hasSize(2);
    }

    @Test
    @DisplayName("人工补扫仅填补未完成扫描，不能豁免命中或输入复现失败")
    void manualScanDoesNotWaiveOtherChecks() {
        var f = new Fixture();
        f.incomplete = true;
        assertThat(f.evaluate().labels()).contains("scan:incomplete", "ci:blocked");
        f.decisions.add(f.decision(MANUAL_SCAN_ACCEPTED, "2", "400", n -> { }));
        assertThat(f.evaluate().labels()).contains("scan:incomplete", "scan:manual-accepted", "state:ready");
        f.conclusion = Conclusion.FAILURE;
        assertThat(f.evaluate().flow()).isEqualTo(Flow.NONE);
        f.conclusion = Conclusion.SUCCESS;
        f.violation("policy");
        f.decisions.clear();
        f.decisions.add(f.decision(MANUAL_SCAN_ACCEPTED, "2", "401", n -> { }));
        assertThat(f.evaluate().blockingFindingIds()).containsExactly("policy");
        assertThat(f.evaluate().flow()).isEqualTo(Flow.NONE);
    }

    @Test
    @DisplayName("伪造来源、演员和报告均拒绝；同名陌生检查与非成功结论不会放行")
    void sourceBindingAndFailures() {
        for (var conclusion : Conclusion.values()) {
            if (conclusion == Conclusion.SUCCESS) continue;
            var f = new Fixture(); f.conclusion = conclusion;
            assertThat(f.evaluate().flow()).isEqualTo(Flow.NONE);
        }
        var f = new Fixture();
        f.publisher = "999";
        assertThat(f.evaluate().validationPassed()).isFalse();
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                n -> n.put("workflowSha", "56".repeat(20)), n -> n.put("actorAccountId", "2"),
                n -> n.put("runId", "999"), n -> n.put("headRepositoryId", "999"))) {
            f.decisions.clear();
            f.decisions.add(f.decision(SELF_REVIEW_APPROVED, "1", "400", mutation));
            assertThatThrownBy(f::evaluate).isInstanceOf(ContractException.class);
        }
        f.decisions.clear();
        f.decisions.add(f.decision(SELF_REVIEW_APPROVED, "99", "401", n -> { }));
        f.reviews.clear();
        assertThat(f.evaluate().human().passed()).isFalse();
        f.decisions.clear();
        f.reportOverride = bytes("not-json");
        assertThatThrownBy(f::evaluate).isInstanceOf(ContractException.class);
        f.reportOverride = null;
        f.omitReport = true;
        assertThatThrownBy(f::evaluate).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("撤销裁决追加生效，重跑旧决定不能恢复授权，撤销撤销也按依赖重新归约")
    void revocationAndReruns() {
        var f = new Fixture(); f.reviews.clear();
        var self = f.decision(SELF_REVIEW_APPROVED, "1", "400", n -> { });
        var revoke = f.decision(REVOKE_DECISION, "2", "401", n -> n.put("targetDecisionSha256", self.document().sha256()));
        f.decisions.add(self); f.decisions.add(revoke);
        assertThat(f.evaluate().human().passed()).isFalse();
        assertThat(f.evaluate().decisionRefs()).containsExactly(revoke.archive().reference(), self.archive().reference());
        f.decisions.add(rerun(self, "2", n -> { }));
        assertThat(f.evaluate().human().passed()).isFalse();
        f.decisions.add(f.decision(REVOKE_DECISION, "2", "402", n -> n.put("targetDecisionSha256", revoke.document().sha256())));
        assertThat(f.evaluate().human().passed()).isTrue();
        assertThat(f.evaluate().decisionRefs()).contains(revoke.archive().reference(), self.archive().reference());
        f.decisions.clear(); f.decisions.add(self); f.decisions.add(rerun(self, "99", n -> { }));
        assertThat(f.evaluate().human().passed()).isFalse();
        f.decisions.clear(); f.decisions.add(self); f.decisions.add(rerun(self, "2", n -> n.put("reason", "Changed approval")));
        assertThatThrownBy(f::evaluate).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("当前事实变化不签发成功，base 更新需新校验而无需重建同一源码包")
    void comparesSnapshotsAndCurrentBase() {
        var f = new Fixture(); f.reviews.clear();
        f.decisions.add(f.decision(SELF_REVIEW_APPROVED, "1", "400", n -> { }));
        var original = f.snapshot();
        f.pr = new CommunityPr("100", 7, "1", "200", HEAD, "56".repeat(20), null);
        assertThat(f.evaluate().flow()).isEqualTo(Flow.READY);
        var current = f.snapshot();
        assertThatThrownBy(() -> f.evaluate(original, current, f.validation(current))).isInstanceOf(ContractException.class);
        assertThat(f.evaluate(current, current, f.validation(original)).validationPassed()).isFalse();
        f.draft = true;
        assertThat(f.evaluate().flow()).isEqualTo(Flow.NONE);
    }

    @Test
    @DisplayName("未合并关闭、合并等待、失败及实际 generation 回读分别投影")
    void closingAndPublishing() {
        var f = new Fixture(); f.state = PrState.CLOSED;
        assertThat(f.evaluate().flow()).isEqualTo(Flow.CLOSED);
        assertThat(f.evaluate().labels()).doesNotContain("state:ready");
        f.state = PrState.MERGED;
        f.pr = new CommunityPr("100", 7, "1", "200", HEAD, BASE, "78".repeat(20));
        assertThat(f.evaluate().flow()).isEqualTo(Flow.AWAITING_APPLY);
        f.apply = new Apply(ApplyState.FAILED, HASH, null);
        assertThat(f.evaluate().flow()).isEqualTo(Flow.APPLY_FAILED);
        f.apply = new Apply(ApplyState.APPLIED, HASH, "56".repeat(32));
        assertThat(f.evaluate().flow()).isEqualTo(Flow.APPLY_FAILED);
        f.apply = new Apply(ApplyState.APPLIED, HASH, HASH);
        assertThat(f.evaluate().flow()).isEqualTo(Flow.COMPLETED);
        // 发布历史已是事实，后来撤销审核不能把已经回读的历史改写为未发布。
        f.reviews.clear();
        assertThat(f.evaluate().flow()).isEqualTo(Flow.COMPLETED);
    }

    @Test
    @DisplayName("非版本操作显式不适用扫描，可自审但不能混入扫描复核字段")
    void nonVersionOperation() {
        var f = new Fixture(); f.version = null; f.reviews.clear();
        f.decisions.add(f.decision(SELF_REVIEW_APPROVED, "1", "400", n -> { }));
        assertThat(f.evaluate().flow()).isEqualTo(Flow.READY);
        assertThatThrownBy(() -> f.decision(MANUAL_SCAN_ACCEPTED, "1", "401", n -> { }))
                .isInstanceOf(ContractException.class);
    }

    private static final class Fixture {
        CommunityPr pr = PR;
        ReviewDecision.Version version = VERSION;
        ReviewPolicy policy = POLICY;
        Conclusion conclusion = Conclusion.SUCCESS;
        String publisher = "90";
        String rules = HASH;
        long scanAttempt = 1;
        boolean incomplete, draft, omitReport;
        PrState state = PrState.OPEN;
        Apply apply;
        Evidence reportOverride;
        final List<RiskReport.Finding> findings = new ArrayList<>();
        final List<HumanReviews.NativeReview> reviews = new ArrayList<>();
        final List<ReviewDecision.Input> decisions = new ArrayList<>();
        Fixture() { reviews.add(review("10", "2", APPROVED, HEAD, null)); }

        void violation(String id) {
            findings.add(new RiskReport.Finding(id, "private-credential-access", "CREDENTIAL_ACCESS", RiskReport.Kind.POLICY_VIOLATION,
                    List.of(id), List.of(bytes("evidence").reference())));
        }
        Evidence report() {
            return evidence(CommunityJson.encode(new RiskReport(1, incomplete ? RiskReport.Status.INCOMPLETE : RiskReport.Status.COMPLETE,
                    "test-scanner", rules, "300", scanAttempt, pr.headSha(), SOURCE, HASH,
                    findings.stream().map(f -> new RiskReport.Observation(f.findingId(), "CREDENTIAL_ACCESS", RiskReport.Origin.PLUGIN,
                            new RiskReport.Location("sample.class", "example.Sample", "run", "()V", 12L, null, null),
                            f.evidence())).toList(), findings, incomplete ? "SCANNER_FAILED" : null)));
        }
        Snapshot snapshot() {
            return new Snapshot("test-catalog", pr, version, HASH, HASH, HASH, state, draft,
                    version == null ? null : new Scan("300", scanAttempt, "test-scanner", rules,
                            incomplete ? Conclusion.FAILURE : Conclusion.SUCCESS), apply);
        }
        Validation validation(Snapshot snapshot) {
            return new Validation(conclusion, publisher, snapshot.pr().headSha(), snapshot.pr().baseSha(), HASH, HASH, HASH);
        }
        Result evaluate() { var s = snapshot(); return evaluate(s, s, validation(s)); }
        Result evaluate(Snapshot before, Snapshot after, Validation validation) {
            var report = version == null || omitReport ? null : reportOverride == null ? report() : reportOverride;
            return ReviewAdmission.evaluate(before, after, validation, "90", policy, reviews, decisions, report,
                    report == null ? 0 : report.bytes().length,
                    findings.isEmpty() ? PluginRiskDeclaration.absent() : new PluginRiskDeclaration(true, List.of("CREDENTIAL_ACCESS")),
                    Map.of(bytes("evidence").reference().path(), bytes("evidence")));
        }
        HumanReviews.NativeReview review(String id, String reviewer, HumanReviews.NativeState state, String head,
                                         HumanReviews.Dismissal dismissal) {
            return new HumanReviews.NativeReview(id, "100", 7, new Account(reviewer, "User"), head, state,
                    TIME, bytes("native-review-" + id), dismissal);
        }
        ReviewDecision.Input decision(ReviewDecision.Action action, String actor, String run, Consumer<ObjectNode> mutation) {
            var data = new ReviewDecision(1, action, "Reviewed evidence", "100", "test-catalog", 7, "200", pr.headSha(), pr.baseSha(),
                    version == null ? null : HASH, version == null ? null : SOURCE, version == null ? null : HASH,
                    actor, "test-reviewer", "1", actor, PATH, WORKFLOW, run, 1, TIME, TIME,
                    action == FALSE_POSITIVE || action == MANUAL_SCAN_ACCEPTED ? "300" : null,
                    action == FALSE_POSITIVE || action == MANUAL_SCAN_ACCEPTED ? scanAttempt : null,
                    action == FALSE_POSITIVE || action == MANUAL_SCAN_ACCEPTED ? "test-scanner" : null,
                    action == FALSE_POSITIVE || action == MANUAL_SCAN_ACCEPTED ? rules : null,
                    action == FALSE_POSITIVE || action == MANUAL_SCAN_ACCEPTED ? report().reference() : null,
                    null, null, action == SELF_REVIEW_APPROVED ? "SELF" : null, action == SELF_REVIEW_APPROVED ? true : null);
            byte[] bytes = CommunityJson.encode(data);
            var tree = (ObjectNode) CommunityJson.strictTree(bytes, bytes.length);
            mutation.accept(tree);
            var source = new ReviewDecision.Execution(pr, "test-catalog", version, PATH, WORKFLOW, run, 1,
                    new Account(actor, "User"), new Account(actor, "User"), TIME);
            return input(tree, source);
        }
    }

    private static ReviewDecision.Input rerun(ReviewDecision.Input original, String actor, Consumer<ObjectNode> mutation) {
        var tree = (ObjectNode) original.document().value();
        tree.put("runAttempt", 2).put("triggeringActorAccountId", actor).put("createdAt", "2025-01-02T03:05:06Z");
        mutation.accept(tree);
        var e = original.execution();
        return input(tree, new ReviewDecision.Execution(e.pr(), e.repositoryId(), e.version(), e.workflowPath(), e.workflowSha(),
                e.runId(), 2, e.originalActor(), new Account(actor, "User"), e.decisionAt()));
    }
    private static ReviewDecision.Input input(ObjectNode data, ReviewDecision.Execution source) {
        var document = CommunityJson.parse(CommunityJson.Kind.DECISION, CommunityJson.encode(data));
        return new ReviewDecision.Input(document, evidence(document.bytes()), source);
    }
    private static Evidence bytes(String text) { return evidence(text.getBytes(StandardCharsets.UTF_8)); }
    private static Evidence evidence(byte[] bytes) {
        return new Evidence(Reference.of("reviews/evidence/" + CommunityJson.sha256(bytes) + ".json", bytes), bytes);
    }
}
