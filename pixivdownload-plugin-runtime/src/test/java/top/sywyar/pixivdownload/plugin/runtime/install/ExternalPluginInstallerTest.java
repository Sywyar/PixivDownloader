package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.sdk.SdkVersion;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageFormat;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.ProvenanceSnapshotState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginDirectorySessionLock;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustDecision;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;

@DisplayName("外置插件安装器：安全安装、Zip Slip 防护与重复 / 升级 / 降级")
class ExternalPluginInstallerTest {

    @TempDir
    Path home;
    private Path pluginsDir;
    private ExternalPluginInstaller installer;
    private String previousDevelopmentMode;

    @BeforeEach
    void setUp() {
        previousDevelopmentMode = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
        pluginsDir = home.resolve("plugins");
        installer = new ExternalPluginInstaller(pluginsDir);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
    }

    @AfterEach
    void closeInstaller() {
        try {
            installer.close();
        } finally {
            restoreDevelopmentMode();
        }
    }

    private void restoreDevelopmentMode() {
        if (previousDevelopmentMode == null) {
            System.clearProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        } else {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousDevelopmentMode);
        }
    }

    // ---------- 基本安装 ----------

    @Test
    @DisplayName("调用方提供的目录租约必须保护同一规范化安装根")
    void suppliedDirectoryLockMustProtectSameRoot() throws Exception {
        Path otherRoot = home.resolve("other-plugins");
        try (PluginDirectorySessionLock wrongRootLock = new PluginDirectorySessionLock(otherRoot)) {
            assertThatThrownBy(() -> new ExternalPluginInstaller(
                    pluginsDir, PluginPackageLimits.defaults(),
                    ignored -> new PluginSupplyChainVerifier(), wrongRootLock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("different root");
        }
    }

    @Test
    @DisplayName("首次安装解压目录形态包：INSTALLED，落盘为 {id}-{version}.zip，安装目录按需创建")
    void installsExplodedPackage() {
        PluginInstallResult result = installFully(exploded("ext-stats", "1.0.0"));

        assertThat(result.outcome()).as(result.messages().toString())
                .isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(result.accepted()).isTrue();
        assertThat(result.installedPath()).isNotNull();
        assertThat(result.installedPath().getFileName().toString()).isEqualTo("ext-stats-1.0.0.zip");
        assertThat(Files.exists(result.installedPath())).isTrue();
        assertThat(installer.listInstalled()).extracting(InstalledPlugin::id).containsExactly("ext-stats");
    }

    @Test
    @DisplayName("首次安装单 jar 形态包：INSTALLED，落盘为 {id}-{version}.jar（取出内层 jar，可再次读出描述符）")
    void installsSingleJarPackage() {
        PluginInstallResult result = installFully(singleJar("ext-dup", "1.0.0"));

        assertThat(result.outcome()).as(result.messages().toString())
                .isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(result.installedPath().getFileName().toString()).isEqualTo("ext-dup-1.0.0.jar");
        PluginPackageInspection reInspected = PluginPackageReader.inspect(result.installedPath());
        assertThat(reInspected.format()).isEqualTo(PluginPackageFormat.SINGLE_JAR);
        assertThat(reInspected.descriptor().id()).isEqualTo("ext-dup");
    }

    @Test
    @DisplayName("安装目录名以 descriptor 的 id/version 为准，与上传文件名无关")
    void canonicalNameFollowsDescriptorNotUploadFilename() {
        Path uploaded = PluginPackageFixtures.explodedZip(
                home.resolve("totally-unrelated-name.zip"), "ext-real", "3.4.5", null, "com.example.Real");

        PluginInstallResult result = installFully(uploaded);

        assertThat(result.installedPath().getFileName().toString()).isEqualTo("ext-real-3.4.5.zip");
    }

    // ---------- 重复 / 升级 / 降级 ----------

    @Test
    @DisplayName("同 id 同版本重复安装：DUPLICATE，幂等，不产生第二个副本")
    void duplicateSameVersionIsIdempotent() {
        Path artifact = exploded("ext", "1.0.0");
        installFully(artifact);
        PluginInstallResult again = installFully(artifact);

        assertThat(again.outcome()).isEqualTo(PluginInstallOutcome.DUPLICATE);
        assertThat(pluginFiles()).containsExactly("ext-1.0.0.zip");
    }

    @Test
    @DisplayName("高版本覆盖低版本：UPGRADED，旧版本被移除，仅留新版本")
    void higherVersionUpgrades() {
        installFully(exploded("ext", "1.0.0"));
        PluginInstallResult upgrade = installFully(exploded("ext", "1.1.0"));

        assertThat(upgrade.outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
        assertThat(upgrade.previousVersion()).isEqualTo("1.0.0");
        assertThat(pluginFiles()).containsExactly("ext-1.1.0.zip");
    }

    @Test
    @DisplayName("低版本覆盖高版本：默认 DOWNGRADE_REJECTED，安装目录不变")
    void lowerVersionRejectedByDefault() {
        installFully(exploded("ext", "2.0.0"));
        PluginInstallResult downgrade = installFully(exploded("ext", "1.0.0"));

        assertThat(downgrade.outcome()).isEqualTo(PluginInstallOutcome.DOWNGRADE_REJECTED);
        assertThat(downgrade.accepted()).isFalse();
        assertThat(pluginFiles()).containsExactly("ext-2.0.0.zip");
    }

    @Test
    @DisplayName("低版本覆盖高版本 + 显式允许降级（force）：DOWNGRADED，高版本被移除")
    void lowerVersionAllowedWithForce() {
        installFully(exploded("ext", "2.0.0"));
        PluginInstallResult downgrade = installFully(exploded("ext", "1.0.0"), true,
                PluginPackageOrigin.localUpload());

        assertThat(downgrade.outcome()).isEqualTo(PluginInstallOutcome.DOWNGRADED);
        assertThat(downgrade.previousVersion()).isEqualTo("2.0.0");
        assertThat(pluginFiles()).containsExactly("ext-1.0.0.zip");
    }

    @Test
    @DisplayName("同 id/version 存在多个副本时失败关闭且不静默删除")
    void duplicateDoesNotMutateNonCanonicalCopies() throws IOException {
        installFully(exploded("ext", "1.0.0")); // 规范 ext-1.0.0.zip
        // 安装目录里塞入一个 id/version 相同、但命名非规范的副本
        Files.copy(pluginsDir.resolve("ext-1.0.0.zip"), pluginsDir.resolve("ext-copy.zip"));
        assertThat(pluginFiles()).containsExactly("ext-1.0.0.zip", "ext-copy.zip");

        PluginInstallResult duplicate = installFully(exploded("ext", "1.0.0"));

        assertThat(duplicate.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(duplicate.accepted()).isFalse();
        assertThat(pluginFiles()).containsExactly("ext-1.0.0.zip", "ext-copy.zip");
        assertThat(installer.listInstalled()).extracting(InstalledPlugin::id).containsExactly("ext", "ext");
    }

    @Test
    @DisplayName("不同 id 各自独立安装、互不影响")
    void differentIdsCoexist() {
        installFully(exploded("ext-a", "1.0.0"));
        installFully(singleJar("ext-b", "1.0.0"));

        assertThat(pluginFiles()).containsExactlyInAnyOrder("ext-a-1.0.0.zip", "ext-b-1.0.0.jar");
    }

    // ---------- 校验 / 兼容 ----------

    @Test
    @DisplayName("缺 version / 非 semver 版本：REJECTED_INVALID，零落盘")
    void rejectsInvalidDescriptor() {
        Path pkg = PluginPackageFixtures.explodedZip(home.resolve("badver.zip"),
                "ext", "1.0", null, "com.example.P"); // 1.0 不是合法 semver

        PluginInstallResult result = installFully(pkg);

        assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INVALID);
        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("尾随垃圾的 requires：REJECTED_INVALID，不能截断成兼容版本")
    void rejectsPartiallyParsedApiRequirement() {
        Path pkg = PluginPackageFixtures.explodedZip(home.resolve("bad-requires.zip"),
                "ext", "1.0.0", "1.0garbage", "com.example.P");

        PluginInstallResult result = installFully(pkg);

        assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INVALID);
        assertThat(result.messages()).anyMatch(message -> message.contains("unparseable requires"));
        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("requires 高于SDK：REJECTED_INCOMPATIBLE，不装为可加载状态")
    void rejectsIncompatible() {
        String requires = (SdkVersion.MAJOR + 1) + ".0";
        Path pkg = PluginPackageFixtures.explodedZip(home.resolve("future.zip"),
                "ext", "1.0.0", requires, "com.example.P");

        PluginInstallResult result = installFully(pkg);

        assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INCOMPATIBLE);
        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("空包 / 缺描述符 / 歧义包：各自对应拒绝结果，零落盘")
    void rejectsMalformedPackages() {
        Path empty = home.resolve("empty.zip");
        PluginPackageFixtures.writeZip(empty, new LinkedHashMap<>());
        assertThat(installFully(empty).outcome()).isEqualTo(PluginInstallOutcome.REJECTED_EMPTY);

        Map<String, byte[]> noDescriptor = new LinkedHashMap<>();
        noDescriptor.put("readme.txt", PluginPackageFixtures.bytes("x"));
        Path nodesc = home.resolve("nd.zip");
        PluginPackageFixtures.writeZip(nodesc, noDescriptor);
        assertThat(installFully(nodesc).outcome()).isEqualTo(PluginInstallOutcome.REJECTED_NO_DESCRIPTOR);

        Map<String, byte[]> ambiguous = new LinkedHashMap<>();
        ambiguous.put("a.jar", PluginPackageFixtures.pluginJarBytes("a", "1.0.0", null, "com.example.A"));
        ambiguous.put("b.jar", PluginPackageFixtures.pluginJarBytes("b", "1.0.0", null, "com.example.B"));
        Path ambi = home.resolve("ambi.zip");
        PluginPackageFixtures.writeZip(ambi, ambiguous);
        assertThat(installFully(ambi).outcome()).isEqualTo(PluginInstallOutcome.REJECTED_AMBIGUOUS);

        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("不存在的包路径：REJECTED_EMPTY，不抛异常")
    void rejectsMissingFile() {
        PluginInstallResult result = installFully(home.resolve("does-not-exist.zip"));
        assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_EMPTY);
    }

    // ---------- Zip Slip ----------

    @Test
    @DisplayName("Zip Slip：../ 父级回溯 entry 被拒，安装目录内外都无文件产生")
    void rejectsParentTraversal() {
        assertZipSlipRejected(zipWithEvilEntry("../evil.jar"));
    }

    @Test
    @DisplayName("Zip Slip：绝对路径 entry 被拒")
    void rejectsAbsolutePath() {
        assertZipSlipRejected(zipWithEvilEntry("/etc/evil.jar"));
    }

    @Test
    @DisplayName("Zip Slip：Windows 盘符路径 entry 被拒")
    void rejectsWindowsDrivePath() {
        assertZipSlipRejected(zipWithEvilEntry("C:\\Windows\\evil.jar"));
    }

    @Test
    @DisplayName("Zip Slip：nested/../../evil 嵌套回溯被拒")
    void rejectsNestedTraversal() {
        assertZipSlipRejected(zipWithEvilEntry("nested/../../evil.jar"));
    }

    @Test
    @DisplayName("Zip Slip 被拒后不留暂存：安装目录内无 .staging 残留")
    void noStagingLeftoverAfterRejection() {
        installFully(zipWithEvilEntry("../evil.jar"));
        assertThat(Files.exists(pluginsDir.resolve(ExternalPluginInstaller.STAGING_DIR))).isFalse();
    }

    // ---------- 失败不留半成品 ----------

    @Test
    @DisplayName(".staging 被普通文件占位时恢复门 fail-closed，且不产生目标包")
    void failedCommitLeavesNoHalfInstall() throws IOException {
        Files.createDirectories(pluginsDir);
        // 用普通文件占住 .staging 名，使暂存子目录创建失败 → 提交阶段 IO 失败
        Files.writeString(pluginsDir.resolve(ExternalPluginInstaller.STAGING_DIR), "block", StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> installFully(exploded("ext", "1.0.0")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery is unsafe");
        assertThat(Files.exists(pluginsDir.resolve("ext-1.0.0.zip"))).isFalse();
        assertThat(pluginsDir.resolve(ExternalPluginInstaller.STAGING_DIR)).isRegularFile();
    }

    @Test
    @DisplayName("被取代旧包的隔离路径不可用时提交失败并保留旧版本")
    void supersededIsolationFailureAbortsInstall() {
        installFully(exploded("ext", "1.0.0")); // 既有规范 ext-1.0.0.zip
        PreparedPluginTransaction prepared = installer.prepareTransaction(
                exploded("ext", "1.1.0"), false, PluginPackageOrigin.localUpload());
        Path backupDirectory = prepared.transactionDirectory().resolve("removed");
        try {
            Files.writeString(backupDirectory, "block", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> installer.commitTransaction(prepared))
                .isInstanceOf(RuntimeException.class);
        assertThat(Files.exists(pluginsDir.resolve("ext-1.1.0.zip"))).isFalse();
        assertThat(pluginsDir.resolve("ext-1.0.0.zip")).exists();
        assertThat(prepared.transactionDirectory()).exists();
    }

    // ---------- 资源规模上限（防 Zip Bomb） ----------

    @Test
    @DisplayName("entry 数量超出安装器上限：REJECTED_TOO_LARGE，零落盘")
    void rejectsTooManyEntries() {
        // 解压目录形态包有 plugin.properties + classes/ + classes/Marker.class 共 3 个 entry
        installer.close();
        try (ExternalPluginInstaller limited = new ExternalPluginInstaller(pluginsDir,
                limits(64 << 20, 1, 256L << 20, 64 << 20, 1 << 20, Long.MAX_VALUE))) {
            assertThat(limited.recoverPendingTransactions().safeToScan()).isTrue();
            PluginInstallResult result = installFully(limited, exploded("ext", "1.0.0"), false,
                    PluginPackageOrigin.localUpload());

            assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_TOO_LARGE);
            assertThat(result.accepted()).isFalse();
            assertThat(limited.listInstalled()).isEmpty();
            assertThat(Files.exists(pluginsDir.resolve(ExternalPluginInstaller.STAGING_DIR))).isFalse();
        }
    }

    @Test
    @DisplayName("plugin.properties 超出描述符读取上限：REJECTED_TOO_LARGE，零落盘")
    void rejectsOversizedDescriptor() {
        // 资源扫描上限放宽、仅描述符上限收紧到 8 字节（真实 plugin.properties 数十字节）
        installer.close();
        try (ExternalPluginInstaller limited = new ExternalPluginInstaller(pluginsDir,
                limits(64 << 20, 20000, 256L << 20, 64 << 20, 8, Long.MAX_VALUE))) {
            assertThat(limited.recoverPendingTransactions().safeToScan()).isTrue();
            PluginInstallResult result = installFully(limited, exploded("ext", "1.0.0"), false,
                    PluginPackageOrigin.localUpload());

            assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_TOO_LARGE);
            assertThat(limited.listInstalled()).isEmpty();
        }
    }

    // ---------- 完整性校验（受信目录与带签名本地来源） ----------

    @Test
    @DisplayName("自定义目录正确验签后仍以精确 SHA-256 确认执行信任")
    void trustedCatalogMatchingShaInstalls() throws IOException {
        Path src = exploded("ext", "1.0.0");
        PluginSigningTestSupport signing = PluginSigningTestSupport.create();
        installer.close();
        try (ExternalPluginInstaller signedInstaller = new ExternalPluginInstaller(
                pluginsDir, PluginPackageLimits.defaults(), signing.verifier())) {
            assertThat(signedInstaller.recoverPendingTransactions().safeToScan()).isTrue();
            PluginPackageOrigin origin = signing.originFor("test-repository", src, "ext", "1.0.0");

            PluginInstallResult pending = installFully(signedInstaller, src, false, origin);

            assertThat(pending.outcome()).isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);
            assertThat(pending.trustRequirement()).isNotNull();
            assertThat(signedInstaller.listInstalled()).isEmpty();

            PluginPackageOrigin confirmed = PluginPackageOrigin.forTrustedCatalog(
                    origin.repositoryId(), origin.officialRepository(), origin.expectedSizeBytes(),
                    origin.expectedSha256(), origin.signature(), origin.identityMigrationSignatures(),
                    origin.repositoryIdentityMigrationAuthorizations(), false,
                    pending.trustRequirement().artifactSha256());
            PluginInstallResult result = installFully(signedInstaller, src, false, confirmed);

            assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
            assertThat(signedInstaller.listInstalled())
                    .extracting(installed -> installed.path().getFileName().toString())
                    .containsExactly("ext-1.0.0.zip");
        }
    }

    @Test
    @DisplayName("带可信 detached 签名的本地包以精确 SHA-256 确认后持久化发布者信任")
    void signedLocalUploadInstallsAndPersistsSignature() throws IOException {
        Path src = exploded("signed-local", "1.0.0");
        PluginSigningTestSupport signing = PluginSigningTestSupport.createOfficial();
        SignatureMetadata signature = signing.artifactSignature(src, "signed-local", "1.0.0");
        installer.close();
        try (ExternalPluginInstaller signedInstaller = new ExternalPluginInstaller(
                pluginsDir, PluginPackageLimits.defaults(), signing.verifier())) {
            assertThat(signedInstaller.recoverPendingTransactions().safeToScan()).isTrue();

            PluginInstallResult pending = installFully(
                    signedInstaller, src, false, PluginPackageOrigin.localUpload(signature));

            assertThat(pending.outcome()).isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);
            assertThat(signedInstaller.listInstalled()).isEmpty();

            PluginInstallResult result = installFully(
                    signedInstaller, src, false, PluginPackageOrigin.localUpload(
                            signature, pending.trustRequirement().artifactSha256()));

            assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
            var provenance = new PluginProvenanceStore(pluginsDir).read(result.installedPath()).orElseThrow();
            assertThat(provenance.source()).isEqualTo(
                    top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource.LOCAL_UPLOAD);
            assertThat(provenance.signature()).isEqualTo(signature);
            assertThat(provenance.status()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(provenance.trustDecision()).isNotNull();
            assertThat(provenance.originForOfflineVerification()).isEqualTo(
                    PluginPackageOrigin.localUpload(signature));
        }
    }

    @Test
    @DisplayName("未签名本地包更新制品哈希后必须重新确认")
    void unsignedLocalUploadRequiresConfirmationForEachArtifact() throws IOException {
        Path firstPackage = exploded("unsigned-local", "1.0.0");
        PluginInstallResult firstPending = installFully(
                firstPackage, false, PluginPackageOrigin.localUnsignedUpload(null));
        assertThat(firstPending.outcome()).isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);

        PluginInstallResult installed = installFully(
                firstPackage,
                false,
                PluginPackageOrigin.localUnsignedUpload(firstPending.trustRequirement().artifactSha256()));
        assertThat(installed.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(new PluginProvenanceStore(pluginsDir).read(installed.installedPath()).orElseThrow()
                .trustDecision().approvalType()).isEqualTo(PluginTrustDecision.ApprovalType.EXACT_ARTIFACT);

        Path updatedPackage = exploded("unsigned-local", "2.0.0");
        PluginInstallResult updatePending = installFully(
                updatedPackage, false, PluginPackageOrigin.localUnsignedUpload(null));
        assertThat(updatePending.outcome()).isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);
        assertThat(updatePending.trustRequirement().artifactSha256())
                .isNotEqualTo(firstPending.trustRequirement().artifactSha256());
        assertThat(pluginsDir.resolve("unsigned-local-1.0.0.zip")).exists();

        PluginInstallResult upgraded = installFully(
                updatedPackage,
                false,
                PluginPackageOrigin.localUnsignedUpload(updatePending.trustRequirement().artifactSha256()));
        assertThat(upgraded.outcome()).isEqualTo(PluginInstallOutcome.UPGRADED);
        assertThat(pluginsDir.resolve("unsigned-local-1.0.0.zip")).doesNotExist();
        assertThat(pluginsDir.resolve("unsigned-local-2.0.0.zip")).exists();
    }

    @Test
    @DisplayName("本地包签名未链到官方信任根时拒绝安装")
    void signedLocalUploadRejectsNonOfficialKey() throws IOException {
        Path src = exploded("custom-signed-local", "1.0.0");
        PluginSigningTestSupport signing = PluginSigningTestSupport.create();
        SignatureMetadata signature = signing.artifactSignature(src, "custom-signed-local", "1.0.0");
        installer.close();
        try (ExternalPluginInstaller signedInstaller = new ExternalPluginInstaller(
                pluginsDir, PluginPackageLimits.defaults(), signing.verifier())) {
            assertThat(signedInstaller.recoverPendingTransactions().safeToScan()).isTrue();

            PluginInstallResult result = installFully(
                    signedInstaller, src, false, PluginPackageOrigin.localUpload(signature));

            assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
            assertThat(signedInstaller.listInstalled()).isEmpty();
        }
    }

    @Test
    @DisplayName("受信目录来源 + 错误 SHA-256：REJECTED_INTEGRITY，零落盘")
    void trustedCatalogWrongShaRejected() {
        PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog(
                "test-repository", false,
                null, "0000000000000000000000000000000000000000000000000000000000000000", null);

        PluginInstallResult result = installFully(exploded("ext", "1.0.0"), false, origin);

        assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(result.accepted()).isFalse();
        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("受信目录来源声明未知签名 key：fail-closed → REJECTED_INTEGRITY，零落盘")
    void trustedCatalogSignatureFailsClosed() {
        SignatureMetadata metadata = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, "missing-key", "c2ln");
        PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog(
                "test-repository", false, null, null, metadata);

        PluginInstallResult result = installFully(exploded("ext", "1.0.0"), false, origin);

        assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(pluginFiles()).isEmpty();
    }

    @Test
    @DisplayName("分页目录元数据与冻结包描述符不一致时拒绝且零落盘")
    void trustedCatalogDescriptorBindingMismatchIsRejected() throws IOException {
        Path src = exploded("ext", "1.0.0");
        PluginSigningTestSupport signing = PluginSigningTestSupport.create();
        PluginPackageOrigin signed = signing.originFor("test-repository", src, "ext", "1.0.0");
        PluginPackageOrigin bound = PluginPackageOrigin.forTrustedCatalog(
                signed.repositoryId(), false, signed.expectedSizeBytes(), signed.expectedSha256(), signed.signature(),
                "different", "2.0.0", "99.0", List.of("missing@1.0"));
        installer.close();
        try (ExternalPluginInstaller signedInstaller = new ExternalPluginInstaller(
                pluginsDir, PluginPackageLimits.defaults(), signing.verifier())) {
            assertThat(signedInstaller.recoverPendingTransactions().safeToScan()).isTrue();

            PluginInstallResult result = installFully(signedInstaller, src, false, bound);

            assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
            assertThat(result.messages()).containsExactly(
                    "catalog plugin id does not match the frozen package descriptor",
                    "catalog version does not match the frozen package descriptor",
                    "catalog SDK requirement does not match the frozen package descriptor",
                    "catalog dependencies do not match the frozen package descriptor");
            assertThat(signedInstaller.listInstalled()).isEmpty();
        }
    }

    // ---------- 管理快照原子性与 provenance 累计预算 ----------

    @Test
    @DisplayName("管理快照从 artifact 枚举到 provenance 读取持续持锁并阻塞并发删除")
    void managementSnapshotHoldsInstallerLockAcrossArtifactAndProvenance() throws Exception {
        installer.close();
        CountDownLatch snapshotPaused = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        try (ExternalPluginInstaller snapshotInstaller = new ExternalPluginInstaller(pluginsDir) {
            @Override
            void beforeManagementProvenanceSnapshot() {
                snapshotPaused.countDown();
                try {
                    if (!releaseSnapshot.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release management snapshot");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("management snapshot wait interrupted", e);
                }
            }
        }) {
            assertThat(snapshotInstaller.recoverPendingTransactions().safeToScan()).isTrue();
            PluginInstallResult installed = installFully(snapshotInstaller,
                    exploded("atomic-snapshot", "1.0.0"), false, PluginPackageOrigin.localUpload());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<InstalledPluginInventorySnapshot> snapshot = null;
            Future<Boolean> removal = null;
            try {
                snapshot = pool.submit(() -> snapshotInstaller.snapshotInstalledWithProvenance(10, 1L << 20));
                assertThat(snapshotPaused.await(10, TimeUnit.SECONDS)).isTrue();
                removal = pool.submit(() -> snapshotInstaller.removeInstalled("atomic-snapshot"));

                Future<Boolean> pendingRemoval = removal;
                assertThatThrownBy(() -> pendingRemoval.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);

                releaseSnapshot.countDown();
                InstalledPluginInventorySnapshot frozen =
                        snapshot.get(10, TimeUnit.SECONDS);
                assertThat(frozen.entries()).singleElement().satisfies(entry -> {
                    assertThat(entry.plugin().path()).isEqualTo(installed.installedPath());
                    assertThat(entry.provenanceState())
                            .isEqualTo(ProvenanceSnapshotState.PRESENT);
                });
                assertThat(removal.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(installed.installedPath()).doesNotExist();
            } finally {
                releaseSnapshot.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("损坏 provenance 的实际字节计入累计预算且后续记录不再读取")
    void malformedProvenanceConsumesManagementSnapshotBudget() throws Exception {
        PluginInstallResult first = installFully(exploded("a-invalid", "1.0.0"));
        installFully(exploded("b-after-invalid", "1.0.0"));
        PluginProvenanceStore store = new PluginProvenanceStore(pluginsDir);
        byte[] malformed = ("formatVersion=1\nunknown=" + "x".repeat(256) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(store.sidecarPath(first.installedPath()), malformed);

        InstalledPluginInventorySnapshot snapshot =
                installer.snapshotInstalledWithProvenance(10, malformed.length);

        assertThat(snapshot.budgetExhausted()).isTrue();
        assertThat(snapshot.entries()).extracting(InstalledPluginSnapshot::provenanceState)
                .containsExactly(
                        ProvenanceSnapshotState.INVALID,
                        ProvenanceSnapshotState.BUDGET_EXHAUSTED);
    }

    @Test
    @DisplayName("单条严格 provenance 状态不确定时只拒绝当前条目并继续投影后续插件")
    void ambiguousProvenanceDoesNotExhaustLaterManagementSnapshots() throws Exception {
        PluginInstallResult first = installFully(exploded("a-ambiguous", "1.0.0"));
        installFully(exploded("b-valid", "1.0.0"));
        PluginProvenanceStore store = new PluginProvenanceStore(pluginsDir);
        Path current = store.sidecarPath(first.installedPath());
        Path legacy = store.managedSidecarPaths(first.installedPath()).stream()
                .filter(path -> !path.equals(current))
                .findFirst()
                .orElseThrow();
        Files.copy(current, legacy);

        InstalledPluginInventorySnapshot snapshot =
                installer.snapshotInstalledWithProvenance(10, 1L << 20);

        assertThat(snapshot.budgetExhausted()).isFalse();
        assertThat(snapshot.entries()).extracting(InstalledPluginSnapshot::provenanceState)
                .containsExactly(
                        ProvenanceSnapshotState.INVALID,
                        ProvenanceSnapshotState.PRESENT);
    }

    @Test
    @DisplayName("单个 provenance 超过硬上限后预算熔断并跳过后续记录")
    void oversizedProvenanceExhaustsManagementSnapshotBudget() throws Exception {
        PluginInstallResult first = installFully(exploded("a-oversized", "1.0.0"));
        installFully(exploded("b-after-oversized", "1.0.0"));
        PluginProvenanceStore store = new PluginProvenanceStore(pluginsDir);
        Files.write(store.sidecarPath(first.installedPath()), new byte[(1 << 20) + 1]);

        InstalledPluginInventorySnapshot snapshot =
                installer.snapshotInstalledWithProvenance(10, 8L << 20);

        assertThat(snapshot.budgetExhausted()).isTrue();
        assertThat(snapshot.entries()).extracting(InstalledPluginSnapshot::provenanceState)
                .containsExactly(
                        ProvenanceSnapshotState.INVALID,
                        ProvenanceSnapshotState.BUDGET_EXHAUSTED);
    }

    // ---------- 并发安装串行化 ----------

    @Test
    @DisplayName("调用方串行化完整事务时，并发同 id 请求只落盘一份规范包")
    void callerSerializesConcurrentTransactionsOfSameId() throws Exception {
        int threads = 8;
        // 每个线程一份独立源 zip（同 id/version），避免共享源文件读竞争
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            sources.add(PluginPackageFixtures.explodedZip(
                    home.resolve("src-" + i + ".zip"), "ext", "1.0.0", null, "com.example.P"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<PluginInstallResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Path src = sources.get(i);
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    synchronized (installer) {
                        return installFully(src);
                    }
                }));
            }
            List<PluginInstallOutcome> outcomes = new ArrayList<>();
            for (Future<PluginInstallResult> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS).outcome());
            }

            long installed = outcomes.stream().filter(o -> o == PluginInstallOutcome.INSTALLED).count();
            long duplicate = outcomes.stream().filter(o -> o == PluginInstallOutcome.DUPLICATE).count();
            assertThat(installed).as("恰好一次真正落盘").isEqualTo(1);
            assertThat(duplicate).as("其余皆幂等 DUPLICATE").isEqualTo(threads - 1);
            assertThat(outcomes).allMatch(o ->
                    o == PluginInstallOutcome.INSTALLED || o == PluginInstallOutcome.DUPLICATE);
            // 落盘唯一规范包、无半成品 / 无 .staging 残留
            assertThat(pluginFiles()).containsExactly("ext-1.0.0.zip");
            assertThat(installer.listInstalled()).extracting(InstalledPlugin::id).containsExactly("ext");
            assertThat(Files.exists(pluginsDir.resolve(ExternalPluginInstaller.STAGING_DIR))).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- helpers ----------

    private static PluginPackageLimits limits(long archive, int entries, long total, long entry,
                                              long descriptor, long ratio) {
        return new PluginPackageLimits(archive, entries, total, entry, descriptor, ratio);
    }

    private void assertZipSlipRejected(Path pkg) {
        long filesBefore = countRegularFilesUnder(home);

        PluginInstallResult result = installFully(pkg);

        assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_UNSAFE);
        assertThat(pluginFiles()).isEmpty();
        // 目录会话锁是正式运行时文件；除它之外不得因越界 entry 新增任何文件。
        assertThat(countRegularFilesUnder(home)).isEqualTo(filesBefore);
    }

    /** 一个本来合法的解压目录包，但额外塞进一个越界 entry。 */
    private Path zipWithEvilEntry(String evilEntryName) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PluginPackageFixtures.PLUGIN_PROPERTIES,
                PluginPackageFixtures.bytes(PluginPackageFixtures.pluginProperties("ext", "1.0.0", null, "com.example.P")));
        entries.put("classes/Marker.class", PluginPackageFixtures.bytes("y"));
        entries.put(evilEntryName, PluginPackageFixtures.bytes("evil-payload"));
        Path zip = home.resolve("slip-" + Math.abs(evilEntryName.hashCode()) + ".zip");
        PluginPackageFixtures.writeZip(zip, entries);
        return zip;
    }

    private Path exploded(String id, String version) {
        return PluginPackageFixtures.explodedZip(
                home.resolve(id + "-" + version + "-src.zip"), id, version, null, "com.example." + classOf(id));
    }

    private Path singleJar(String id, String version) {
        return PluginPackageFixtures.singleJarZip(
                home.resolve(id + "-" + version + "-src.zip"), id + ".jar", id, version, null, "com.example." + classOf(id));
    }

    private static String classOf(String id) {
        return id.replace("-", "") + "Plugin";
    }

    private PluginInstallResult installFully(Path packagePath) {
        return installFully(packagePath, false, PluginPackageOrigin.localUpload());
    }

    private PluginInstallResult installFully(Path packagePath, boolean allowDowngrade,
                                             PluginPackageOrigin origin) {
        return installFully(installer, packagePath, allowDowngrade, origin);
    }

    private static PluginInstallResult installFully(ExternalPluginInstaller targetInstaller, Path packagePath,
                                                    boolean allowDowngrade, PluginPackageOrigin origin) {
        PreparedPluginTransaction prepared = targetInstaller.prepareTransaction(
                packagePath, allowDowngrade, origin);
        if (!prepared.readyToCommit()) {
            return prepared.result();
        }
        CommittedPluginTransaction committed = targetInstaller.commitTransaction(prepared);
        targetInstaller.verifyCommittedTarget(committed);
        targetInstaller.markActivated(committed);
        targetInstaller.completeTransaction(committed);
        return prepared.result();
    }

    private List<String> pluginFiles() {
        return installer.listInstalled().stream()
                .map(installed -> installed.path().getFileName().toString())
                .sorted()
                .toList();
    }

    private static long countRegularFilesUnder(Path root) {
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> !".pixivdownload-runtime.lock".equals(path.getFileName().toString()))
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
