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

@DisplayName("插件启动会话：所有权、启动与失败收敛")
class PluginBootstrapSessionTest extends PluginBootstrapSessionTestSupport {

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
    @DisplayName("manager 构造严格晚于恢复结论：start 前不可取得，start 后保持唯一实例")
    void managerConstructedOnlyAfterRecoveryDecision() {
        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                tempDir.resolve("deferred-manager"), PluginEnabledSnapshot.empty());
        try {
            assertThatThrownBy(session::manager)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction recovery decision");

            session.start();

            assertThat(session.manager()).isSameAs(session.manager());
        } finally {
            session.close();
        }
    }

    @Test
    @DisplayName("事务恢复抛 JVM 致命错误时释放 installer 目录租约、关闭会话并原样重抛")
    void fatalTransactionRecoveryClosesInstallerBeforeRethrow() throws Exception {
        Path pluginsDir = tempDir.resolve("fatal-transaction-recovery");
        Files.createDirectories(pluginsDir);
        OutOfMemoryError fatal = new OutOfMemoryError("fatal transaction recovery");
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        PluginBootstrapSession session = new PluginBootstrapSession(
                pluginsDir, PluginBootstrapSession.Ownership.CONTEXT, PluginEnabledSnapshot.empty(),
                origin -> verifier, (root, resolver, installer) -> mock(PluginRuntimeManager.class),
                installer -> {
                    assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
                    throw fatal;
                });

        assertThatThrownBy(session::start).isSameAs(fatal);

        assertThat(session.isClosed()).isTrue();
        assertThat(session.isStarted()).isFalse();
        assertThatThrownBy(session::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(session::manager)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
        assertDirectoryLeaseReleased(pluginsDir);
    }

    @Test
    @DisplayName("manager 工厂抛运行时异常时收敛为诊断且会话仍可正常关闭")
    void managerFactoryRuntimeFailureConvergesToDiagnostics() throws Exception {
        Path pluginsDir = tempDir.resolve("manager-factory-runtime-failure");
        Files.createDirectories(pluginsDir);
        IllegalStateException failure = new IllegalStateException("simulated manager factory failure");
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        PluginBootstrapSession session = new PluginBootstrapSession(
                pluginsDir, PluginBootstrapSession.Ownership.CONTEXT, PluginEnabledSnapshot.empty(),
                origin -> verifier, (root, resolver, installer) -> {
                    throw failure;
                });

        assertThat(session.start()).isSameAs(session);

        assertThat(session.isStarted()).isTrue();
        assertThat(session.isClosed()).isFalse();
        assertThat(session.status().failures()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("manager construction failed"));
        assertThat(session.diagnostics()).singleElement()
                .asString().contains("simulated manager factory failure");
        assertThatThrownBy(session::manager)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");

        session.close();
        assertDirectoryLeaseReleased(pluginsDir);
    }

    @Test
    @DisplayName("manager 工厂抛普通 Error 时同样收敛为诊断")
    void managerFactoryNonFatalErrorConvergesToDiagnostics() throws Exception {
        Path pluginsDir = tempDir.resolve("manager-factory-error");
        Files.createDirectories(pluginsDir);
        AssertionError failure = new AssertionError("simulated manager factory error");
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        PluginBootstrapSession session = new PluginBootstrapSession(
                pluginsDir, PluginBootstrapSession.Ownership.CONTEXT, PluginEnabledSnapshot.empty(),
                origin -> verifier, (root, resolver, installer) -> {
                    throw failure;
                });

        assertThat(session.start()).isSameAs(session);

        assertThat(session.isStarted()).isTrue();
        assertThat(session.status().failures()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("manager construction failed"));
        session.close();
        assertDirectoryLeaseReleased(pluginsDir);
    }

    @Test
    @DisplayName("manager 工厂抛 JVM 致命错误时释放目录租约并原样重抛")
    void fatalManagerFactoryFailureClosesInstallerBeforeRethrow() throws Exception {
        Path pluginsDir = tempDir.resolve("fatal-manager-factory");
        Files.createDirectories(pluginsDir);
        OutOfMemoryError fatal = new OutOfMemoryError("fatal manager factory");
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        PluginBootstrapSession session = new PluginBootstrapSession(
                pluginsDir, PluginBootstrapSession.Ownership.CONTEXT, PluginEnabledSnapshot.empty(),
                origin -> verifier, (root, resolver, installer) -> {
                    throw fatal;
                });

        assertThatThrownBy(session::start).isSameAs(fatal);

        assertThat(session.isClosed()).isTrue();
        assertThat(session.isStarted()).isFalse();
        assertThatThrownBy(session::manager)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
        assertDirectoryLeaseReleased(pluginsDir);
    }

    @Test
    @DisplayName("manager 启动抛 JVM 致命错误时释放运行时与目录租约且会话不可复活")
    void fatalManagerStartClosesSessionBeforeRethrow() throws Exception {
        Path pluginsDir = tempDir.resolve("fatal-manager-start");
        Files.createDirectories(pluginsDir);
        PluginRuntimeManager runtimeManager = mock(PluginRuntimeManager.class);
        OutOfMemoryError fatal = new OutOfMemoryError("fatal manager start");
        when(runtimeManager.start()).thenThrow(fatal);
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        PluginBootstrapSession session = new PluginBootstrapSession(
                pluginsDir, PluginBootstrapSession.Ownership.CONTEXT, PluginEnabledSnapshot.empty(),
                origin -> verifier, (root, resolver, installer) -> runtimeManager);

        assertThatThrownBy(session::start).isSameAs(fatal);

        verify(runtimeManager).shutdown();
        assertThat(session.isClosed()).isTrue();
        assertThat(session.isStarted()).isFalse();
        assertThatThrownBy(session::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(session::manager)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
        assertDirectoryLeaseReleased(pluginsDir);
    }

    @Test
    @DisplayName("启动期清点抛 JVM 致命错误时释放已启动 manager 与目录租约并原样重抛")
    void fatalStartupDiscoveryClosesSessionBeforeRethrow() throws Exception {
        Path pluginsDir = tempDir.resolve("fatal-startup-discovery");
        Files.createDirectories(pluginsDir);
        PluginRuntimeManager runtimeManager = mock(PluginRuntimeManager.class);
        ThreadDeath fatal = new ThreadDeath();
        when(runtimeManager.start()).thenReturn(new PluginRuntimeStatus(
                pluginsDir, PluginDirectoryState.EMPTY, List.of(), List.of(), List.of()));
        when(runtimeManager.inspectPlugins()).thenThrow(fatal);
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        PluginBootstrapSession session = new PluginBootstrapSession(
                pluginsDir, PluginBootstrapSession.Ownership.CONTEXT, PluginEnabledSnapshot.empty(),
                origin -> verifier, (root, resolver, installer) -> runtimeManager);

        assertThatThrownBy(session::start).isSameAs(fatal);

        verify(runtimeManager).shutdown();
        assertThat(session.isClosed()).isTrue();
        assertThat(session.isStarted()).isFalse();
        assertThat(session.startupInventory().installations()).isEmpty();
        assertThat(session.startupDiscovery().discovered()).isEmpty();
        assertDirectoryLeaseReleased(pluginsDir);
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
        assertThat(session.manager().isPhysicalRuntimeInitialized()).isFalse();
        assertThat(Files.deleteIfExists(jar)).isTrue();
    }
}
