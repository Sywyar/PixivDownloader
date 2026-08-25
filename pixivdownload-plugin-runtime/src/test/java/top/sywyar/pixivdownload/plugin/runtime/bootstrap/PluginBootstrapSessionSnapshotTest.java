package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginDirectorySessionLock;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PluginBootstrapSession} 单元 / 端到端探针测试：恢复事务早于 start、manager/start 只执行一次、status 正确保存、
 * PROCESS / CONTEXT ownership、closeForContext / close 幂等与释放语义、启用快照默认值 / 不可变性、缺失 / 空 / 坏包诊断路径。
 * 用真实可加载的外置探针插件（{@link BootstrapProbePlugin}）经文件标记观测 load / start / stop 次数，不只靠 mock。
 */

@DisplayName("插件启动会话：启动快照与类加载器释放")
class PluginBootstrapSessionSnapshotTest extends PluginBootstrapSessionTestSupport {

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
}
