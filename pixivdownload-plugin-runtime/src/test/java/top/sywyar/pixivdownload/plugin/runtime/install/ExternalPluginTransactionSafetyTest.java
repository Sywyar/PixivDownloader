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

@DisplayName("外置插件事务：清单预算与路径安全")
class ExternalPluginTransactionSafetyTest extends ExternalPluginTransactionTestSupport {

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
}
