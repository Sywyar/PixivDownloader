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

@DisplayName("外置插件事务：发布提交与耐久状态")
class ExternalPluginTransactionCommitTest extends ExternalPluginTransactionTestSupport {

    @Test
    @DisplayName("安装事务发布后的未检查异常会封闭当前会话")
    void uncheckedFailureAfterInstallPublicationBlocksCurrentSession() {
        Path plugins = temp.resolve("plugins-published-install-runtime-failure");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterInstallTransactionPublished(Path transaction) {
                throw new UncheckedIOException(new IOException("simulated published install failure"));
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("published-install-runtime-failure.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());

        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(installer.recoveryGateSnapshot().state())
                .isEqualTo(PluginRecoveryGateState.BLOCKED);
        assertThat(installer.recoveryGateSnapshot().report().failures())
                .extracting(PluginTransactionRecoveryReport.Failure::kind)
                .containsExactly(PluginTransactionRecoveryReport.FailureKind.RECOVERY_FAILED);
        assertThat(plugins.resolve(".staging")).isDirectory();
        assertThatThrownBy(installer::listInstalled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plugin transaction recovery is unsafe")
                .hasMessageContaining("refusing to list installed plugins");
    }

    @Test
    @DisplayName("安装事务发布后的 Error 会先封闭恢复门再原样抛出")
    void errorAfterInstallPublicationBlocksBeforeRethrow() {
        Path plugins = temp.resolve("plugins-published-install-error");
        AssertionError failure = new AssertionError("simulated published install error");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterInstallTransactionPublished(Path transaction) {
                throw failure;
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();

        assertThatThrownBy(() -> installer.prepareTransaction(
                packageFile("published-install-error.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload())).isSameAs(failure);

        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
        assertThat(plugins.resolve(".staging")).isDirectory();
    }

    @Test
    @DisplayName("旧包隔离后的 Error 会恢复旧态且重复 discard 保持幂等")
    void errorAfterOldIsolationRecoversAndRepeatedDiscardIsSafe() {
        Path plugins = temp.resolve("plugins-old-isolated-error");
        ExternalPluginInstaller setup = newInstaller(plugins);
        installFully(setup, packageFile("old-isolated-error-v1.zip", "1.0.0"));
        setup.close();
        AssertionError failure = new AssertionError("simulated old-isolated error");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterOldArtifactsIsolated(Path transaction) {
                throw failure;
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("old-isolated-error-v2.zip", "2.0.0"),
                false, PluginPackageOrigin.localUpload());

        assertThatThrownBy(() -> installer.commitTransaction(prepared)).isSameAs(failure);
        installer.discardPrepared(prepared);

        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();
        assertThat(plugins.resolve("demo-2.0.0.zip")).doesNotExist();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("删除事务发布后的未检查异常会先完成可验证恢复")
    void uncheckedFailureAfterRemovalPublicationRecoversBeforeReturning() {
        Path plugins = temp.resolve("plugins-published-removal-runtime-failure");
        ExternalPluginInstaller setup = new ExternalPluginInstaller(plugins);
        assertThat(setup.recoverPendingTransactions().safeToScan()).isTrue();
        installFully(setup, packageFile("published-removal-runtime-failure.zip", "1.0.0"));
        setup.close();

        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterRemovalTransactionPublished(Path transaction) {
                throw new UncheckedIOException(new IOException("simulated published removal failure"));
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();

        assertThatThrownBy(() -> installer.removeInstalled("demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to remove installed plugin demo");

        assertThat(installer.recoverySafeForRuntime()).isTrue();
        assertThat(installer.listInstalled()).extracting(InstalledPlugin::id).containsExactly("demo");
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("删除完成清单已落盘后报错仍返回持久删除成功")
    void removalFailureAfterCommittedManifestKeepsDurableRemoval() {
        Path plugins = temp.resolve("plugins-removal-after-commit-failure");
        ExternalPluginInstaller setup = new ExternalPluginInstaller(plugins);
        assertThat(setup.recoverPendingTransactions().safeToScan()).isTrue();
        installFully(setup, packageFile("removal-after-commit-failure.zip", "1.0.0"));
        setup.close();

        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterRemovalCommittedManifestPersisted(Path transaction) throws IOException {
                throw new IOException("simulated post-removal commit failure");
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();

        assertThat(installer.removeInstalled("demo")).isTrue();

        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(installer.listInstalled()).isEmpty();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("删除完成清单落盘后的普通 Error 保留持久删除")
    void removalErrorAfterCommittedManifestKeepsDurableRemoval() {
        Path plugins = temp.resolve("plugins-removal-after-commit-error");
        ExternalPluginInstaller setup = newInstaller(plugins);
        installFully(setup, packageFile("removal-after-commit-error.zip", "1.0.0"));
        setup.close();

        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterRemovalCommittedManifestPersisted(Path transaction) {
                throw new AssertionError("simulated post-removal commit error");
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();

        assertThat(installer.removeInstalled("demo")).isTrue();
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(installer.listInstalled()).isEmpty();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("激活清单落盘前失败保持 gate 可回滚")
    void activationPersistenceFailureBeforeAtomicWriteRemainsRollbackable() throws IOException {
        Path plugins = temp.resolve("plugins-activation-before-persist-failure");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void beforeActivationManifestPersisted(Path transaction) throws IOException {
                throw new IOException("simulated activation persistence failure");
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("activation-before-persist-failure.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.verifyCommittedTarget(committed);

        assertThatThrownBy(() -> installer.markActivated(committed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to persist plugin activation");

        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(readManifest(prepared.transactionDirectory()).getProperty("state"))
                .isEqualTo("NEW_PLACED");
        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(prepared.target()).doesNotExist();
        assertThat(prepared.transactionDirectory()).doesNotExist();
    }

    @Test
    @DisplayName("激活清单已落盘后报错保留已验证的新代")
    void activationPersistenceFailureAfterAtomicWriteKeepsDurableGeneration() throws IOException {
        Path plugins = temp.resolve("plugins-activation-after-persist-failure");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterActivationManifestPersisted(Path transaction) throws IOException {
                throw new IOException("simulated post-activation persistence failure");
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("activation-after-persist-failure.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.verifyCommittedTarget(committed);

        installer.markActivated(committed);

        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(readManifest(prepared.transactionDirectory()).getProperty("state"))
                .isEqualTo("ACTIVATED");
        assertThat(prepared.target()).exists();
        installer.completeTransaction(committed);
        assertThat(prepared.transactionDirectory()).doesNotExist();
        assertThat(prepared.target()).exists();
    }

    @Test
    @DisplayName("完成清单已落盘后报错保留新代并当场退役清单")
    void completionFailureAfterAtomicWriteKeepsCommittedGeneration() throws IOException {
        Path plugins = temp.resolve("plugins-completion-after-persist-failure");
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterCommittedManifestPersisted(Path transaction) throws IOException {
                throw new IOException("simulated post-commit persistence failure");
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("completion-after-persist-failure.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.verifyCommittedTarget(committed);
        installer.markActivated(committed);

        installer.completeTransaction(committed);

        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(committed.durableState())
                .isEqualTo(CommittedPluginTransaction.DurableState.RETIRED);
        assertThat(prepared.transactionDirectory()).doesNotExist();
        assertThat(prepared.target()).exists();

        installer.close();
        ExternalPluginInstaller restarted = new ExternalPluginInstaller(plugins);
        installers.add(restarted);
        assertThat(restarted.recoverPendingTransactions().safeToScan()).isTrue();
        assertThat(prepared.transactionDirectory()).doesNotExist();
        assertThat(prepared.target()).exists();
    }

    @Test
    @DisplayName("COMMITTED 清单无法原子退役时立即封闭后续写入")
    void committedRetirementFailureBlocksFurtherMutations() throws IOException {
        Path plugins = temp.resolve("plugins-committed-retirement-blocked");
        ExternalPluginInstaller installer = newInstaller(plugins);
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("committed-retirement-blocked.zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.verifyCommittedTarget(committed);
        installer.markActivated(committed);
        Path finalizationRoot = plugins.resolve(".transaction-cleanup");
        Files.writeString(finalizationRoot, "blocks atomic retirement", StandardCharsets.UTF_8);

        installer.completeTransaction(committed);

        assertThat(committed.durableState())
                .isEqualTo(CommittedPluginTransaction.DurableState.COMMITTED);
        assertThat(committed.recoveryBlocked()).isTrue();
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
        assertThat(prepared.transactionDirectory()).exists();
        assertThatThrownBy(() -> installer.removeInstalled("demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery is unsafe");

        installer.close();
        Files.delete(finalizationRoot);
        ExternalPluginInstaller restarted = new ExternalPluginInstaller(plugins);
        installers.add(restarted);
        assertThat(restarted.recoverPendingTransactions().safeToScan()).isTrue();
        assertThat(prepared.transactionDirectory()).doesNotExist();
        assertThat(prepared.target()).exists();
    }
}
