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

@DisplayName("插件启动会话：事务恢复与后续准入")
class PluginBootstrapSessionRecoveryGateTest extends PluginBootstrapSessionTestSupport {

    @Test
    @DisplayName("恢复待处理安装事务：start 时先恢复旧包（target 删除、backup 还原）、再扫描加载探针")
    void recoveryRunsBeforeScan() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        stageProbeJar(pluginsDir);
        Path oldPackage = PluginPackageFixtures.explodedZip(tempDir.resolve("recovery-old.zip"),
                "recovery-demo", "1.0.0", "1.0", "demo.Plugin");
        Path newPackage = PluginPackageFixtures.explodedZip(tempDir.resolve("recovery-new.zip"),
                "recovery-demo", "2.0.0", "1.0", "demo.Plugin");
        PreparedPluginTransaction prepared;
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(pluginsDir)) {
            assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
            installFully(installer, oldPackage);
            prepared = installer.prepareTransaction(
                    newPackage, false, PluginPackageOrigin.localUpload());
            installer.commitTransaction(prepared); // NEW_PLACED：启动恢复必须回滚到旧版本
        }

        PluginBootstrapSession session = PluginBootstrapSession.createContext(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        assertThat(pluginsDir.resolve("recovery-demo-2.0.0.zip")).doesNotExist();
        assertThat(pluginsDir.resolve("recovery-demo-1.0.0.zip")).exists();
        assertThat(prepared.transactionDirectory()).doesNotExist();
        // 同一次 start 内扫描已执行：探针已加载启动
        assertThat(session.status().startedPluginIds()).contains("bootstrap-probe");
        session.close();
    }

    @Test
    @DisplayName("恢复失败时保留坏事务并在 PF4J 扫描前整体 fail-closed")
    void unresolvedRecoveryPreventsPf4jScan() throws Exception {
        Path pluginsDir = tempDir.resolve("blocked-plugins");
        stageProbeJar(pluginsDir);
        Path marker = tempDir.resolve("blocked-probe-events.log");
        Files.createFile(marker);
        System.setProperty("bootstrap.probe.marker", marker.toString());
        Path transaction = pluginsDir.resolve(".staging").resolve("orphaned");
        Path retained = transaction.resolve("removed").resolve("0-bootstrap-probe.jar");
        Files.createDirectories(retained.getParent());
        Files.writeString(retained, "only-copy", StandardCharsets.UTF_8);

        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        assertThat(session.isStarted()).isTrue();
        assertThat(session.status().hasFailures()).isTrue();
        assertThat(session.status().loadedPluginIds()).isEmpty();
        assertThat(session.status().startedPluginIds()).isEmpty();
        assertThat(session.status().failures())
                .extracting(failure -> failure.reason())
                .anyMatch(reason -> reason.contains("MISSING_MANIFEST"));
        assertThat(session.diagnostics()).anyMatch(diagnostic -> diagnostic.contains("MISSING_MANIFEST"));
        assertThat(session.startupInventory().installations()).isEmpty();
        assertThat(session.startupDiscovery().discovered()).isEmpty();
        PluginRuntimeManager runtimeManager = session.manager();
        assertThat(runtimeManager).isSameAs(session.manager());
        assertThat(runtimeManager.isPhysicalRuntimeInitialized())
                .as("BLOCKED 恢复报告下 manager 必须保持 inert，不得创建 PF4J manager 或扫描")
                .isFalse();
        assertThat(runtimeManager.inspectPlugins().installations()).isEmpty();
        assertThatThrownBy(runtimeManager::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery is unsafe");
        assertThatThrownBy(() -> runtimeManager.loadPlugin(pluginsDir.resolve("0-bootstrap-probe.jar")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery is unsafe");
        assertThat(Files.readString(marker, StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readString(retained, StandardCharsets.UTF_8)).isEqualTo("only-copy");
        Path candidate = PluginPackageFixtures.explodedZip(tempDir.resolve("blocked-install.zip"),
                "blocked-install", "1.0.0", "1.0", "demo.Plugin");
        assertThatThrownBy(() -> session.installer().prepareTransaction(
                candidate, false, PluginPackageOrigin.localUpload()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery is unsafe");
        session.close();
    }

    @Test
    @DisplayName("启动时目录缺失后晚到的不安全事务必须在显式加载前补恢复并拒绝")
    void lateUnsafeTransactionAfterAbsentStartupBlocksExplicitLoad() throws Exception {
        Path pluginsDir = tempDir.resolve("late-created-plugins");
        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                pluginsDir, PluginEnabledSnapshot.empty());
        try {
            session.start();
            assertThat(session.status().state()).isEqualTo(PluginDirectoryState.ABSENT);
            assertThat(session.installer().recoverySafeForRuntime()).isTrue();

            Path marker = tempDir.resolve("late-created-probe-events.log");
            Files.createFile(marker);
            System.setProperty("bootstrap.probe.marker", marker.toString());
            Path probeJar = stageProbeJar(pluginsDir);
            Path retained = pluginsDir.resolve(".staging").resolve("late-orphan")
                    .resolve("removed").resolve("evidence.jar");
            Files.createDirectories(retained.getParent());
            Files.writeString(retained, "only-copy", StandardCharsets.UTF_8);

            assertThatThrownBy(() -> session.manager().loadPlugin(probeJar))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("recovery is unsafe");

            assertThat(session.installer().recoverySafeForRuntime()).isFalse();
            assertThat(session.installer().recoveryGateSnapshot().state())
                    .isEqualTo(PluginRecoveryGateState.BLOCKED);
            assertThat(session.installer().recoveryGateSnapshot().report().failures())
                    .extracting(failure -> failure.kind().name())
                    .containsExactly("MISSING_MANIFEST");
            assertThat(session.manager().isPhysicalRuntimeInitialized()).isFalse();
            assertThat(Files.readString(marker, StandardCharsets.UTF_8)).isEmpty();
            assertThat(Files.readString(retained, StandardCharsets.UTF_8)).isEqualTo("only-copy");
        } finally {
            session.close();
        }
    }

    @Test
    @DisplayName("启动时目录缺失后晚到的不安全事务也必须在开发目录显式加载前拒绝")
    void lateUnsafeTransactionAfterAbsentStartupBlocksDevelopmentLoad() throws Exception {
        Path repositoryRoot = tempDir.resolve("late-created-dev-repository");
        Path pluginsDir = repositoryRoot.resolve("plugins");
        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                pluginsDir, PluginEnabledSnapshot.empty());
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        try {
            session.start();
            assertThat(session.status().state()).isEqualTo(PluginDirectoryState.ABSENT);

            Path marker = tempDir.resolve("late-created-dev-probe-events.log");
            Files.createFile(marker);
            System.setProperty("bootstrap.probe.marker", marker.toString());
            Path classesDirectory = stageProbeDevelopmentClasses(repositoryRoot);
            Path retained = pluginsDir.resolve(".staging").resolve("late-dev-orphan")
                    .resolve("removed").resolve("evidence.jar");
            Files.createDirectories(retained.getParent());
            Files.writeString(retained, "only-copy", StandardCharsets.UTF_8);
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.setProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, repositoryRoot.toString());

            assertThatThrownBy(() -> session.manager().loadPlugin(classesDirectory))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("recovery is unsafe");

            assertThat(session.installer().recoverySafeForRuntime()).isFalse();
            assertThat(session.manager().isPhysicalRuntimeInitialized()).isFalse();
            assertThat(Files.readString(marker, StandardCharsets.UTF_8)).isEmpty();
            assertThat(Files.readString(retained, StandardCharsets.UTF_8)).isEqualTo("only-copy");
        } finally {
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
            session.close();
        }
    }

    @Test
    @DisplayName("开发 generation 加载后晚到的不安全事务必须在显式启动入口前拒绝")
    void lateUnsafeTransactionAfterDevelopmentLoadBlocksExplicitStart() throws Exception {
        Path repositoryRoot = tempDir.resolve("late-created-dev-start-repository");
        Path pluginsDir = repositoryRoot.resolve("plugins");
        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                pluginsDir, PluginEnabledSnapshot.empty());
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        try {
            session.start();
            assertThat(session.status().state()).isEqualTo(PluginDirectoryState.ABSENT);

            Path marker = tempDir.resolve("late-created-dev-start-events.log");
            Files.createFile(marker);
            System.setProperty("bootstrap.probe.marker", marker.toString());
            Path classesDirectory = stageProbeDevelopmentClasses(repositoryRoot);
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.setProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, repositoryRoot.toString());
            session.manager().loadPlugin(classesDirectory);

            Path retained = pluginsDir.resolve(".staging").resolve("late-dev-start-orphan")
                    .resolve("removed").resolve("evidence.jar");
            Files.createDirectories(retained.getParent());
            Files.writeString(retained, "only-copy", StandardCharsets.UTF_8);

            assertThatThrownBy(() -> session.manager().startPlugin("bootstrap-probe"))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("recovery is unsafe");

            assertThat(session.installer().recoverySafeForRuntime()).isFalse();
            assertThat(session.installer().recoveryGateSnapshot().state())
                    .isEqualTo(PluginRecoveryGateState.BLOCKED);
            assertThat(session.manager().packagePhases().get("bootstrap-probe"))
                    .isEqualTo(PluginRuntimePackagePhase.LOADED);
            assertThat(Files.readString(marker, StandardCharsets.UTF_8)).isEqualTo("load\n");
            assertThat(Files.readString(retained, StandardCharsets.UTF_8)).isEqualTo("only-copy");
        } finally {
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
            session.close();
        }
    }

    @Test
    @DisplayName("开发模式启动前晚到的不安全事务必须补恢复并阻止插件入口启动")
    void lateUnsafeTransactionBeforeDevelopmentStartupBlocksEntryStart() throws Exception {
        Path repositoryRoot = tempDir.resolve("late-dev-start-repository");
        Path pluginsDir = repositoryRoot.resolve("plugins");
        Path marker = tempDir.resolve("late-dev-start-events.log");
        Files.createFile(marker);
        System.setProperty("bootstrap.probe.marker", marker.toString());
        stageProbeDevelopmentClasses(repositoryRoot);
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        Path retained = pluginsDir.resolve(".staging").resolve("late-start-orphan")
                .resolve("removed").resolve("evidence.jar");
        PluginBootstrapSession session = new PluginBootstrapSession(
                pluginsDir, PluginBootstrapSession.Ownership.CONTEXT, PluginEnabledSnapshot.empty(),
                origin -> verifier, (root, resolver, installer) -> {
                    try {
                        Files.createDirectories(retained.getParent());
                        Files.writeString(retained, "only-copy", StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new IllegalStateException("failed to arrange late transaction", e);
                    }
                    return new PluginRuntimeManager(root, resolver) {
                        @Override
                        protected void beforeProductionScan(Path directory) throws IOException {
                            try {
                                installer.prepareRuntimeScan();
                            } catch (IllegalStateException e) {
                                throw new IOException("plugin directory is not safe to scan", e);
                            }
                        }
                    };
                });
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.setProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, repositoryRoot.toString());

            session.start();

            assertThat(session.installer().recoveryGateSnapshot().state())
                    .isEqualTo(PluginRecoveryGateState.BLOCKED);
            assertThat(session.status().hasFailures()).isTrue();
            assertThat(session.manager().isPhysicalRuntimeInitialized()).isFalse();
            assertThat(Files.readString(marker, StandardCharsets.UTF_8)).isEmpty();
            assertThat(Files.readString(retained, StandardCharsets.UTF_8)).isEqualTo("only-copy");
        } finally {
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
            session.close();
        }
    }
}
