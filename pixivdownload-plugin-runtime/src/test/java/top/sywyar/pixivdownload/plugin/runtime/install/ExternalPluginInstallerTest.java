package top.sywyar.pixivdownload.plugin.runtime.install;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;

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
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginDirectorySessionLock;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;

@DisplayName("外置插件安装器：安全安装、Zip Slip 防护与重复 / 升级 / 降级")
class ExternalPluginInstallerTest {

    @TempDir
    Path home;
    private Path pluginsDir;
    private ExternalPluginInstaller installer;

    @BeforeEach
    void setUp() {
        pluginsDir = home.resolve("plugins");
        installer = new ExternalPluginInstaller(pluginsDir);
        assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
    }

    @AfterEach
    void closeInstaller() {
        installer.close();
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
        installFully(exploded("ext", "1.0.0"));
        PluginInstallResult again = installFully(exploded("ext", "1.0.0"));

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
    @DisplayName("DUPLICATE 不静默删除安装目录中的非规范副本")
    void duplicateDoesNotMutateNonCanonicalCopies() throws IOException {
        installFully(exploded("ext", "1.0.0")); // 规范 ext-1.0.0.zip
        // 安装目录里塞入一个 id/version 相同、但命名非规范的副本
        Files.copy(pluginsDir.resolve("ext-1.0.0.zip"), pluginsDir.resolve("ext-copy.zip"));
        assertThat(pluginFiles()).containsExactly("ext-1.0.0.zip", "ext-copy.zip");

        PluginInstallResult duplicate = installFully(exploded("ext", "1.0.0"));

        assertThat(duplicate.outcome()).isEqualTo(PluginInstallOutcome.DUPLICATE);
        assertThat(duplicate.accepted()).isTrue();
        // DUPLICATE 是无写终态；安装器不在没有事务的情况下删除现有文件。
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
    @DisplayName("requires 高于核心 API：REJECTED_INCOMPATIBLE，不装为可加载状态")
    void rejectsIncompatible() {
        String requires = (PluginApiVersion.MAJOR + 1) + ".0";
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

    // ---------- 完整性校验（受信目录来源；本地上传无期望） ----------

    @Test
    @DisplayName("受信目录来源 + 正确 SHA-256/大小：正常安装为 INSTALLED")
    void trustedCatalogMatchingShaInstalls() throws IOException {
        Path src = exploded("ext", "1.0.0");
        PluginSigningTestSupport signing = PluginSigningTestSupport.create();
        installer.close();
        try (ExternalPluginInstaller signedInstaller = new ExternalPluginInstaller(
                pluginsDir, PluginPackageLimits.defaults(), signing.verifier())) {
            assertThat(signedInstaller.recoverPendingTransactions().safeToScan()).isTrue();
            PluginPackageOrigin origin = signing.originFor("test-repository", src, "ext", "1.0.0");

            PluginInstallResult result = installFully(signedInstaller, src, false, origin);

            assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
            assertThat(signedInstaller.listInstalled())
                    .extracting(installed -> installed.path().getFileName().toString())
                    .containsExactly("ext-1.0.0.zip");
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
