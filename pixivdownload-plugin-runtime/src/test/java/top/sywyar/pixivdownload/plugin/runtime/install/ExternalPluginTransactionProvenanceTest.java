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
import java.util.Map;
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
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.RepositoryIdentityMigrationAuthorization;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;

@DisplayName("外置插件事务：旧制品与来源记录")
class ExternalPluginTransactionProvenanceTest extends ExternalPluginTransactionTestSupport {

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
    @DisplayName("损坏的旧 provenance 无法证明所有权时拒绝替换")
    void malformedOldProvenanceRejectsReplacement() throws IOException {
        Path plugins = temp.resolve("plugins-repair-malformed-provenance");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("repair-old.zip", "1.0.0"));
        Path oldArtifact = plugins.resolve("demo-1.0.0.zip");
        Path oldSidecar = sidecar(plugins, oldArtifact);
        String malformed = "formatVersion=broken\n";
        Files.writeString(oldSidecar, malformed, StandardCharsets.UTF_8);

        PreparedPluginTransaction prepared = installer.prepareTransaction(
                packageFile("repair-new.zip", "2.0.0"), false, PluginPackageOrigin.localUpload());

        assertThat(prepared.readyToCommit()).isFalse();
        assertThat(prepared.result().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(oldArtifact).exists();
        assertThat(Files.readString(oldSidecar, StandardCharsets.UTF_8)).isEqualTo(malformed);
        assertThat(plugins.resolve("demo-2.0.0.zip")).doesNotExist();
        assertThat(plugins.resolve(".staging")).doesNotExist();
    }

    @Test
    @DisplayName("同一签名发布者与仓库可升级，仓库切换则 fail-closed")
    void catalogIdentityFreezesRepository() throws IOException {
        Path plugins = temp.resolve("plugins-catalog-owner");
        PluginSigningTestSupport signing = PluginSigningTestSupport.create();
        ExternalPluginInstaller installer = signedInstaller(plugins, signing.verifier());
        Path oldPackage = packageFile("catalog-owner-old.zip", "1.0.0");
        installFully(installer, oldPackage,
                signing.confirmed(signing.originFor("repository-a", oldPackage, "demo", "1.0.0")));
        Path candidate = packageFile("catalog-owner-new.zip", "2.0.0");

        PreparedPluginTransaction rejected = installer.prepareTransaction(candidate, false,
                signing.originFor("repository-b", candidate, "demo", "2.0.0"));

        assertThat(rejected.readyToCommit()).isFalse();
        assertThat(rejected.result().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();
        assertThat(plugins.resolve(".staging")).doesNotExist();

        PluginInstallResult upgraded = installFully(installer, candidate,
                signing.originFor("repository-a", candidate, "demo", "2.0.0"));
        assertThat(upgraded.outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
    }

    @Test
    @DisplayName("已信任的新密钥也不能静默接管既有插件身份")
    void catalogIdentityRejectsUnapprovedKeyRotation() throws IOException {
        Path plugins = temp.resolve("plugins-key-rotation");
        PluginSigningTestSupport oldSigner = PluginSigningTestSupport.create(
                "old-key", "Test Publisher", false);
        PluginSigningTestSupport newSigner = PluginSigningTestSupport.create(
                "new-key", "Test Publisher", false);
        ExternalPluginInstaller installer = signedInstaller(
                plugins, PluginSigningTestSupport.verifierFor(oldSigner, newSigner));
        Path oldPackage = packageFile("key-old.zip", "1.0.0");
        installFully(installer, oldPackage,
                oldSigner.confirmed(oldSigner.originFor("repository", oldPackage, "demo", "1.0.0")));
        Path candidate = packageFile("key-new.zip", "2.0.0");

        PreparedPluginTransaction rejected = installer.prepareTransaction(candidate, false,
                newSigner.originFor("repository", candidate, "demo", "2.0.0"));

        assertThat(rejected.readyToCommit()).isFalse();
        assertThat(rejected.result().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();
        assertThat(plugins.resolve("demo-2.0.0.zip")).doesNotExist();
    }

    @Test
    @DisplayName("旧 key 授权身份迁移后仍需确认新发布者")
    void catalogIdentityAcceptsAuthorizedMigration() throws IOException {
        Path plugins = temp.resolve("plugins-authorized-identity-migration");
        PluginSigningTestSupport oldSigner = PluginSigningTestSupport.create(
                "old-key", "Old Publisher", false);
        PluginSigningTestSupport newSigner = PluginSigningTestSupport.create(
                "new-key", "New Publisher", false);
        ExternalPluginInstaller installer = signedInstaller(
                plugins, PluginSigningTestSupport.verifierFor(oldSigner, newSigner));
        Path oldPackage = packageFile("migration-old.zip", "1.0.0");
        installFully(installer, oldPackage,
                oldSigner.confirmed(oldSigner.originFor(
                        "old-repository", oldPackage, "demo", "1.0.0")));
        Path candidate = packageFile("migration-new.zip", "2.0.0");
        var authorization = oldSigner.identityMigrationSignature(
                "demo", "old-repository", "demo", "new-repository", newSigner, candidate, "2.0.0");
        PluginPackageOrigin migrationOrigin = newSigner.originFor(
                "new-repository", candidate, "demo", "2.0.0", Map.of("demo", authorization));

        PluginInstallResult pending = installFully(installer, candidate, migrationOrigin);
        assertThat(pending.outcome()).isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);
        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();

        PluginInstallResult upgraded = installFully(
                installer, candidate, newSigner.confirmed(migrationOrigin));

        assertThat(upgraded.outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
        assertThat(plugins.resolve("demo-1.0.0.zip")).doesNotExist();
        assertThat(plugins.resolve("demo-2.0.0.zip")).exists();
        var provenance = new PluginProvenanceStore(plugins)
                .read(plugins.resolve("demo-2.0.0.zip")).orElseThrow();
        assertThat(provenance.repositoryId()).isEqualTo("new-repository");
        assertThat(provenance.keyId()).isEqualTo("new-key");
        assertThat(provenance.publisher()).isEqualTo("New Publisher");
    }

    @Test
    @DisplayName("旧 key 不可用时仓库根迁移声明必须经管理员显式确认")
    void repositoryRootMigrationRequiresExplicitConfirmation() throws IOException {
        Path plugins = temp.resolve("plugins-repository-root-identity-migration");
        PluginSigningTestSupport oldSigner = PluginSigningTestSupport.create(
                "old-key", "Old Publisher", false);
        PluginSigningTestSupport newSigner = PluginSigningTestSupport.create(
                "new-key", "New Publisher", false);
        PluginSigningTestSupport repositoryRoot = PluginSigningTestSupport.create(
                "repository-root", "Repository Operator", false);
        ExternalPluginInstaller installer = signedInstaller(
                plugins, PluginSigningTestSupport.verifierFor(oldSigner, newSigner, repositoryRoot));
        Path oldPackage = packageFile("repository-migration-old.zip", "1.0.0");
        installFully(installer, oldPackage,
                oldSigner.confirmed(oldSigner.originFor(
                        "old-repository", oldPackage, "demo", "1.0.0")));
        installer.updateVerifier(new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(
                oldSigner.trustedKey(TrustedPluginKey.State.RETIRED),
                newSigner.trustedKey(TrustedPluginKey.State.ACTIVE),
                repositoryRoot.trustedKey(TrustedPluginKey.State.ACTIVE)))));
        Path candidate = packageFile("repository-migration-new.zip", "2.0.0");
        RepositoryIdentityMigrationAuthorization authorization =
                repositoryRoot.repositoryIdentityMigrationAuthorization(
                        RepositoryIdentityMigrationAuthorization.KEY_UNAVAILABLE,
                        "demo",
                        "old-repository",
                        oldSigner,
                        "demo",
                        "new-repository",
                        newSigner,
                        candidate,
                        "2.0.0");

        PreparedPluginTransaction confirmationRequired = installer.prepareTransaction(
                candidate,
                false,
                newSigner.originFor(
                        "new-repository",
                        candidate,
                        "demo",
                        "2.0.0",
                        Map.of(),
                        Map.of("demo", authorization),
                        false));

        assertThat(confirmationRequired.readyToCommit()).isFalse();
        assertThat(confirmationRequired.result().outcome())
                .isEqualTo(PluginInstallOutcome.REJECTED_IDENTITY_CONFIRMATION_REQUIRED);
        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();
        assertThat(plugins.resolve("demo-2.0.0.zip")).doesNotExist();

        PluginPackageOrigin identityConfirmedMigration = newSigner.originFor(
                "new-repository",
                candidate,
                "demo",
                "2.0.0",
                Map.of(),
                Map.of("demo", authorization),
                true);
        PluginInstallResult trustConfirmationRequired = installFully(
                installer, candidate, identityConfirmedMigration);

        assertThat(trustConfirmationRequired.outcome())
                .isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);
        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();

        PluginInstallResult upgraded = installFully(
                installer, candidate, newSigner.confirmed(identityConfirmedMigration));

        assertThat(upgraded.outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
        assertThat(plugins.resolve("demo-1.0.0.zip")).doesNotExist();
        assertThat(plugins.resolve("demo-2.0.0.zip")).exists();
    }

    @Test
    @DisplayName("仓库根迁移声明篡改候选制品或使用退役根时拒绝")
    void repositoryRootMigrationRejectsTamperingAndRetiredRoot() throws IOException {
        Path plugins = temp.resolve("plugins-invalid-repository-root-migration");
        PluginSigningTestSupport oldSigner = PluginSigningTestSupport.create(
                "old-key", "Old Publisher", false);
        PluginSigningTestSupport newSigner = PluginSigningTestSupport.create(
                "new-key", "New Publisher", false);
        PluginSigningTestSupport repositoryRoot = PluginSigningTestSupport.create(
                "repository-root", "Repository Operator", false);
        ExternalPluginInstaller installer = signedInstaller(
                plugins, PluginSigningTestSupport.verifierFor(oldSigner, newSigner, repositoryRoot));
        Path oldPackage = packageFile("invalid-repository-migration-old.zip", "1.0.0");
        installFully(installer, oldPackage,
                oldSigner.confirmed(oldSigner.originFor(
                        "old-repository", oldPackage, "demo", "1.0.0")));
        Path signedCandidate = packageFile("invalid-repository-migration-signed.zip", "2.0.0");
        RepositoryIdentityMigrationAuthorization authorization =
                repositoryRoot.repositoryIdentityMigrationAuthorization(
                        RepositoryIdentityMigrationAuthorization.KEY_UNAVAILABLE,
                        "demo",
                        "old-repository",
                        oldSigner,
                        "demo",
                        "new-repository",
                        newSigner,
                        signedCandidate,
                        "2.0.0");
        Path tamperedCandidate = packageFile(
                "invalid-repository-migration-tampered.zip", "demo", "2.0.0", "other");
        installer.updateVerifier(new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(
                oldSigner.trustedKey(TrustedPluginKey.State.RETIRED),
                newSigner.trustedKey(TrustedPluginKey.State.ACTIVE),
                repositoryRoot.trustedKey(TrustedPluginKey.State.ACTIVE)))));

        PreparedPluginTransaction tampered = installer.prepareTransaction(
                tamperedCandidate,
                false,
                newSigner.originFor(
                        "new-repository",
                        tamperedCandidate,
                        "demo",
                        "2.0.0",
                        Map.of(),
                        Map.of("demo", authorization),
                        true));
        assertThat(tampered.result().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);

        installer.updateVerifier(new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(
                oldSigner.trustedKey(TrustedPluginKey.State.RETIRED),
                newSigner.trustedKey(TrustedPluginKey.State.ACTIVE),
                repositoryRoot.trustedKey(TrustedPluginKey.State.RETIRED)))));
        PreparedPluginTransaction retiredRoot = installer.prepareTransaction(
                signedCandidate,
                false,
                newSigner.originFor(
                        "new-repository",
                        signedCandidate,
                        "demo",
                        "2.0.0",
                        Map.of(),
                        Map.of("demo", authorization),
                        true));
        assertThat(retiredRoot.result().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(plugins.resolve("demo-1.0.0.zip")).exists();
        assertThat(plugins.resolve("demo-2.0.0.zip")).doesNotExist();
    }

    @Test
    @DisplayName("未签名开发包不能用 replaces 跨插件身份接管")
    void unsignedDevelopmentPackageCannotReplaceAnotherIdentity() {
        Path plugins = temp.resolve("plugins-unsigned-replacement");
        ExternalPluginInstaller installer = newInstaller(plugins);
        installFully(installer, packageFile("retired.zip", "retired", "1.0.0", null));

        PreparedPluginTransaction rejected = installer.prepareTransaction(
                packageFile("replacement.zip", "replacement", "1.0.0", "retired"),
                false, PluginPackageOrigin.localUpload());

        assertThat(rejected.readyToCommit()).isFalse();
        assertThat(rejected.result().outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(plugins.resolve("retired-1.0.0.zip")).exists();
        assertThat(plugins.resolve("replacement-1.0.0.zip")).doesNotExist();
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

    private ExternalPluginInstaller signedInstaller(
            Path plugins, PluginSupplyChainVerifier verifier) {
        ExternalPluginInstaller installer = new ExternalPluginInstaller(
                plugins, PluginPackageLimits.defaults(), verifier);
        installers.add(installer);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
        return installer;
    }
}
