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

@DisplayName("插件启动会话：离线复验与关闭所有权")
class PluginBootstrapSessionVerificationTest extends PluginBootstrapSessionTestSupport {

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
}
