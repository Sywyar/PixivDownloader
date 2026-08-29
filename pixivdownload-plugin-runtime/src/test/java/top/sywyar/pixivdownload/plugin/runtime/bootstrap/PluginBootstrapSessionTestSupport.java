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
import top.sywyar.pixivdownload.runtimeprobe.BootstrapProbeFeaturePlugin;
import top.sywyar.pixivdownload.runtimeprobe.BootstrapProbePlugin;
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
abstract class PluginBootstrapSessionTestSupport {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearMarkerProperty() {
        System.clearProperty("bootstrap.probe.marker");
    }

    protected static PluginBootstrapSession createContext(
            Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot) {
        return createContext(pluginsRoot, enabledSnapshot, new PluginSupplyChainVerifier());
    }

    protected static PluginBootstrapSession createContext(
            Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot, PluginSupplyChainVerifier verifier) {
        return new PluginBootstrapSession(
                pluginsRoot, PluginBootstrapSession.Ownership.CONTEXT, enabledSnapshot,
                ignored -> verifier, () -> true);
    }

    protected static PluginBootstrapSession createProcess(
            Path pluginsRoot, PluginEnabledSnapshot enabledSnapshot) {
        return new PluginBootstrapSession(
                pluginsRoot, PluginBootstrapSession.Ownership.PROCESS, enabledSnapshot,
                ignored -> new PluginSupplyChainVerifier(), () -> true);
    }

    // ── startup snapshot 短生命周期 + classloader 释放 ──────────────────────────

    protected static PluginInstallResult installFully(ExternalPluginInstaller installer, Path packagePath) {
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

    protected static void assertDirectoryLeaseReleased(Path pluginsDir) throws IOException {
        try (PluginDirectorySessionLock replacement = new PluginDirectorySessionLock(pluginsDir)) {
            replacement.acquireForMutation();
            assertThat(replacement.held()).isTrue();
        }
    }

    protected static ReloadProbe reloadAfterReleasingSnapshot(
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

    protected record ReloadProbe(
            WeakReference<ClassLoader> oldClassLoader, long oldGeneration, long newGeneration) {
    }
    /** 探针功能插件在启动期 inventory 中的 classloader（PF4J 插件 classloader）。 */
    protected static ClassLoader probeClassLoader(PluginBootstrapSession session) {
        return session.startupInventory().installations().stream()
                .filter(i -> "bootstrap-probe".equals(i.id()))
                .map(i -> i.classLoader())
                .findFirst()
                .orElseThrow(() -> new AssertionError("bootstrap-probe not in startup inventory"));
    }

    /** best-effort GC 探针：反复触发 GC + 内存压力，返回弱引用是否已被清除。镜像 app 侧 ClassLoaderLeakProbes 约定。 */
    protected static boolean awaitCollected(WeakReference<?> ref) {
        for (int i = 0; i < 25 && ref.get() != null; i++) {
            provokeGc();
            if (ref.get() == null) {
                break;
            }
            sleepQuietly(40L);
        }
        return ref.get() == null;
    }

    protected static void provokeGc() {
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

    protected static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- helpers ---

    /** 把 {@link BootstrapProbePlugin} + {@link BootstrapProbeFeaturePlugin} 编译产物组装成 PF4J 可加载的 thin 插件 jar。 */
    protected static Path stageProbeJar(Path pluginsDir) throws IOException {
        Path jar = stageProbeJarWithoutProvenance(pluginsDir);
        writeLocalProvenance(pluginsDir, jar);
        return jar;
    }

    protected static Path stageProbeJarWithoutProvenance(Path pluginsDir) throws IOException {
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

    protected static Path stageProbeDevelopmentClasses(Path repositoryRoot) throws IOException {
        Path moduleRoot = repositoryRoot.resolve("pixivdownload-plugin-bootstrap-probe");
        String properties = "plugin.id=bootstrap-probe\nplugin.version=1.0.0\nplugin.requires=1.0\n"
                + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n";
        Path sourceResources = moduleRoot.resolve("src/main/resources");
        Files.createDirectories(sourceResources);
        Files.writeString(sourceResources.resolve("plugin.properties"), properties, StandardCharsets.UTF_8);
        Path classesDirectory = moduleRoot.resolve("target/classes");
        Files.createDirectories(classesDirectory);
        Files.writeString(classesDirectory.resolve("plugin.properties"), properties, StandardCharsets.UTF_8);
        copyClassFile(classesDirectory, BootstrapProbePlugin.class);
        copyClassFile(classesDirectory, BootstrapProbeFeaturePlugin.class);
        return classesDirectory;
    }

    protected static void copyClassFile(Path classesDirectory, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        Path target = classesDirectory.resolve(entry);
        Files.createDirectories(target.getParent());
        try (InputStream in = type.getResourceAsStream("/" + entry)) {
            assertThat(in).as("class resource must be compiled: " + type.getName()).isNotNull();
            Files.copy(in, target);
        }
    }

    protected static void writeLocalProvenance(Path pluginsDir, Path jar) throws IOException {
        VerificationResult result = new VerificationResult(VerificationStatus.UNSIGNED_ALLOWED,
                "bootstrap-probe", "1.0.0", null, null, null, null, Instant.now(), Files.size(jar),
                PluginPackageIntegrity.sha256Hex(jar), "UNSIGNED_ALLOWED");
        new PluginProvenanceStore(pluginsDir).write(jar, PluginPackageOrigin.localUpload(), result);
    }

    protected static void addClassEntry(ZipOutputStream zos, Class<?> type) throws IOException {
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

    protected static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    protected static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    protected static final class SigningFixture {

        protected final String keyId;
        protected final PrivateKey privateKey;
        protected final TrustedPluginKey trustedKey;

        protected SigningFixture(String keyId, PrivateKey privateKey, TrustedPluginKey trustedKey) {
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

        protected SignatureMetadata artifactSignature(Path artifact, String pluginId, String version)
                throws IOException {
            byte[] sha256 = Hashing.sha256(artifact);
            byte[] message = EnvelopeV1Codec.artifactMessage(SignatureMetadata.ED25519, keyId,
                    pluginId, version, Files.size(artifact), sha256);
            return new SignatureMetadata(SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, keyId,
                    Base64.getEncoder().encodeToString(sign(message)));
        }

        protected byte[] sign(byte[] message) {
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
