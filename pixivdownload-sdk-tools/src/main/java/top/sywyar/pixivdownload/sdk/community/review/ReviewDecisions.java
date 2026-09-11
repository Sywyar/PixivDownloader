package top.sywyar.pixivdownload.sdk.community.review;

import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.PriorityQueue;
import java.util.Set;

/** 只从可核对的原始裁决归约放行效果；旧 head、旧扫描与已撤销裁决不产生授权。 */
public final class ReviewDecisions {
    public record Applied(ReviewDecision decision, Reference reference) { }
    private final Set<String> falsePositives;
    private final boolean manualScanAccepted;
    private final Applied selfReview;
    private final List<Reference> references;

    private ReviewDecisions(Set<String> falsePositives, boolean manualScanAccepted, Applied selfReview, List<Reference> references) {
        this.falsePositives = Set.copyOf(falsePositives);
        this.manualScanAccepted = manualScanAccepted;
        this.selfReview = selfReview;
        this.references = List.copyOf(references);
    }
    public Set<String> falsePositives() { return falsePositives; }
    public boolean manualScanAccepted() { return manualScanAccepted; }
    public Applied selfReview() { return selfReview; }
    public List<Reference> references() { return references; }

    public static ReviewDecisions evaluate(String repositoryId, CommunityPr pr, ReviewDecision.Version version,
                                           RiskReport report, Reference reportRef, ReviewPolicy policy,
                                           List<ReviewDecision.Input> inputs) {
        var current = new HashMap<String, Applied>();
        var originals = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        var attempts = new HashMap<String, Applied>();
        for (var input : inputs) {
            var decision = ReviewDecision.verifySource(input, policy);
            if (!decision.repositoryId().equals(repositoryId) || !decision.targets(pr, version)) continue;
            var reference = input.archive().reference();
            var old = current.putIfAbsent(reference.sha256(), new Applied(decision, reference));
            if (old != null && !old.reference.equals(reference)) throw new ContractException("REVIEW_MISMATCH", "/decisionRefs");
            var original = (com.fasterxml.jackson.databind.node.ObjectNode) input.document().value();
            original.remove(List.of("runAttempt", "triggeringActorAccountId", "createdAt"));
            var previous = originals.putIfAbsent(decision.runId(), original);
            if (previous != null && !previous.equals(original)) throw new ContractException("REVIEW_MISMATCH", "/decision/rerun");
            attempts.merge(decision.runId(), new Applied(decision, reference), (left, right) -> {
                if (left.decision.runAttempt() == right.decision.runAttempt() && !left.reference.equals(right.reference)) {
                    throw new ContractException("REVIEW_MISMATCH", "/decision/runAttempt");
                }
                return left.decision.runAttempt() < right.decision.runAttempt() ? right : left;
            });
        }
        // 同一次 dispatch 的重跑保留同一决定；撤销旧 attempt 也会撤销该决定的新执行证据。
        var targets = new HashMap<String, String>();
        var incoming = new HashMap<String, Integer>();
        attempts.keySet().forEach(id -> incoming.put(id, 0));
        for (var applied : attempts.values()) {
            var d = applied.decision;
            if (!d.authorized(policy) || d.action() != ReviewDecision.Action.REVOKE_DECISION) continue;
            var target = current.get(d.targetDecisionSha256());
            if (target == null || Instant.parse(target.decision.decisionAt()).isAfter(Instant.parse(d.decisionAt()))) {
                throw new ContractException("REVIEW_MISMATCH", "/targetDecisionSha256");
            }
            targets.put(d.runId(), target.decision.runId());
            incoming.compute(target.decision.runId(), (id, count) -> count + 1);
        }
        var revoked = new HashSet<String>();
        var ready = new PriorityQueue<String>();
        incoming.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.remove();
            visited++;
            String target = targets.get(id);
            if (target != null) {
                if (!revoked.contains(id)) revoked.add(target);
                if (incoming.compute(target, (key, count) -> count - 1) == 0) ready.add(target);
            }
        }
        if (visited != attempts.size()) throw new ContractException("REVIEW_MISMATCH", "/targetDecisionSha256");
        var ordered = attempts.values().stream().sorted(Comparator.comparing((Applied a) -> Instant.parse(a.decision.decisionAt()))
                .thenComparing(a -> a.reference.sha256())).toList();
        var falsePositives = new HashSet<String>();
        var references = new ArrayList<Reference>();
        Applied self = null;
        boolean manual = false;
        for (var applied : ordered) {
            if (revoked.contains(applied.decision.runId())) continue;
            var d = applied.decision;
            if (!d.authorized(policy)) continue;
            switch (d.action()) {
                case FALSE_POSITIVE, MANUAL_SCAN_ACCEPTED -> {
                    if (report == null || !d.matchesScan(report, reportRef)) continue;
                    if (d.action() == ReviewDecision.Action.FALSE_POSITIVE) {
                        Set<String> ids = new HashSet<>();
                        report.findings().forEach(f -> ids.add(f.findingId()));
                        if (!ids.containsAll(d.findingIds())) throw new ContractException("REVIEW_MISMATCH", "/findingIds");
                        falsePositives.addAll(d.findingIds());
                    } else {
                        if (report.status() != RiskReport.Status.INCOMPLETE) throw new ContractException("REVIEW_MISMATCH", "/action");
                        manual = true;
                    }
                }
                case SELF_REVIEW_APPROVED -> {
                    if (!d.actorAccountId().equals(pr.authorAccountId())) throw new ContractException("REVIEW_MISMATCH", "/actorAccountId");
                    self = applied;
                }
                case REVOKE_DECISION -> { }
            }
            references.add(applied.reference);
        }
        // 撤销记录只保存目标摘要；同时保留目标原始引用，才能从 review 取回整条审计依据。
        var archived = new LinkedHashSet<>(references);
        for (var reference : references) {
            var decision = current.get(reference.sha256()).decision;
            while (decision.action() == ReviewDecision.Action.REVOKE_DECISION) {
                var target = current.get(decision.targetDecisionSha256());
                if (!archived.add(target.reference)) break;
                decision = target.decision;
            }
        }
        return new ReviewDecisions(falsePositives, manual, self, new ArrayList<>(archived));
    }
}
