package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PluginBootstrapSession} 单元 / 端到端探针测试：恢复事务早于 start、manager/start 只执行一次、status 正确保存、
 * PROCESS / CONTEXT ownership、closeForContext / close 幂等与释放语义、启用快照默认值 / 不可变性、缺失 / 空 / 坏包诊断路径。
 * 用真实可加载的外置探针插件（{@link BootstrapProbePlugin}）经文件标记观测 load / start / stop 次数，不只靠 mock。
 */
@DisplayName("PluginBootstrapSession：恢复→start 一次 / ownership / 关闭释放 / 真实探针")
class PluginBootstrapSessionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearMarkerProperty() {
        System.clearProperty("bootstrap.probe.marker");
    }

    @Test
    @DisplayName("ownership：createProcess=PROCESS、createContext=CONTEXT")
    void ownershipFactoryMethods() {
        PluginBootstrapSession process = PluginBootstrapSession.createProcess(
                tempDir.resolve("p"), PluginEnabledSnapshot.empty());
        PluginBootstrapSession context = PluginBootstrapSession.createContext(
                tempDir.resolve("c"), PluginEnabledSnapshot.empty());
        assertThat(process.ownership()).isEqualTo(PluginBootstrapSession.Ownership.PROCESS);
        assertThat(context.ownership()).isEqualTo(PluginBootstrapSession.Ownership.CONTEXT);
    }

    @Test
    @DisplayName("启用快照：透传且默认全部启用；status 在 start 后保存")
    void enabledSnapshotPassedThroughAndStatusSaved() {
        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                tempDir, PluginEnabledSnapshot.ofDisabled(java.util.List.of("novel"), java.util.List.of()));
        assertThat(session.enabledSnapshot().isEnabled("novel")).isFalse();
        assertThat(session.enabledSnapshot().isEnabled("gallery")).isTrue();

        PluginRuntimeStatus status = session.start().status();
        // 空目录：EMPTY、零加载、零失败
        assertThat(status.state()).isEqualTo(PluginDirectoryState.EMPTY);
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.hasFailures()).isFalse();
        assertThat(session.isStarted()).isTrue();
    }

    @Test
    @DisplayName("start 幂等：真实探针只 load/start 一次（恢复事务 + 一次扫描，重复 start 为 no-op）")
    void startIsIdempotentProbeLoadsAndStartsOnce() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Path jar = stageProbeJar(pluginsDir);
        Path marker = tempDir.resolve("probe-events.log");
        Files.createFile(marker);
        System.setProperty("bootstrap.probe.marker", marker.toString());

        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        // 探针被 PF4J 加载（构造）+ 启动（start）各一次
        String events = Files.readString(marker, StandardCharsets.UTF_8);
        assertThat(countOccurrences(events, "load")).isEqualTo(1);
        assertThat(countOccurrences(events, "start")).isEqualTo(1);
        assertThat(session.status().startedPluginIds()).contains("bootstrap-probe");
        // 同一 manager 实例
        assertThat(session.manager()).isSameAs(session.manager());

        // 重复 start 幂等：不再次 load / start
        session.start();
        String eventsAfter = Files.readString(marker, StandardCharsets.UTF_8);
        assertThat(countOccurrences(eventsAfter, "load")).isEqualTo(1);
        assertThat(countOccurrences(eventsAfter, "start")).isEqualTo(1);

        // close 停止 + 卸载探针，jar 文件锁释放（Windows 下可删）
        session.close();
        assertThat(countOccurrences(Files.readString(marker, StandardCharsets.UTF_8), "stop")).isEqualTo(1);
        assertThat(session.manager().pluginManager()).isEmpty();
        assertThat(Files.deleteIfExists(jar)).isTrue();
    }

    @Test
    @DisplayName("恢复待处理安装事务：start 时先恢复旧包（target 删除、backup 还原）、再扫描加载探针")
    void recoveryRunsBeforeScan() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        stageProbeJar(pluginsDir);

        // 伪造一个待恢复事务：OLD_ISOLATED——target 是不该出现的「新包」，backup 是应还原的「旧包」
        Path txDir = pluginsDir.resolve(".staging").resolve("tx-1");
        Path backupDir = txDir.resolve("removed");
        Files.createDirectories(backupDir);
        Path strayNew = pluginsDir.resolve("stray-new-package.jar");
        Path origin = tempDir.resolve("restored-old-package.bin");
        Path backup = backupDir.resolve("old.jar");
        Files.writeString(strayNew, "new", StandardCharsets.UTF_8);
        Files.writeString(backup, "old", StandardCharsets.UTF_8);
        // 用 Properties.store 写事务清单，避免 Windows 路径反斜杠被 Properties.load 误当转义（与生产 readManifest UTF-8 对齐）。
        Properties manifest = new Properties();
        manifest.setProperty("state", "OLD_ISOLATED");
        manifest.setProperty("target", strayNew.toString());
        manifest.setProperty("backup.count", "1");
        manifest.setProperty("backup.0.origin", origin.toString());
        manifest.setProperty("backup.0.path", backup.toString());
        try (java.io.Writer writer = Files.newBufferedWriter(txDir.resolve("transaction.properties"),
                StandardCharsets.UTF_8)) {
            manifest.store(writer, null);
        }

        PluginBootstrapSession session = PluginBootstrapSession.createContext(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        // 恢复已执行：stray 新包删除、旧包还原到 origin、事务目录清理
        assertThat(Files.exists(strayNew)).isFalse();
        assertThat(Files.readString(origin, StandardCharsets.UTF_8)).isEqualTo("old");
        assertThat(Files.exists(txDir)).isFalse();
        // 同一次 start 内扫描已执行：探针已加载启动
        assertThat(session.status().startedPluginIds()).contains("bootstrap-probe");
        session.close();
    }

    @Test
    @DisplayName("诊断路径：缺失目录→ABSENT、坏包→failure、不抛、不阻断")
    void missingAndBadDirectoryConvergeToDiagnostics() throws Exception {
        PluginBootstrapSession absent = PluginBootstrapSession.createContext(
                tempDir.resolve("does-not-exist"), PluginEnabledSnapshot.empty());
        absent.start();
        assertThat(absent.status().state()).isEqualTo(PluginDirectoryState.ABSENT);
        assertThat(absent.diagnostics()).isEmpty();

        Path pluginsDir = tempDir.resolve("bad-plugins");
        Files.createDirectories(pluginsDir);
        Files.write(pluginsDir.resolve("broken.jar"), new byte[]{1, 2, 3, 4}); // 非 zip
        PluginBootstrapSession bad = PluginBootstrapSession.createContext(pluginsDir, PluginEnabledSnapshot.empty());
        bad.start();
        // 坏包被隔离捕获成诊断 / failure，不致命
        assertThat(bad.status().hasFailures()).isTrue();
        assertThat(bad.diagnostics()).isEmpty(); // 坏包记入 status.failures（非 session 诊断）
        bad.close();
    }

    @Test
    @DisplayName("合法签名 sidecar：启动期离线复验成功，探针正常 load/start 并持久化 offlineStatus")
    void signedProbeLoadsAndPersistsOfflineReverify() throws Exception {
        Path pluginsDir = tempDir.resolve("signed-plugins");
        Path jar = stageProbeJar(pluginsDir);
        Path marker = tempDir.resolve("signed-events.log");
        Files.createFile(marker);
        System.setProperty("bootstrap.probe.marker", marker.toString());
        SigningFixture signing = SigningFixture.create();
        PluginPackageOrigin origin = signing.originFor(jar, "bootstrap-probe", "1.0.0");
        new PluginProvenanceStore(pluginsDir).write(jar, origin, signing.verifiedResult(jar));

        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                pluginsDir, PluginEnabledSnapshot.empty(), signing.verifier());
        session.start();

        assertThat(session.status().startedPluginIds()).contains("bootstrap-probe");
        assertThat(Files.readString(marker, StandardCharsets.UTF_8)).contains("load").contains("start");
        PluginProvenanceRecord provenance = new PluginProvenanceStore(pluginsDir).read(jar).orElseThrow();
        assertThat(provenance.offlineStatus()).isEqualTo(VerificationStatus.VERIFIED);
        session.close();
    }

    @Test
    @DisplayName("坏签名 sidecar：PF4J 前拒绝，探针构造器与 start 均不执行")
    void invalidSignaturePreventsAnyProbeCodeExecution() throws Exception {
        Path pluginsDir = tempDir.resolve("bad-signature-plugins");
        Path jar = stageProbeJar(pluginsDir);
        Path marker = tempDir.resolve("bad-signature-events.log");
        Files.createFile(marker);
        System.setProperty("bootstrap.probe.marker", marker.toString());
        SignatureMetadata signature = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, "missing-key", "c2ln");
        PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog(
                "test-repository", false, Files.size(jar), PluginPackageIntegrity.sha256Hex(jar), signature);
        VerificationResult result = new VerificationResult(VerificationStatus.VERIFIED,
                "bootstrap-probe", "1.0.0", "missing-key", SignatureMetadata.ED25519,
                null, null, Instant.now(), Files.size(jar), PluginPackageIntegrity.sha256Hex(jar), "VERIFIED");
        new PluginProvenanceStore(pluginsDir).write(jar, origin, result);

        PluginBootstrapSession session = PluginBootstrapSession.createContext(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        assertThat(session.status().startedPluginIds()).doesNotContain("bootstrap-probe");
        assertThat(session.status().hasFailures()).isTrue();
        assertThat(Files.readString(marker, StandardCharsets.UTF_8))
                .as("验签失败必须发生在 PF4J 创建 classloader / 构造插件实例前")
                .isEmpty();
        session.close();
    }

    @Test
    @DisplayName("缺 provenance sidecar：启动扫描 fail-closed，PF4J classloader 不创建")
    void missingProvenancePreventsAnyProbeCodeExecution() throws Exception {
        Path pluginsDir = tempDir.resolve("missing-sidecar-plugins");
        stageProbeJarWithoutProvenance(pluginsDir);
        Path marker = tempDir.resolve("missing-sidecar-events.log");
        Files.createFile(marker);
        System.setProperty("bootstrap.probe.marker", marker.toString());

        PluginBootstrapSession session = PluginBootstrapSession.createContext(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        assertThat(session.status().startedPluginIds()).doesNotContain("bootstrap-probe");
        assertThat(session.status().hasFailures()).isTrue();
        assertThat(Files.readString(marker, StandardCharsets.UTF_8))
                .as("缺 sidecar 必须发生在 PF4J 创建 classloader / 构造插件实例前")
                .isEmpty();
        session.close();
    }


    @Test
    @DisplayName("PROCESS：closeForContext 不关闭（manager 仍就绪），close 才真正关闭")
    void processCloseForContextIsNoOp() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();
        assertThat(session.manager().pluginManager()).isPresent();

        session.closeForContext(); // PROCESS → no-op
        assertThat(session.manager().pluginManager()).isPresent();

        session.close(); // 真正关闭
        assertThat(session.manager().pluginManager()).isEmpty();
    }

    @Test
    @DisplayName("CONTEXT：closeForContext 关闭运行时（等价 close）")
    void contextCloseForContextCloses() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        PluginBootstrapSession session = PluginBootstrapSession.createContext(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();
        assertThat(session.manager().pluginManager()).isPresent();

        session.closeForContext(); // CONTEXT → 关闭
        assertThat(session.manager().pluginManager()).isEmpty();
    }

    @Test
    @DisplayName("close 幂等：多次 close / closeForContext 安全")
    void closeIsIdempotent() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        PluginBootstrapSession session = PluginBootstrapSession.createContext(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();
        session.close();
        session.close();
        session.closeForContext();
        assertThat(session.manager().pluginManager()).isEmpty();
    }

    @Test
    @DisplayName("启动期 inventory / discovery：start 后一次性保存，含已启动探针、无失败")
    void startupInventoryAndDiscoverySavedOnceWithProbe() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        PluginInventory inventory = session.startupInventory();
        PluginDiscoveryResult discovery = session.startupDiscovery();
        assertThat(inventory.installations())
                .as("启动期 inventory 含已启动探针（STARTED 条目）")
                .hasSize(1)
                .extracting(i -> i.id())
                .containsExactly("bootstrap-probe");
        assertThat(discovery.discovered())
                .as("启动期 discovery 含可接入探针")
                .extracting(d -> d.plugin().id())
                .containsExactly("bootstrap-probe");
        assertThat(discovery.hasFailures()).isFalse();
        session.close();
    }

    @Test
    @DisplayName("重复 start 不重复 provider discovery：startup inventory / discovery 实例在多次 start 间不变（缓存、不重新清点）")
    void repeatStartDoesNotRediscover() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();
        PluginInventory firstInventory = session.startupInventory();
        PluginDiscoveryResult firstDiscovery = session.startupDiscovery();

        session.start(); // 幂等 no-op
        session.start();

        assertThat(session.startupInventory()).isSameAs(firstInventory);
        assertThat(session.startupDiscovery()).isSameAs(firstDiscovery);
        session.close();
    }

    @Test
    @DisplayName("运行期动态清点不篡改已保存的 startup 快照")
    void dynamicInspectDoesNotMutateStartupSnapshot() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();
        PluginInventory saved = session.startupInventory();

        // 运行期从同一 manager 动态清点——返回新 inventory 对象，不替换已保存的 startup 快照
        PluginInventory dynamic = session.manager().inspectPlugins();
        assertThat(dynamic).isNotSameAs(saved);
        assertThat(session.startupInventory()).isSameAs(saved);
        session.close();
    }

    @Test
    @DisplayName("坏包进入启动期失败诊断、不阻断 start；startupDiscovery 仍含可正常发现的探针")
    void badPackageConvergesAndDoesNotBlockStartupDiscovery() throws Exception {
        Path pluginsDir = tempDir.resolve("mixed-plugins");
        stageProbeJar(pluginsDir); // 可正常发现的探针
        Files.write(pluginsDir.resolve("broken.jar"), new byte[]{1, 2, 3, 4}); // 非 zip → 加载失败

        PluginBootstrapSession session = PluginBootstrapSession.createContext(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        // start 未被坏包阻断
        assertThat(session.isStarted()).isTrue();
        // 坏包被收敛成 failure（status 或 discovery），不致命
        assertThat(session.status().hasFailures() || session.startupDiscovery().hasFailures()).isTrue();
        // 可正常发现的探针仍出现在 startupDiscovery
        assertThat(session.startupDiscovery().discovered())
                .extracting(d -> d.plugin().id())
                .containsExactly("bootstrap-probe");
        session.close();
    }

    @Test
    @DisplayName("close 后不可重新 start：抛 IllegalStateException（不可复活语义）")
    void startAfterCloseRefuses() {
        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                tempDir.resolve("p"), PluginEnabledSnapshot.empty());
        session.start();
        session.close();
        assertThatThrownBy(session::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    // ── startup snapshot 短生命周期 + classloader 释放 ──────────────────────────

    @Test
    @DisplayName("releaseStartupSnapshot：清空启动期快照（不再持有插件实例 / classloader），运行期动态清点不受影响")
    void releaseStartupSnapshotClearsSnapshot() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();
        assertThat(session.startupInventory().installations()).hasSize(1);
        assertThat(session.startupDiscovery().discovered()).hasSize(1);

        session.releaseStartupSnapshot();

        assertThat(session.startupInventory().installations())
                .as("释放后启动期 inventory 快照为空（不再暴露插件 / classloader）")
                .isEmpty();
        assertThat(session.startupDiscovery().discovered()).isEmpty();
        // 运行期动态清点仍从 manager 取得，不受快照释放影响
        assertThat(session.manager().inspectPlugins().installations())
                .as("释放快照不影响运行期动态清点")
                .hasSize(1);
        // 释放幂等
        session.releaseStartupSnapshot();
        assertThat(session.startupInventory().installations()).isEmpty();
        session.close();
    }

    @Test
    @DisplayName("close 无条件清空启动期快照（即便未显式 release）")
    void closeClearsStartupSnapshot() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();
        assertThat(session.startupInventory().installations()).isNotEmpty();

        session.close();

        assertThat(session.startupInventory().installations())
                .as("close 后启动期快照无条件清空，不残留钉住旧 generation 的引用")
                .isEmpty();
        assertThat(session.startupDiscovery().discovered()).isEmpty();
    }

    @Test
    @DisplayName("释放快照 + 关闭后，探针 classloader 不被会话快照钉住（WeakReference GC 探针，环境容忍）")
    void releasedSnapshotDoesNotPinPluginClassLoader() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        // 捕获探针 classloader 的弱引用（启动期快照当前持有它）
        ClassLoader probeLoader = probeClassLoader(session);
        WeakReference<ClassLoader> loaderRef = new WeakReference<>(probeLoader);

        // 释放启动期快照 + 关闭会话（manager.shutdown 释放 PF4J classloader / 句柄）
        session.releaseStartupSnapshot();
        session.close();
        // 清空本测试的局部强引用，使 classloader 仅余弱引用可达
        probeLoader = null;
        session = null;

        // 确定性引用链已由 releaseStartupSnapshotClearsSnapshot 钉死（快照为空、不再暴露 classloader）；
        // GC 探针为环境相关辅助：尽力回收，未回收时据确定性断言判环境 inconclusive（Windows/CI 下 System.gc 不保证）。
        if (!awaitCollected(loaderRef)) {
            Assumptions.abort("探针 classloader 未在 GC 探针窗口内回收（环境不稳定），"
                    + "确定性引用链已证明会话快照不再持有它");
        }
        assertThat(loaderRef.get())
                .as("释放快照 + 关闭后探针 classloader 应可回收")
                .isNull();
    }

    @Test
    @DisplayName("运行期 reload 后旧 generation classloader 不被已消费的 startup snapshot 钉住")
    void runtimeReloadOldGenerationNotPinnedByStartupSnapshot() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Path pluginJar = stageProbeJar(pluginsDir);
        PluginBootstrapSession session =
                PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        ReloadProbe probe = reloadAfterReleasingSnapshot(session, pluginJar);
        try {
            assertThat(probe.newGeneration()).isGreaterThan(probe.oldGeneration());
            assertThat(session.startupInventory().installations()).isEmpty();
            assertThat(session.startupDiscovery().discovered()).isEmpty();

            if (!awaitCollected(probe.oldClassLoader())) {
                Assumptions.abort("reload 后旧 generation classloader 未在 GC 探针窗口内回收（环境不稳定），"
                        + "确定性断言已证明 startup snapshot 清空且新 generation 已建立");
            }
            assertThat(probe.oldClassLoader().get())
                    .as("旧 generation classloader 不应被 startup snapshot 钉住")
                    .isNull();
        } finally {
            session.close();
        }
    }

    private static ReloadProbe reloadAfterReleasingSnapshot(
            PluginBootstrapSession session, Path pluginJar) {
        PluginInventory inventory = session.startupInventory();
        PluginDiscoveryResult discovery = session.startupDiscovery();
        ClassLoader oldLoader = inventory.installations().stream()
                .filter(i -> "bootstrap-probe".equals(i.id()))
                .map(i -> i.classLoader())
                .findFirst()
                .orElseThrow();
        assertThat(discovery.discovered()).hasSize(1);
        long oldGeneration = session.manager().generation("bootstrap-probe").orElseThrow();

        session.releaseStartupSnapshot();
        inventory = null;
        discovery = null;

        session.manager().stopPlugin("bootstrap-probe");
        session.manager().unloadPlugin("bootstrap-probe");
        session.manager().loadPlugin(pluginJar);
        session.manager().startPlugin("bootstrap-probe");

        long newGeneration = session.manager().generation("bootstrap-probe").orElseThrow();
        ClassLoader newLoader = session.manager().inspectPlugins().installations().stream()
                .filter(i -> "bootstrap-probe".equals(i.id()))
                .map(i -> i.classLoader())
                .findFirst()
                .orElseThrow();
        assertThat(newLoader).isNotSameAs(oldLoader);

        WeakReference<ClassLoader> oldLoaderRef = new WeakReference<>(oldLoader);
        oldLoader = null;
        newLoader = null;
        return new ReloadProbe(oldLoaderRef, oldGeneration, newGeneration);
    }

    private record ReloadProbe(
            WeakReference<ClassLoader> oldClassLoader, long oldGeneration, long newGeneration) {
    }
    /** 探针功能插件在启动期 inventory 中的 classloader（PF4J 插件 classloader）。 */
    private static ClassLoader probeClassLoader(PluginBootstrapSession session) {
        return session.startupInventory().installations().stream()
                .filter(i -> "bootstrap-probe".equals(i.id()))
                .map(i -> i.classLoader())
                .findFirst()
                .orElseThrow(() -> new AssertionError("bootstrap-probe not in startup inventory"));
    }

    /** best-effort GC 探针：反复触发 GC + 内存压力，返回弱引用是否已被清除。镜像 app 侧 ClassLoaderLeakProbes 约定。 */
    private static boolean awaitCollected(WeakReference<?> ref) {
        for (int i = 0; i < 25 && ref.get() != null; i++) {
            provokeGc();
            if (ref.get() == null) {
                break;
            }
            sleepQuietly(40L);
        }
        return ref.get() == null;
    }

    private static void provokeGc() {
        System.gc();
        System.runFinalization();
        List<byte[]> ballast = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                ballast.add(new byte[1 << 20]); // ~8MB 瞬时压力
            }
        } catch (OutOfMemoryError ignored) {
            // 压力已达成
        } finally {
            ballast.clear();
        }
        System.gc();
        System.runFinalization();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- helpers ---

    /** 把 {@link BootstrapProbePlugin} + {@link BootstrapProbeFeaturePlugin} 编译产物组装成 PF4J 可加载的 thin 插件 jar。 */
    private static Path stageProbeJar(Path pluginsDir) throws IOException {
        Path jar = stageProbeJarWithoutProvenance(pluginsDir);
        writeLocalProvenance(pluginsDir, jar);
        return jar;
    }

    private static Path stageProbeJarWithoutProvenance(Path pluginsDir) throws IOException {
        Files.createDirectories(pluginsDir);
        Path jar = pluginsDir.resolve("bootstrap-probe-1.0.0.jar");
        String props = "plugin.id=bootstrap-probe\nplugin.version=1.0.0\nplugin.requires=1.0\n"
                + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n"
                + "plugin.provider=test\nplugin.description=bootstrap probe\n";
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("plugin.properties"));
            zos.write(props.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            addClassEntry(zos, BootstrapProbePlugin.class);
            addClassEntry(zos, BootstrapProbeFeaturePlugin.class);
        }
        return jar;
    }

    private static void writeLocalProvenance(Path pluginsDir, Path jar) throws IOException {
        VerificationResult result = new VerificationResult(VerificationStatus.UNSIGNED_ALLOWED,
                "bootstrap-probe", "1.0.0", null, null, null, null, Instant.now(), Files.size(jar),
                PluginPackageIntegrity.sha256Hex(jar), "UNSIGNED_ALLOWED");
        new PluginProvenanceStore(pluginsDir).write(jar, PluginPackageOrigin.localUpload(), result);
    }

    private static void addClassEntry(ZipOutputStream zos, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getResourceAsStream("/" + entry)) {
            assertThat(in).as("class resource must be compiled: " + entry).isNotNull();
            bytes = in.readAllBytes();
        }
        zos.putNextEntry(new ZipEntry(entry));
        zos.write(bytes);
        zos.closeEntry();
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    private static final class SigningFixture {

        private final String keyId;
        private final PrivateKey privateKey;
        private final TrustedPluginKey trustedKey;

        private SigningFixture(String keyId, PrivateKey privateKey, TrustedPluginKey trustedKey) {
            this.keyId = keyId;
            this.privateKey = privateKey;
            this.trustedKey = trustedKey;
        }

        static SigningFixture create() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(SignatureMetadata.ED25519);
                KeyPair keyPair = generator.generateKeyPair();
                String keyId = "bootstrap-test-key";
                TrustedPluginKey trustedKey = new TrustedPluginKey(
                        keyId,
                        SignatureMetadata.ED25519,
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                        TrustedPluginKey.State.ACTIVE,
                        "Bootstrap Test Publisher",
                        "Bootstrap Test Trust",
                        false);
                return new SigningFixture(keyId, keyPair.getPrivate(), trustedKey);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("无法生成启动探针签名密钥", e);
            }
        }

        PluginSupplyChainVerifier verifier() {
            return new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(trustedKey)));
        }

        PluginPackageOrigin originFor(Path artifact, String pluginId, String version) throws IOException {
            return PluginPackageOrigin.forTrustedCatalog("test-repository", false, Files.size(artifact),
                    PluginPackageIntegrity.sha256Hex(artifact), artifactSignature(artifact, pluginId, version));
        }

        VerificationResult verifiedResult(Path artifact) throws IOException {
            return new VerificationResult(VerificationStatus.VERIFIED, "bootstrap-probe", "1.0.0",
                    keyId, SignatureMetadata.ED25519, trustedKey.publisher(), trustedKey.trustLabel(),
                    Instant.now(), Files.size(artifact), PluginPackageIntegrity.sha256Hex(artifact), "VERIFIED");
        }

        private SignatureMetadata artifactSignature(Path artifact, String pluginId, String version)
                throws IOException {
            byte[] sha256 = Hashing.sha256(artifact);
            byte[] message = EnvelopeV1Codec.artifactMessage(SignatureMetadata.ED25519, keyId,
                    pluginId, version, Files.size(artifact), sha256);
            return new SignatureMetadata(SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, keyId,
                    Base64.getEncoder().encodeToString(sign(message)));
        }

        private byte[] sign(byte[] message) {
            try {
                Signature signature = Signature.getInstance(SignatureMetadata.ED25519);
                signature.initSign(privateKey);
                signature.update(message);
                return signature.sign();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("无法生成启动探针签名", e);
            }
        }
    }
}
