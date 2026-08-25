package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 拥有一次恢复扫描的计划分类和跨事务 claim 冲突判定。 */
public final class PluginRecoveryPlanSet {

    private final List<Path> emptyTransactions;
    private final List<PluginRecoveryPlan> plans;

    public PluginRecoveryPlanSet(
            List<Path> emptyTransactions,
            List<PluginRecoveryPlan> plans
    ) {
        this.emptyTransactions = List.copyOf(emptyTransactions);
        this.plans = List.copyOf(plans);
    }

    public List<Path> emptyTransactions() {
        return emptyTransactions;
    }

    public List<PluginRecoveryPlan> rollbackPlans() {
        return plans.stream().filter(plan -> !plan.finalState()).toList();
    }

    public List<PluginRecoveryPlan> finalPlans() {
        return plans.stream().filter(PluginRecoveryPlan::finalState).toList();
    }

    public boolean requiresVisibleInventory() {
        return plans.stream().anyMatch(PluginRecoveryPlan::requiresVisibleInventory);
    }

    /**
     * 每个事务至多返回一条冲突；物理路径冲突优先于插件身份冲突，
     * 与恢复门面原有诊断次序一致。
     */
    public List<Conflict> conflicts() {
        List<Conflict> conflicts = new ArrayList<>();
        Set<String> conflictedTransactions = new LinkedHashSet<>();

        Map<Path, List<PluginRecoveryPlan>> pathOwners = new LinkedHashMap<>();
        for (PluginRecoveryPlan plan : plans) {
            for (Path claim : plan.manifest().claimedArtifactPaths()) {
                pathOwners.computeIfAbsent(claim, ignored -> new ArrayList<>()).add(plan);
            }
        }
        pathOwners.forEach((path, owners) -> addConflicts(
                owners,
                FailureKind.UNSAFE_PATH,
                "artifact path is claimed by multiple transactions (" + transactionIds(owners) + "): " + path,
                conflictedTransactions,
                conflicts
        ));

        Map<String, List<PluginRecoveryPlan>> identityOwners = new LinkedHashMap<>();
        for (PluginRecoveryPlan plan : plans) {
            for (String identity : plan.claimedPluginIdentities()) {
                identityOwners.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(plan);
            }
        }
        identityOwners.forEach((identity, owners) -> addConflicts(
                owners,
                FailureKind.IDENTITY_CONFLICT,
                "plugin identity is claimed by multiple transactions (" + transactionIds(owners) + "): "
                        + identity,
                conflictedTransactions,
                conflicts
        ));
        return List.copyOf(conflicts);
    }

    private static void addConflicts(
            List<PluginRecoveryPlan> owners,
            FailureKind kind,
            String detail,
            Set<String> conflictedTransactions,
            List<Conflict> conflicts
    ) {
        if (owners.size() < 2) {
            return;
        }
        for (PluginRecoveryPlan plan : owners) {
            if (conflictedTransactions.add(plan.transactionId())) {
                conflicts.add(new Conflict(plan, kind, detail));
            }
        }
    }

    private static String transactionIds(List<PluginRecoveryPlan> owners) {
        return owners.stream()
                .map(PluginRecoveryPlan::transactionId)
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    public record Conflict(
            PluginRecoveryPlan plan,
            FailureKind kind,
            String detail
    ) {
    }
}
