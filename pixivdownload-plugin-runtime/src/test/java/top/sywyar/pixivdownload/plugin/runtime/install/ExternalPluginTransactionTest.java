package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
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

@DisplayName("外置插件文件事务：预校验、提交、回滚与启动恢复")
class ExternalPluginTransactionTest {

    Path temp;
    private final List<ExternalPluginInstaller> installers = new ArrayList<>();

    @BeforeEach
    void createWorkspaceTemp() throws IOException {
        temp = Path.of("target", "test-transactions", UUID.randomUUID().toString());
        Files.createDirectories(temp);
    }

    @AfterEach
    void cleanWorkspaceTemp() throws IOException {
        for (int i = installers.size() - 1; i >= 0; i--) {
            installers.get(i).close();
        }
        if (!Files.exists(temp)) {
            return;
        }
        try (var walk = Files.walk(temp)) {
            for (Path path : walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

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
    @DisplayName("新包验证后仅隔离精确替代身份并随回滚恢复其 artifact 与 provenance")
    void replacementTransactionTargetsExactRetiredIdentity() {
        Path plugins = temp.resolve("plugins-replacement");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("retired.zip", "novel-gallery", "1.0.0", null));
        installFully(installer, packageFile("third-party.zip", "novel-gallery-plus", "1.0.0", null));
        Path retired = plugins.resolve("novel-gallery-1.0.0.zip");
        Path unrelated = plugins.resolve("novel-gallery-plus-1.0.0.zip");

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("novel.zip", "novel", "1.0.0", "novel-gallery"), false,
                PluginPackageOrigin.localUpload());

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
    @DisplayName("当前 generation 无法确认清退时保留 NEW_PLACED 并由下次启动恢复旧包")
    void deferredRollbackBlocksCurrentProcessAndRecoversOnRestart() {
        Path plugins = temp.resolve("plugins-deferred-rollback");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("deferred-old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("deferred-new.zip", "2.0.0"), false,
                PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);

        installer.deferRollbackUntilRestart(
                committed, new IllegalStateException("current generation cleanup is unconfirmed"));

        assertThat(committed.recoveryBlocked()).isTrue();
        assertThat(committed.durableState())
                .isEqualTo(CommittedPluginTransaction.DurableState.NEW_PLACED);
        assertThat(installer.recoveryGateSnapshot().state())
                .isEqualTo(PluginRecoveryGateState.BLOCKED);
        assertThat(prepared.transactionDirectory()).exists();
        assertThat(plugins.resolve("demo-1.0.0.zip")).doesNotExist();
        assertThat(plugins.resolve("demo-2.0.0.zip")).exists();
        assertThatThrownBy(installer::listInstalled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery is unsafe");

        installer.close();
        ExternalPluginInstaller restarted = new ExternalPluginInstaller(plugins);
        installers.add(restarted);
        PluginTransactionRecoveryReport recovery = restarted.recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isTrue();
        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();
        assertThat(sidecar(plugins, plugins.resolve("demo-1.0.0.zip"))).exists();
        assertThat(plugins.resolve("demo-2.0.0.zip")).doesNotExist();
        assertThat(sidecar(plugins, plugins.resolve("demo-2.0.0.zip"))).doesNotExist();
        assertThat(prepared.transactionDirectory()).doesNotExist();
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

    @Test
    @DisplayName("旧版非规范文件名升级失败后按原名恢复")
    void legacyArtifactNameIsRestoredVerbatimAfterRollback() throws IOException {
        Path plugins = temp.resolve("plugins-legacy-name-rollback");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("legacy-name-v1.zip", "1.0.0"));
        Path canonical = plugins.resolve("demo-1.0.0.zip");
        Path legacy = plugins.resolve("my-plugin.zip");
        Files.move(canonical, legacy);
        Files.move(sidecar(plugins, canonical), sidecar(plugins, legacy));

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("legacy-name-v2.zip", "2.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);

        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(legacy).exists();
        assertThat(sidecar(plugins, legacy)).exists();
        assertThat(canonical).doesNotExist();
        assertThat(prepared.target()).doesNotExist();
    }

    @Test
    @DisplayName("旧版非规范文件名可按包内身份安全删除")
    void legacyArtifactNameCanBeRemoved() throws IOException {
        Path plugins = temp.resolve("plugins-legacy-name-remove");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("legacy-name-remove.zip", "1.0.0"));
        Path canonical = plugins.resolve("demo-1.0.0.zip");
        Path legacy = plugins.resolve("copied-plugin.jar");
        Files.move(canonical, legacy);
        Files.move(sidecar(plugins, canonical), sidecar(plugins, legacy));

        assertThat(installer.removeInstalled("demo")).isTrue();
        assertThat(legacy).doesNotExist();
        assertThat(sidecar(plugins, legacy)).doesNotExist();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("恢复清单累计预算首次超限后不再打开后续事务")
    void manifestBudgetStopsOpeningLaterTransactions() throws IOException {
        Path plugins = temp.resolve("plugins-manifest-budget");
        List<String> opened = new ArrayList<>();
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void beforeRecoveryManifestRead(Path manifest) {
                opened.add(manifest.getParent().getFileName().toString());
            }
        };
        installers.add(installer);
        Path staging = plugins.resolve(".staging");
        String oversizedButIndividuallyBounded =
                "format.version=invalid\npadding=" + "x".repeat(510_000);
        for (int index = 0; index < 18; index++) {
            Path transaction = staging.resolve(String.format("%02d", index));
            Files.createDirectories(transaction);
            Files.writeString(transaction.resolve("transaction.properties"),
                    oversizedButIndividuallyBounded, StandardCharsets.UTF_8);
        }

        PluginTransactionRecoveryReport report = installer.recoverPendingTransactions();

        assertThat(report.safeToScan()).isFalse();
        assertThat(opened).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 17)
                        .mapToObj(index -> String.format("%02d", index)).toList());
        assertThat(opened).doesNotContain("17");
    }

    @Test
    @DisplayName("非法 UTF-8 清单的已读字节也计入累计预算")
    void malformedManifestReadsConsumeCumulativeBudget() throws IOException {
        Path plugins = temp.resolve("plugins-malformed-manifest-budget");
        List<String> opened = new ArrayList<>();
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void beforeRecoveryManifestRead(Path manifest) {
                opened.add(manifest.getParent().getFileName().toString());
            }
        };
        installers.add(installer);
        byte[] invalidUtf8 = new byte[510_000];
        java.util.Arrays.fill(invalidUtf8, (byte) 'x');
        invalidUtf8[invalidUtf8.length - 1] = (byte) 0xC3;
        Path staging = plugins.resolve(".staging");
        for (int index = 0; index < 18; index++) {
            Path transaction = staging.resolve(String.format("%02d", index));
            Files.createDirectories(transaction);
            Files.write(transaction.resolve("transaction.properties"), invalidUtf8);
        }

        assertThat(installer.recoverPendingTransactions().safeToScan()).isFalse();

        assertThat(opened).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 17)
                        .mapToObj(index -> String.format("%02d", index)).toList());
        assertThat(opened).doesNotContain("17");
    }

    @Test
    @DisplayName("超过恢复上限的 replaces 在发布前拒绝且不封闭 gate")
    void excessiveReplacementCountIsRejectedBeforePublication() {
        Path plugins = temp.resolve("plugins-excessive-replaces");
        ExternalPluginInstaller installer = newInstaller(plugins);
        String replaces = java.util.stream.IntStream.range(0, 257)
                .mapToObj(index -> "replaced-" + index)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("excessive-replaces.zip", "demo", "1.0.0", replaces),
                false, PluginPackageOrigin.localUpload());

        assertThat(prepared.readyToCommit()).isFalse();
        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("超过恢复字节上限的自生成清单在发布前拒绝")
    void oversizedGeneratedManifestIsRejectedBeforePublication() {
        Path plugins = temp.resolve("plugins-oversized-generated-manifest");
        ExternalPluginInstaller installer = newInstaller(plugins);

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("oversized-generated-manifest.zip", "demo", "1.0.0",
                        randomReplacementIds(256, 2_600)),
                false, PluginPackageOrigin.localUpload());

        assertThat(prepared.readyToCommit()).isFalse();
        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(prepared.result().messages())
                .anyMatch(message -> message.contains("manifest exceeds the supported size"));
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("超过恢复上限的旧 artifact 集在发布前拒绝")
    void excessiveBackupCountIsRejectedBeforePublication() throws IOException {
        Path plugins = temp.resolve("plugins-excessive-backups");
        Files.createDirectories(plugins);
        Path template = packageFile("backup-template.zip", "1.0.0");
        for (int index = 0; index < 257; index++) {
            Files.copy(template, plugins.resolve("old-copy-" + index + ".zip"));
        }
        ExternalPluginInstaller installer = newInstaller(plugins);

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("backup-replacement.zip", "2.0.0"),
                false, PluginPackageOrigin.localUpload());

        assertThat(prepared.readyToCommit()).isFalse();
        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.FAILED);
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("恢复报告拒绝 null failures，防止调用方误把报告构造错误当成成功")
    void recoveryReportRejectsNullFailures() {
        assertThatThrownBy(() -> new PluginTransactionRecoveryReport(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("failures");
    }

    @Test
    @DisplayName("ACTIVATED target 摘要漂移时保留 transaction 与旧包 backup 并 fail-closed")
    void activatedTargetDigestMismatchPreservesRecoveryEvidence() throws IOException {
        Path plugins = temp.resolve("plugins-activated-corrupt");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("activated-old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("activated-new.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.markActivated(committed);
        Files.writeString(prepared.target(), "corrupt", StandardCharsets.UTF_8);

        installer.close();
        PluginTransactionRecoveryReport recovery = newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isFalse();
        assertThat(prepared.transactionDirectory()).exists();
        assertThat(committed.backups().get(0).backup()).exists();
        assertThat(Files.readString(prepared.target(), StandardCharsets.UTF_8)).isEqualTo("corrupt");
    }

    @Test
    @DisplayName("ACTIVATED target provenance 缺失时不清理 transaction 或 backup")
    void activatedMissingProvenancePreservesRecoveryEvidence() throws IOException {
        Path plugins = temp.resolve("plugins-activated-no-provenance");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("activated-sidecar-old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("activated-sidecar-new.zip", "2.0.0"), false,
                PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.markActivated(committed);
        Files.delete(sidecar(plugins, prepared.target()));

        installer.close();
        PluginTransactionRecoveryReport recovery = newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isFalse();
        assertThat(prepared.target()).exists();
        assertThat(prepared.transactionDirectory()).exists();
        assertThat(committed.backups().get(0).backup()).exists();
    }

    @Test
    @DisplayName("manifest 不能借 target 字段删除根目录内另一插件 artifact")
    void manifestCannotRetargetDeletionToUnrelatedRootArtifact() throws IOException {
        Path plugins = temp.resolve("plugins-retarget");
        ExternalPluginInstaller installer = newInstaller(plugins);
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("retarget-new.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        Path victimPackage = packageFile("victim-source.zip", "victim", "1.0.0", null);
        installFully(installer, victimPackage);
        Path victim = plugins.resolve("victim-1.0.0.zip");
        Properties properties = readManifest(prepared.transactionDirectory());
        properties.setProperty("target", victim.toAbsolutePath().normalize().toString());
        writeManifest(prepared.transactionDirectory(), properties);

        installer.close();
        PluginTransactionRecoveryReport recovery = newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isFalse();
        assertThat(victim).exists();
        assertThat(prepared.transactionDirectory()).exists();
    }

    @Test
    @DisplayName("PREPARED backup 只移动了 sidecar 时恢复可收敛拆分状态")
    void partialBackupSidecarMoveIsRecovered() throws IOException {
        Path plugins = temp.resolve("plugins-partial-backup");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("partial-old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("partial-new.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        CommittedPluginTransaction.BackupArtifact old = committed.backups().get(0);

        // 还原 PREPARED 所允许的两个崩溃接缝：new 回到 staged；旧 artifact 已在 origin，sidecar 仍在 backup。
        Files.move(sidecar(plugins, prepared.target()), sidecar(plugins, prepared.stagedArtifact()));
        Files.move(prepared.target(), prepared.stagedArtifact());
        Files.move(old.backup(), old.origin());
        Properties properties = readManifest(prepared.transactionDirectory());
        properties.setProperty("state", "PREPARED");
        writeManifest(prepared.transactionDirectory(), properties);

        installer.close();
        PluginTransactionRecoveryReport recovery = newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isTrue();
        assertThat(old.origin()).exists();
        assertThat(sidecar(plugins, old.origin())).exists();
        assertThat(prepared.target()).doesNotExist();
        assertThat(prepared.transactionDirectory()).doesNotExist();
    }

    @Test
    @DisplayName("两个合法事务声称同一 target 时都保留且在任何恢复写入前阻断")
    void crossTransactionTargetConflictFailsBeforeMutation() {
        Path plugins = temp.resolve("plugins-cross-transaction");
        ExternalPluginInstaller installer = newInstaller(plugins);
        Path candidate = packageFile("cross.zip", "2.0.0");
        PreparedPluginTransaction first = installer.prepareTransaction(
                candidate, false, PluginPackageOrigin.localUpload());
        PreparedPluginTransaction second = installer.prepareTransaction(
                candidate, false, PluginPackageOrigin.localUpload());

        installer.close();
        PluginTransactionRecoveryReport recovery = newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isFalse();
        assertThat(recovery.failures())
                .extracting(PluginTransactionRecoveryReport.Failure::kind)
                .containsOnly(PluginTransactionRecoveryReport.FailureKind.UNSAFE_PATH);
        assertThat(first.stagedArtifact()).exists();
        assertThat(second.stagedArtifact()).exists();
        assertThat(first.transactionDirectory()).exists();
        assertThat(second.transactionDirectory()).exists();
    }

    @Test
    @DisplayName("超大 manifest 在读取前拒绝并原样保留事务")
    void oversizedManifestIsRejectedBeforeRead() throws IOException {
        Path plugins = temp.resolve("plugins-oversized-manifest");
        Path transaction = plugins.resolve(".staging").resolve("oversized");
        Files.createDirectories(transaction);
        Path manifest = transaction.resolve("transaction.properties");
        Files.write(manifest, new byte[2 * 1024 * 1024 + 1]);

        PluginTransactionRecoveryReport recovery =
                newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isFalse();
        assertThat(recovery.failures())
                .extracting(PluginTransactionRecoveryReport.Failure::kind)
                .containsExactly(PluginTransactionRecoveryReport.FailureKind.INVALID_MANIFEST);
        assertThat(manifest).hasSize(2 * 1024 * 1024 + 1L);
    }

    @Test
    @DisplayName("plugins root 是符号链接时恢复在枚举 staging 前 fail-closed")
    void symbolicPluginRootIsRejectedBeforeEnumeration() throws IOException {
        Path actual = temp.resolve("actual-plugins");
        Files.createDirectories(actual);
        Path linked = temp.resolve("linked-plugins");
        try {
            Files.createSymbolicLink(linked, actual);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("当前文件系统不能创建符号链接: " + e.getMessage());
        }

        PluginTransactionRecoveryReport recovery =
                newInstaller(linked).recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isFalse();
        assertThat(recovery.failures())
                .extracting(PluginTransactionRecoveryReport.Failure::kind)
                .containsExactly(PluginTransactionRecoveryReport.FailureKind.STAGING_ROOT_UNSAFE);
    }

    @Test
    @DisplayName("缺失 manifest 的非空事务目录保留原文件并阻止扫描")
    void nonEmptyTransactionWithoutManifestIsPreserved() throws IOException {
        Path plugins = temp.resolve("plugins-missing-manifest");
        Path transaction = plugins.resolve(".staging").resolve("orphaned");
        Path retained = transaction.resolve("removed").resolve("0-demo-1.0.0.jar");
        Files.createDirectories(retained.getParent());
        Files.writeString(retained, "only-copy", StandardCharsets.UTF_8);

        PluginTransactionRecoveryReport recovery =
                newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.safeToScan()).isFalse();
        assertThat(recovery.failures())
                .extracting(PluginTransactionRecoveryReport.Failure::kind)
                .containsExactly(PluginTransactionRecoveryReport.FailureKind.MISSING_MANIFEST);
        assertThat(Files.readString(retained, StandardCharsets.UTF_8)).isEqualTo("only-copy");
        assertThat(transaction).exists();
    }

    @Test
    @DisplayName("manifest 的 backup 路径越出事务 removed 根时不移动任何文件")
    void backupOutsideTransactionRootIsRejectedBeforeMutation() throws IOException {
        Path plugins = temp.resolve("plugins-unsafe-backup").toAbsolutePath().normalize();
        Path transaction = plugins.resolve(".staging").resolve("unsafe-backup");
        Path outsideBackup = temp.resolve("outside-backup.jar").toAbsolutePath().normalize();
        Path origin = plugins.resolve("demo-1.0.0.jar");
        Files.writeString(outsideBackup, "backup", StandardCharsets.UTF_8);
        Properties manifest = manifest("unsafe-backup", "OLD_ISOLATED", "demo", "2.0.0",
                plugins.resolve("demo-2.0.0.jar"), 1);
        manifest.setProperty("backup.0.origin", origin.toString());
        manifest.setProperty("backup.0.path", outsideBackup.toString());
        writeManifest(transaction, manifest);

        PluginTransactionRecoveryReport recovery =
                newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.failures())
                .extracting(PluginTransactionRecoveryReport.Failure::kind)
                .containsExactly(PluginTransactionRecoveryReport.FailureKind.UNSAFE_PATH);
        assertThat(Files.readString(outsideBackup, StandardCharsets.UTF_8)).isEqualTo("backup");
        assertThat(origin).doesNotExist();
        assertThat(transaction).exists();
    }

    @Test
    @DisplayName("provenance 预期根不是目录时先阻止恢复且不删除 target")
    void unsafeProvenanceRootPreventsTargetDeletion() throws IOException {
        Path plugins = temp.resolve("plugins-unsafe-provenance").toAbsolutePath().normalize();
        Files.createDirectories(plugins);
        Path target = plugins.resolve("demo-2.0.0.jar");
        Files.writeString(target, "new", StandardCharsets.UTF_8);
        Files.writeString(plugins.resolve("provenance"), "not-a-directory", StandardCharsets.UTF_8);
        Path transaction = plugins.resolve(".staging").resolve("unsafe-provenance");
        writeManifest(transaction, manifest("unsafe-provenance", "NEW_PLACED", "demo", "2.0.0", target, 0));

        PluginTransactionRecoveryReport recovery =
                newInstaller(plugins).recoverPendingTransactions();

        assertThat(recovery.failures())
                .extracting(PluginTransactionRecoveryReport.Failure::kind)
                .containsExactly(PluginTransactionRecoveryReport.FailureKind.UNSAFE_PATH);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("new");
        assertThat(transaction).exists();
    }

    @Test
    @DisplayName("提交窗口前旧 artifact 摘要变化时拒绝过期事务")
    void stalePreparedTransactionIsRejectedBeforeCommit() throws IOException {
        Path plugins = temp.resolve("plugins-stale");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("stale-old.zip", "1.0.0"));
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("stale-next.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        Path current = plugins.resolve("demo-1.0.0.zip");
        Files.writeString(current, "tampered", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> installer.verifyCurrentArtifacts(prepared))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to verify prepared plugin transaction");
        assertThat(current).exists();
        assertThat(prepared.target()).doesNotExist();
        assertThat(prepared.transactionDirectory()).exists();
    }

    @Test
    @DisplayName("删除已安装插件通过隔离事务完成并清理暂存目录")
    void removalUsesRecoverableTransaction() {
        Path plugins = temp.resolve("plugins-remove");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("remove.zip", "1.0.0"));

        assertThat(installer.removeInstalled("demo")).isTrue();
        assertThat(installer.listInstalled()).isEmpty();
        assertThat(sidecar(plugins, plugins.resolve("demo-1.0.0.zip"))).doesNotExist();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("损坏的旧 provenance 可被新包替换且回滚时原样恢复")
    void malformedOldProvenanceCanBeReplacedAndRestoredOpaqueOnRollback() throws IOException {
        Path plugins = temp.resolve("plugins-repair-malformed-provenance");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("repair-old.zip", "1.0.0"));
        Path oldArtifact = plugins.resolve("demo-1.0.0.zip");
        Path oldSidecar = sidecar(plugins, oldArtifact);
        String malformed = "formatVersion=broken\n";
        Files.writeString(oldSidecar, malformed, StandardCharsets.UTF_8);

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("repair-new.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());
        assertThat(prepared.readyToCommit()).isTrue();
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);

        assertThat(installer.rollbackTransaction(committed)).isTrue();
        assertThat(oldArtifact).exists();
        assertThat(Files.readString(oldSidecar, StandardCharsets.UTF_8)).isEqualTo(malformed);
        assertThat(prepared.target()).doesNotExist();
    }

    @Test
    @DisplayName("损坏 provenance 不阻塞使用可恢复事务删除旧包")
    void malformedProvenanceDoesNotBlockTransactionalRemoval() throws IOException {
        Path plugins = temp.resolve("plugins-remove-malformed-provenance");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("remove-malformed-provenance.zip", "1.0.0"));
        Path artifact = plugins.resolve("demo-1.0.0.zip");
        Files.writeString(sidecar(plugins, artifact), "formatVersion=broken\n", StandardCharsets.UTF_8);

        assertThat(installer.removeInstalled("demo")).isTrue();

        assertThat(artifact).doesNotExist();
        assertThat(sidecar(plugins, artifact)).doesNotExist();
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.SAFE);
    }

    @Test
    @DisplayName("provenance 最终写入 plugins/provenance，旧根目录 sidecar 读取后迁移")
    void provenanceLivesUnderProvenanceDirectoryAndMigratesLegacySidecar() throws IOException {
        Path plugins = temp.resolve("plugins-provenance");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("provenance.zip", "1.0.0"));
        Path artifact = plugins.resolve("demo-1.0.0.zip");
        PluginProvenanceStore store = new PluginProvenanceStore(plugins);
        Path central = store.sidecarPath(artifact);
        Path legacy = legacySidecar(artifact);

        assertThat(central).isEqualTo(plugins.resolve("provenance")
                .resolve("demo-1.0.0.zip.pixiv-plugin-provenance"));
        assertThat(central).exists();
        assertThat(legacy).doesNotExist();

        Files.move(central, legacy);
        assertThat(store.read(artifact)).isPresent();

        assertThat(central).exists();
        assertThat(legacy).doesNotExist();
    }

    private ExternalPluginInstaller newInstaller(Path plugins) {
        ExternalPluginInstaller created = new ExternalPluginInstaller(plugins);
        installers.add(created);
        created.recoverPendingTransactions();
        return created;
    }

    private void assertCommitRecoveryPrimary(
            Path plugins, Error originalFailure, Error recoveryFailure, Error expectedPrimary) {
        AtomicBoolean recovering = new AtomicBoolean();
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterOldArtifactsIsolated(Path transaction) {
                recovering.set(true);
                throw originalFailure;
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
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile(plugins.getFileName() + ".zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());

        assertThatThrownBy(() -> installer.commitTransaction(prepared)).isSameAs(expectedPrimary);

        Error replaced = expectedPrimary == originalFailure ? recoveryFailure : originalFailure;
        assertThat(expectedPrimary.getSuppressed()).containsExactly(replaced);
        assertThat(replaced.getSuppressed()).isEmpty();
        assertThat(prepared.commitState()).isEqualTo(PreparedPluginTransaction.CommitState.UNSAFE);
        assertThat(installer.recoveryGateSnapshot().state()).isEqualTo(PluginRecoveryGateState.BLOCKED);
    }

    private void assertCompletionRetirementPrimary(
            Path plugins, Throwable persistenceFailure, Error retirementFailure, Throwable expectedPrimary) {
        AtomicBoolean retiring = new AtomicBoolean();
        ExternalPluginInstaller installer = new ExternalPluginInstaller(plugins) {
            @Override
            void afterCommittedManifestPersisted(Path transaction) {
                retiring.set(true);
                if (persistenceFailure instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) persistenceFailure;
            }

            @Override
            void beforeManagedCleanup(Path root) {
                if (retiring.get()) {
                    throw retirementFailure;
                }
            }
        };
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile(plugins.getFileName() + ".zip", "1.0.0"),
                false, PluginPackageOrigin.localUpload());
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.markActivated(committed);

        assertThatThrownBy(() -> installer.completeTransaction(committed)).isSameAs(expectedPrimary);

        Throwable replaced = expectedPrimary == persistenceFailure ? retirementFailure : persistenceFailure;
        assertThat(expectedPrimary.getSuppressed()).containsExactly(replaced);
        assertThat(replaced.getSuppressed()).isEmpty();
        assertThat(committed.durableState())
                .isEqualTo(CommittedPluginTransaction.DurableState.RETIRED);
        assertThat(prepared.transactionDirectory()).doesNotExist();
        assertThat(prepared.target()).exists();
    }

    private static PluginInstallResult installFully(ExternalPluginInstaller installer, Path packagePath) {
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packagePath, false, PluginPackageOrigin.localUpload());
        if (!prepared.readyToCommit()) {
            return prepared.result();
        }
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.verifyCommittedTarget(committed);
        installer.markActivated(committed);
        installer.completeTransaction(committed);
        return prepared.result();
    }

    private Path packageFile(String name, String version) {
        return PluginPackageFixtures.explodedZip(temp.resolve(name), "demo", version, "1.0", "demo.Plugin");
    }

    private Path packageFile(String name, String id, String version, String replaces) {
        String properties = PluginPackageFixtures.pluginProperties(id, version, "1.0", "demo.Plugin");
        if (replaces != null) {
            properties += "pixiv.replaces=" + replaces + "\n";
        }
        Path file = temp.resolve(name);
        PluginPackageFixtures.writeZip(file, java.util.Map.of(
                PluginPackageFixtures.PLUGIN_PROPERTIES, PluginPackageFixtures.bytes(properties),
                "classes/Marker.class", PluginPackageFixtures.bytes("marker")));
        return file;
    }

    private static String randomReplacementIds(int count, int idLength) {
        Random random = new Random(0x50_49_58_49_56L);
        StringBuilder result = new StringBuilder(count * (idLength + 1));
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append('r');
            for (int character = 1; character < idLength; character++) {
                result.append((char) ('a' + random.nextInt(26)));
            }
        }
        return result.toString();
    }

    private static Properties manifest(String transactionId, String state, String packageId, String version,
                                       Path target, int backupCount) {
        Properties properties = new Properties();
        properties.setProperty("format.version", "1");
        properties.setProperty("transaction.id", transactionId);
        properties.setProperty("operation", "INSTALL");
        properties.setProperty("state", state);
        properties.setProperty("package.id", packageId);
        properties.setProperty("version", version);
        properties.setProperty("target", target == null ? "" : target.toAbsolutePath().normalize().toString());
        Path staged = target == null ? Path.of("") : target.toAbsolutePath().normalize().getParent()
                .resolve(".staging").resolve(transactionId).resolve("new").resolve(target.getFileName());
        properties.setProperty("staged", target == null ? "" : staged.toString());
        expectedArtifact(properties, "artifact", packageId, version);
        properties.setProperty("replaces.count", "0");
        properties.setProperty("backup.count", Integer.toString(backupCount));
        for (int i = 0; i < backupCount; i++) {
            expectedArtifact(properties, "backup." + i, packageId, "1.0.0");
        }
        return properties;
    }

    private static void expectedArtifact(Properties properties, String prefix, String id, String version) {
        properties.setProperty(prefix + ".id", id);
        properties.setProperty(prefix + ".version", version);
        properties.setProperty(prefix + ".size", "1");
        properties.setProperty(prefix + ".sha256", "0".repeat(64));
        properties.setProperty(prefix + ".sidecar.sha256", "0".repeat(64));
    }

    private static Properties readManifest(Path transaction) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(transaction.resolve("transaction.properties"),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void writeManifest(Path transaction, Properties properties) throws IOException {
        Files.createDirectories(transaction);
        try (var writer = Files.newBufferedWriter(transaction.resolve("transaction.properties"),
                StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
    }

    private static Path sidecar(Path plugins, Path artifact) {
        return new PluginProvenanceStore(plugins).sidecarPath(artifact);
    }

    private static Path legacySidecar(Path artifact) {
        return artifact.resolveSibling(artifact.getFileName() + ".pixiv-plugin-provenance");
    }
}
