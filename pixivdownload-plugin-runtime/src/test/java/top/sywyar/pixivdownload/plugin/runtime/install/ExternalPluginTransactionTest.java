package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRemovalAttempt;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;

@DisplayName("外置插件事务：替换、回滚与启动恢复")
class ExternalPluginTransactionTest extends ExternalPluginTransactionTestSupport {

    @Test
    @DisplayName("prepare 不触碰旧包，commit 后保留 backup，rollback 恢复旧版本")
    void rollbackRestoresPreviousArtifact() {
        Path plugins = temp.resolve("plugins");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("v1.zip", "1.0.0"));
        Path old = plugins.resolve("demo-1.0.0.zip");
        assertThat(sidecar(plugins, old)).exists();

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("v2.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());

        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
        assertThat(old).exists();
        assertThat(prepared.target()).doesNotExist();
        assertThat(prepared.stagedArtifact()).exists();
        assertThat(sidecar(plugins, prepared.stagedArtifact())).exists();

        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        assertThat(old).doesNotExist();
        assertThat(sidecar(plugins, old)).doesNotExist();
        assertThat(prepared.target()).exists();
        assertThat(sidecar(plugins, prepared.target())).exists();
        assertThat(committed.backups()).hasSize(1);
        assertThat(committed.backups().get(0).backup()).exists();
        assertThat(sidecar(plugins, committed.backups().get(0).backup())).exists();

        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(old).exists();
        assertThat(sidecar(plugins, old)).exists();
        assertThat(prepared.target()).doesNotExist();
        assertThat(sidecar(plugins, prepared.target())).doesNotExist();
    }

    @Test
    @DisplayName("同一签名所有者授权的新包仅隔离精确替代身份并随回滚恢复")
    void replacementTransactionTargetsExactRetiredIdentity() throws IOException {
        Path plugins = temp.resolve("plugins-replacement");
        PluginSigningTestSupport signing = PluginSigningTestSupport.createOfficial();
        ExternalPluginInstaller installer = new ExternalPluginInstaller(
                plugins, PluginPackageLimits.defaults(), signing.verifier());
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        Path retiredPackage = packageFile("retired.zip", "novel-gallery", "1.0.0", null);
        var retiredSignature = signing.artifactSignature(retiredPackage, "novel-gallery", "1.0.0");
        installFully(installer, retiredPackage,
                PluginPackageOrigin.localUpload(
                        retiredSignature, PluginPackageIntegrity.sha256Hex(retiredPackage)));
        installFully(installer, packageFile("third-party.zip", "novel-gallery-plus", "1.0.0", null));
        Path retired = plugins.resolve("novel-gallery-1.0.0.zip");
        Path unrelated = plugins.resolve("novel-gallery-plus-1.0.0.zip");
        Path replacement = packageFile("novel.zip", "novel", "1.0.0", "novel-gallery");
        var replacementSignature = signing.artifactSignature(replacement, "novel", "1.0.0");
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                replacement, false, PluginPackageOrigin.localUpload(
                        replacementSignature, PluginPackageIntegrity.sha256Hex(replacement)));

        assertThat(prepared.readyToCommit()).isTrue();
        assertThat(retired).exists();
        assertThat(sidecar(plugins, retired)).exists();
        assertThat(unrelated).exists();

        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        assertThat(retired).doesNotExist();
        assertThat(sidecar(plugins, retired)).doesNotExist();
        assertThat(unrelated).exists();
        assertThat(sidecar(plugins, unrelated)).exists();
        assertThat(prepared.target()).exists();

        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(retired).exists();
        assertThat(sidecar(plugins, retired)).exists();
        assertThat(unrelated).exists();
        assertThat(prepared.target()).doesNotExist();
    }

    @Test
    @DisplayName("替代包描述符校验失败时保留旧 artifact 与 provenance")
    void rejectedReplacementLeavesRetiredArtifactUntouched() {
        Path plugins = temp.resolve("plugins-replacement-rejected");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("retired-invalid.zip", "novel-gallery", "1.0.0", null));
        Path retired = plugins.resolve("novel-gallery-1.0.0.zip");

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("invalid-novel.zip", "novel", "1.0", "novel-gallery"), false,
                PluginPackageOrigin.localUpload());

        assertThat(prepared.readyToCommit()).isFalse();
        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INVALID);
        assertThat(retired).exists();
        assertThat(sidecar(plugins, retired)).exists();
    }

    @Test
    @DisplayName("NEW_PLACED 崩溃恢复优先恢复旧包，避免同 id 新旧包同时暴露")
    void recoverNewPlacedRestoresOld() {
        Path plugins = temp.resolve("plugins-recover-old");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("new.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        installer.commitTransaction(prepared);

        installer.close();
        ExternalPluginInstaller restarted = newInstaller(plugins);
        PluginTransactionRecoveryReport recovery = restarted.recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isTrue();
        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();
        assertThat(sidecar(plugins, plugins.resolve("demo-1.0.0.zip"))).exists();
        assertThat(plugins.resolve("demo-2.0.0.zip")).doesNotExist();
        assertThat(sidecar(plugins, plugins.resolve("demo-2.0.0.zip"))).doesNotExist();
        assertThat(restarted.listInstalled()).extracting(InstalledPlugin::version).containsExactly("1.0.0");
    }

    @Test
    @DisplayName("ACTIVATED 崩溃恢复保留新包并清理 backup")
    void recoverActivatedCommitsNew() {
        Path plugins = temp.resolve("plugins-recover-new");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("old-a.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("new-a.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.markActivated(committed);

        installer.close();
        ExternalPluginInstaller restarted = newInstaller(plugins);
        PluginTransactionRecoveryReport recovery = restarted.recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isTrue();
        assertThat(plugins.resolve("demo-1.0.0.zip")).doesNotExist();
        assertThat(sidecar(plugins, plugins.resolve("demo-1.0.0.zip"))).doesNotExist();
        assertThat(plugins.resolve("demo-2.0.0.zip")).exists();
        assertThat(sidecar(plugins, plugins.resolve("demo-2.0.0.zip"))).exists();
        assertThat(restarted.listInstalled()).extracting(InstalledPlugin::version).containsExactly("2.0.0");
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("坏事务 claims 在任何恢复写入前阻断整轮并原样保留合法事务")
    void invalidTransactionBlocksAllRecoveryBeforeMutation() throws IOException {
        Path plugins = temp.resolve("plugins-isolated-recovery");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("isolated-old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("isolated-new.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        Path oldArtifact = plugins.resolve("demo-1.0.0.zip");
        Path newArtifact = plugins.resolve("demo-2.0.0.zip");
        Path backup = committed.backups().get(0).backup();

        Path outside = temp.resolve("outside-target.jar").toAbsolutePath().normalize();
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        Path invalid = plugins.resolve(".staging").resolve("00-invalid");
        Properties manifest = manifest("00-invalid", "NEW_PLACED", "bad", "1.0.0", outside, 0);
        writeManifest(invalid, manifest);

        installer.close();
        PluginTransactionRecoveryReport recovery = newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isFalse();
        assertThat(recovery.failures())
                .extracting(PluginTransactionRecoveryReport.Failure::kind)
                .containsExactly(PluginTransactionRecoveryReport.FailureKind.UNSAFE_PATH);
        assertThat(Files.readString(outside, StandardCharsets.UTF_8)).isEqualTo("outside");
        assertThat(invalid).exists();
        assertThat(prepared.transactionDirectory()).exists();
        assertThat(readManifest(prepared.transactionDirectory()).getProperty("state"))
                .isEqualTo("NEW_PLACED");
        assertThat(oldArtifact).doesNotExist();
        assertThat(newArtifact).exists();
        assertThat(backup).exists();
    }

    @Test
    @DisplayName("终态退役后的清理异常只保留隐藏残留且重启可安全清理")
    void retiredCleanupRuntimeFailureDoesNotRollbackOrBlockRecovery() {
        Path plugins = temp.resolve("plugins-retired-cleanup");
        AtomicReference<Path> retainedCleanup = new AtomicReference<>();
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void beforeManagedCleanup(Path root) {
                Path parent = root.getParent();
                if (parent != null && ".transaction-cleanup".equals(parent.getFileName().toString())) {
                    retainedCleanup.set(root);
                    throw new IllegalStateException("simulated cleanup traversal failure");
                }
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("retired-cleanup.zip", "1.0.0"), false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.verifyCommittedTarget(committed);
        installer.markActivated(committed);

        installer.completeTransaction(committed);

        assertThat(installer.recoverySafeForRuntime()).isTrue();
        assertThat(prepared.target()).exists();
        assertThat(prepared.transactionDirectory()).doesNotExist();
        assertThat(retainedCleanup.get()).isNotNull().exists();

        installer.close();
        ExternalPluginInstaller restarted = new ExternalPluginInstaller(plugins);
        installers.add(restarted);
        PluginTransactionRecoveryReport recovery = restarted.recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isTrue();
        assertThat(restarted.recoverySafeForRuntime()).isTrue();
        assertThat(prepared.target()).exists();
        assertThat(plugins.resolve(".transaction-cleanup")).doesNotExist();
    }

    @Test
    @DisplayName("非权威准备与清理区无法删除时保留残留但不封闭权威恢复")
    void nonAuthoritativeWorkspaceResidueDoesNotBlockRecovery() throws IOException {
        Path plugins = temp.resolve("plugins-non-authoritative-residue");
        Files.createDirectories(plugins);
        Path preparingResidue = plugins.resolve(".preparing");
        Path cleanupResidue = plugins.resolve(".transaction-cleanup");
        Files.writeString(preparingResidue, "retained preparing residue", StandardCharsets.UTF_8);
        Files.writeString(cleanupResidue, "retained cleanup residue", StandardCharsets.UTF_8);
        ExternalPluginInstaller installer = newInstaller(plugins);

        PluginTransactionRecoveryReport recovery = installer.recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isTrue();
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(installer.listInstalled()).isEmpty();
        assertThat(preparingResidue).hasContent("retained preparing residue");
        assertThat(cleanupResidue).hasContent("retained cleanup residue");
    }
}
