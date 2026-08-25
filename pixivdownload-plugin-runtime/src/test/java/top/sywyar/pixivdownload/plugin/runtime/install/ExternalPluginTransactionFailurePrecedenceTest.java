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
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRemovalAttempt;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;

@DisplayName("外置插件事务：致命错误优先级与补偿")
class ExternalPluginTransactionFailurePrecedenceTest extends ExternalPluginTransactionTestSupport {

    @Test
    @DisplayName("激活与完成终态落盘后的普通 Error 均保留新代")
    void terminalManifestErrorsKeepDurableGeneration() {
        Path plugins = temp.resolve("plugins-terminal-manifest-errors");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterActivationManifestPersisted(Path transaction) {
                throw new AssertionError("simulated post-activation error");
            }

            @Override
            void afterCommittedManifestPersisted(Path transaction) {
                throw new AssertionError("simulated post-commit error");
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("terminal-manifest-errors.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.verifyCommittedTarget(committed);

        installer.markActivated(committed);
        installer.completeTransaction(committed);

        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(prepared.target()).exists();
        assertThat(prepared.transactionDirectory()).doesNotExist();
    }

    @Test
    @DisplayName("fatal 发生在 ACTIVATED 与 COMMITTED 之后时回执保留权威终态")
    void fatalTerminalManifestFailuresUpdateCommitReceiptBeforeRethrow() {
        Path activationPlugins = temp.resolve("plugins-fatal-activation");
        VirtualMachineError activationFatal = new VirtualMachineError("fatal after activation") { };
        ExternalPluginInstaller activationInstaller = new ExternalPluginInstaller(activationPlugins) {
            @Override
            void afterActivationManifestPersisted(Path transaction) {
                throw activationFatal;
            }
        };
        installers.add(activationInstaller);
        assertThat(activationInstaller.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction activationPrepared = activationInstaller.prepareTransaction(
                packageFile("fatal-activation.zip", "1.0.0"), false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction activationCommitted =
                activationInstaller.commitTransaction(activationPrepared);

        assertThatThrownBy(() -> activationInstaller.markActivated(activationCommitted))
                .isSameAs(activationFatal);
        assertThat(activationCommitted.durableState())
                .isEqualTo(CommittedPluginTransaction.DurableState.ACTIVATED);

        Path completionPlugins = temp.resolve("plugins-fatal-completion");
        VirtualMachineError completionFatal = new VirtualMachineError("fatal after completion") { };
        ExternalPluginInstaller completionInstaller = new ExternalPluginInstaller(completionPlugins) {
            @Override
            void afterCommittedManifestPersisted(Path transaction) {
                throw completionFatal;
            }
        };
        installers.add(completionInstaller);
        assertThat(completionInstaller.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction completionPrepared = completionInstaller.prepareTransaction(
                packageFile("fatal-completion.zip", "1.0.0"), false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction completionCommitted =
                completionInstaller.commitTransaction(completionPrepared);
        completionInstaller.markActivated(completionCommitted);

        assertThatThrownBy(() -> completionInstaller.completeTransaction(completionCommitted))
                .isSameAs(completionFatal);
        assertThat(completionCommitted.durableState())
                .isEqualTo(CommittedPluginTransaction.DurableState.RETIRED);
        assertThat(completionPrepared.transactionDirectory()).doesNotExist();
        assertThat(completionPrepared.target()).exists();
    }

    @Test
    @DisplayName("fatal 删除失败重抛前回执已确认 REMOVED")
    void fatalRemovalTerminalUpdatesReceiptBeforeRethrow() {
        Path plugins = temp.resolve("plugins-fatal-removal");
        ExternalPluginInstaller baseline = newInstaller(plugins);
        installFully(baseline, packageFile("fatal-removal.zip", "1.0.0"));
        baseline.close();
        VirtualMachineError fatal = new VirtualMachineError("fatal after removal") { };
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterRemovalCommittedManifestPersisted(Path transaction) {
                throw fatal;
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PluginRemovalAttempt attempt = new PluginRemovalAttempt("demo");

        assertThatThrownBy(() -> installer.removeInstalled(attempt)).isSameAs(fatal);

        assertThat(attempt.outcome()).isEqualTo(PluginRemovalAttempt.Outcome.REMOVED);
        assertThat(installer.listInstalled()).isEmpty();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("commit 补偿失败按 fatal 与先发顺序选择主异常")
    void commitRecoveryFailureUsesFatalAndOriginalOrder() {
        VirtualMachineError originalFatal = new VirtualMachineError("original fatal") { };
        AssertionError laterError = new AssertionError("later ordinary error");
        assertCommitRecoveryPrimary(
                temp.resolve("plugins-commit-original-fatal"),
                originalFatal, laterError, originalFatal);

        AssertionError originalError = new AssertionError("original ordinary error");
        ThreadDeath laterFatal = new ThreadDeath();
        assertCommitRecoveryPrimary(
                temp.resolve("plugins-commit-later-fatal"),
                originalError, laterFatal, laterFatal);

        AssertionError firstError = new AssertionError("first ordinary error");
        AssertionError secondError = new AssertionError("second ordinary error");
        assertCommitRecoveryPrimary(
                temp.resolve("plugins-commit-error-tie"),
                firstError, secondError, firstError);
    }

    @Test
    @DisplayName("verify 后 discard 失败不会用后续普通 Error 覆盖原 fatal")
    void verifyDiscardFailureKeepsOriginalFatal() {
        Path plugins = temp.resolve("plugins-verify-discard-fatal");
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        VirtualMachineError originalFatal = new VirtualMachineError("verification fatal") { };
        AssertionError discardFailure = new AssertionError("discard error");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void beforeRecoveryManifestRead(Path manifest) {
                if (!armed.get()) {
                    return;
                }
                if (reads.incrementAndGet() == 1) {
                    throw originalFatal;
                }
                throw discardFailure;
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("verify-discard-fatal.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        armed.set(true);

        assertThatThrownBy(() -> installer.verifyCurrentArtifacts(prepared)).isSameAs(originalFatal);

        assertThat(originalFatal.getSuppressed()).containsExactly(discardFailure);
        assertThat(discardFailure.getSuppressed()).isEmpty();
        assertThat(prepared.commitState()).isEqualTo(PreparedPluginTransaction.CommitState.UNSAFE);
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
    }

    @Test
    @DisplayName("ACTIVATED 重读的后续 fatal 覆盖普通 Error 并正向挂载 suppressed")
    void activationReconciliationFatalSupersedesOrdinaryError() {
        Path plugins = temp.resolve("plugins-activation-reconciliation-fatal");
        AtomicBoolean reconciling = new AtomicBoolean();
        AssertionError originalError = new AssertionError("post-activation error");
        VirtualMachineError reconciliationFatal =
                new VirtualMachineError("activation reconciliation fatal") { };
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterActivationManifestPersisted(Path transaction) {
                reconciling.set(true);
                throw originalError;
            }

            @Override
            void beforeRecoveryManifestRead(Path manifest) {
                if (reconciling.get()) {
                    throw reconciliationFatal;
                }
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("activation-reconciliation-fatal.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);

        assertThatThrownBy(() -> installer.markActivated(committed)).isSameAs(reconciliationFatal);

        assertThat(reconciliationFatal.getSuppressed()).containsExactly(originalError);
        assertThat(originalError.getSuppressed()).isEmpty();
        assertThat(committed.recoveryBlocked()).isTrue();
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
    }

    @Test
    @DisplayName("COMMITTED 持久化与退役双失败按原后 fatal 优先级抛出")
    void completionRetirementFailureUsesFatalPrecedence() {
        VirtualMachineError originalFatal = new VirtualMachineError("completion original fatal") { };
        ThreadDeath laterFatal = new ThreadDeath();
        assertCompletionRetirementPrimary(
                temp.resolve("plugins-completion-original-fatal"),
                originalFatal, laterFatal, originalFatal);

        IllegalStateException originalRuntime =
                new IllegalStateException("completion ordinary runtime");
        VirtualMachineError retirementFatal = new VirtualMachineError("retirement fatal") { };
        assertCompletionRetirementPrimary(
                temp.resolve("plugins-completion-later-fatal"),
                originalRuntime, retirementFatal, retirementFatal);
    }

    @Test
    @DisplayName("COMMITTED 退役保留重读的 fatal 携带原退役失败")
    void committedRetentionFatalCarriesRetirementFailure() throws IOException {
        Path plugins = temp.resolve("plugins-committed-retention-fatal");
        AtomicBoolean completing = new AtomicBoolean();
        AtomicInteger manifestReads = new AtomicInteger();
        VirtualMachineError reconciliationFatal =
                new VirtualMachineError("committed retention fatal") { };
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void beforeRecoveryManifestRead(Path manifest) {
                if (completing.get() && manifestReads.incrementAndGet() == 3) {
                    throw reconciliationFatal;
                }
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("committed-retention-fatal.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.markActivated(committed);
        Files.writeString(plugins.resolve(".transaction-cleanup"),
                "block retirement", StandardCharsets.UTF_8);
        completing.set(true);

        assertThatThrownBy(() -> installer.completeTransaction(committed)).isSameAs(reconciliationFatal);

        assertThat(reconciliationFatal.getSuppressed()).singleElement().satisfies(failure -> {
            assertThat(failure).isInstanceOf(IOException.class);
            assertThat(failure.getMessage()).contains("transaction finalization root");
        });
        assertThat(reconciliationFatal.getSuppressed()[0].getSuppressed()).isEmpty();
        assertThat(manifestReads.get()).isEqualTo(3);
        assertThat(committed.recoveryBlocked()).isTrue();
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
    }

    @Test
    @DisplayName("删除恢复失败不会用后续普通 Error 覆盖原 fatal")
    void removalRecoveryFailureKeepsOriginalFatal() {
        Path plugins = temp.resolve("plugins-removal-recovery-fatal");
        ExternalPluginInstaller baseline = newInstaller(plugins);
        installFully(baseline, packageFile("removal-recovery-fatal.zip", "1.0.0"));
        baseline.close();
        AtomicBoolean recovering = new AtomicBoolean();
        VirtualMachineError originalFatal = new VirtualMachineError("removal original fatal") { };
        AssertionError recoveryFailure = new AssertionError("removal recovery error");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterRemovalTransactionPublished(Path transaction) {
                recovering.set(true);
                throw originalFatal;
            }

            @Override
            void beforeRecoveryManifestRead(Path manifest) {
                if (recovering.get()) {
                    throw recoveryFailure;
                }
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PluginRemovalAttempt attempt = new PluginRemovalAttempt("demo");

        assertThatThrownBy(() -> installer.removeInstalled(attempt)).isSameAs(originalFatal);

        assertThat(originalFatal.getSuppressed()).containsExactly(recoveryFailure);
        assertThat(recoveryFailure.getSuppressed()).isEmpty();
        assertThat(attempt.outcome()).isEqualTo(PluginRemovalAttempt.Outcome.UNSAFE);
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
    }

    @Test
    @DisplayName("安装发布前清理失败不会用后续普通 Error 覆盖原 fatal")
    void prepareCleanupFailureKeepsOriginalFatal() {
        Path plugins = temp.resolve("plugins-prepare-cleanup-fatal");
        AtomicBoolean cleaning = new AtomicBoolean();
        VirtualMachineError originalFatal = new VirtualMachineError("prepare original fatal") { };
        AssertionError cleanupFailure = new AssertionError("prepare cleanup error");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void beforeInstallTransactionPublished(Path unpublishedTransaction) {
                cleaning.set(true);
                throw originalFatal;
            }

            @Override
            void beforeManagedCleanup(Path root) {
                if (cleaning.get()) {
                    throw cleanupFailure;
                }
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();

        assertThatThrownBy(() -> installer.prepareTransaction(
                packageFile("prepare-cleanup-fatal.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload())).isSameAs(originalFatal);

        assertThat(originalFatal.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).isEmpty();
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
    }

    @Test
    @DisplayName("删除发布前清理失败不会用后续普通 Error 覆盖原 fatal")
    void removalCleanupFailureKeepsOriginalFatal() {
        Path plugins = temp.resolve("plugins-removal-cleanup-fatal");
        ExternalPluginInstaller baseline = newInstaller(plugins);
        installFully(baseline, packageFile("removal-cleanup-fatal.zip", "1.0.0"));
        baseline.close();
        AtomicBoolean cleaning = new AtomicBoolean();
        VirtualMachineError originalFatal = new VirtualMachineError("removal original fatal") { };
        AssertionError cleanupFailure = new AssertionError("removal cleanup error");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void beforeRemovalTransactionPublished(Path unpublishedTransaction) {
                cleaning.set(true);
                throw originalFatal;
            }

            @Override
            void beforeManagedCleanup(Path root) {
                if (cleaning.get()) {
                    throw cleanupFailure;
                }
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PluginRemovalAttempt attempt = new PluginRemovalAttempt("demo");

        assertThatThrownBy(() -> installer.removeInstalled(attempt)).isSameAs(originalFatal);

        assertThat(originalFatal.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).isEmpty();
        assertThat(attempt.outcome()).isEqualTo(PluginRemovalAttempt.Outcome.ROLLED_BACK);
        assertThat(installer.listInstalled()).hasSize(1);
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
    }
}
