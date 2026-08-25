package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.ExpectedArtifact;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryBackup;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryManifest;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryOperation;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionState;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件恢复计划集合")
class PluginRecoveryPlanSetTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("物理路径冲突优先且每个事务只产生一条诊断")
    void prioritizesPathConflictsPerTransaction() {
        Path sharedTarget = tempDir.resolve("sample.jar");
        PluginRecoveryPlan first = plan("first", "sample", sharedTarget, PluginTransactionState.PREPARED);
        PluginRecoveryPlan second = plan("second", "sample", sharedTarget, PluginTransactionState.PREPARED);

        List<PluginRecoveryPlanSet.Conflict> conflicts = new PluginRecoveryPlanSet(
                List.of(), List.of(first, second)).conflicts();

        assertThat(conflicts).hasSize(2)
                .extracting(PluginRecoveryPlanSet.Conflict::kind)
                .containsOnly(FailureKind.UNSAFE_PATH);
        assertThat(conflicts).extracting(conflict -> conflict.plan().transactionId())
                .containsExactly("first", "second");
    }

    @Test
    @DisplayName("不同路径声明同一插件身份时拒绝两个事务")
    void detectsIdentityConflictsAcrossDistinctPaths() {
        PluginRecoveryPlan first = plan(
                "first", "sample", tempDir.resolve("sample-a.jar"), PluginTransactionState.PREPARED);
        PluginRecoveryPlan second = plan(
                "second", "sample", tempDir.resolve("sample-b.jar"), PluginTransactionState.PREPARED);

        List<PluginRecoveryPlanSet.Conflict> conflicts = new PluginRecoveryPlanSet(
                List.of(), List.of(first, second)).conflicts();

        assertThat(conflicts).hasSize(2)
                .extracting(PluginRecoveryPlanSet.Conflict::kind)
                .containsOnly(FailureKind.IDENTITY_CONFLICT);
    }

    @Test
    @DisplayName("计划按回滚与终态分类并识别可见清点需求")
    void classifiesPlansAndInventoryRequirement() {
        PluginRecoveryPlan rollback = plan(
                "rollback", "old", tempDir.resolve("old.jar"), PluginTransactionState.PREPARED);
        PluginRecoveryPlan activated = plan(
                "activated", "new", tempDir.resolve("new.jar"), PluginTransactionState.ACTIVATED);
        PluginRecoveryPlan rolledBack = plan(
                "rolled-back", "done", tempDir.resolve("done.jar"), PluginTransactionState.ROLLED_BACK);
        Path empty = tempDir.resolve("empty");
        PluginRecoveryPlanSet plans = new PluginRecoveryPlanSet(
                List.of(empty), List.of(rollback, activated, rolledBack));

        assertThat(plans.emptyTransactions()).containsExactly(empty);
        assertThat(plans.rollbackPlans()).containsExactly(rollback);
        assertThat(plans.finalPlans()).containsExactly(activated, rolledBack);
        assertThat(plans.requiresVisibleInventory()).isTrue();
    }

    @Test
    @DisplayName("插件身份声明保持包、替代项与备份顺序")
    void preservesClaimedIdentityOrder() {
        Path transaction = tempDir.resolve("ordered");
        ExpectedArtifact backupArtifact = new ExpectedArtifact(
                "backup", "1.0.0", 1L, "sha256", "sidecar-sha256");
        RecoveryManifest manifest = new RecoveryManifest(
                RecoveryOperation.INSTALL,
                PluginTransactionState.PREPARED,
                "sample",
                "1.0.0",
                tempDir.resolve("sample.jar"),
                transaction.resolve("new").resolve("sample.jar"),
                null,
                List.of("legacy"),
                List.of(new RecoveryBackup(
                        backupArtifact,
                        tempDir.resolve("backup.jar"),
                        transaction.resolve("backup").resolve("backup.jar"))));

        PluginRecoveryPlan plan = new PluginRecoveryPlan("ordered", transaction, manifest);

        assertThat(plan.claimedPluginIdentities()).containsExactly("sample", "legacy", "backup");
    }

    private PluginRecoveryPlan plan(
            String transactionId,
            String packageId,
            Path target,
            PluginTransactionState state
    ) {
        Path transaction = tempDir.resolve(transactionId);
        RecoveryManifest manifest = new RecoveryManifest(
                RecoveryOperation.INSTALL,
                state,
                packageId,
                "1.0.0",
                target,
                transaction.resolve("new").resolve(target.getFileName()),
                null,
                List.of(),
                List.of());
        return new PluginRecoveryPlan(transactionId, transaction, manifest);
    }
}
