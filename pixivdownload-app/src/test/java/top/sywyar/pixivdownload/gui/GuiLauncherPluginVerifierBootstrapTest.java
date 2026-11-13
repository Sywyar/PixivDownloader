package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbeFeaturePlugin;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbePlugin;
import top.sywyar.pixivdownload.gui.config.PluginRepositoryConfigEditor;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.TrustedKeyConfigEntry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustPolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuiLauncher PROCESS bootstrap：config.yaml custom trusted key 在 start 前生效")
class GuiLauncherPluginVerifierBootstrapTest {

    @TempDir
    Path tempDir;

    @org.junit.jupiter.api.AfterEach
    void removePrivatePreparationCache() {
        Path prepared = tempDir.resolve("prepared-cache");
        if (Files.exists(prepared)) {
            assertThat(top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactSnapshot
                    .deleteWorkspaceQuietly(prepared)).isTrue();
        }
    }

    @Test
    @DisplayName("配置 custom trusted key：custom-signed installed plugin 可在 PROCESS bootstrap 启动")
    void processBootstrapUsesCustomTrustedKeyFromConfig() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins-with-key");
        Path jar = stageProbeJar(pluginsDir);
        SigningFixture signing = SigningFixture.create("gui-custom-key");
        writeSignedProvenance(pluginsDir, jar, signing);
        Path config = writeConfig(true, signing.publicKeyBase64());
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir,
                PluginEnabledSnapshot.empty(), GuiLauncher.readPluginVerifierResolver(config));
        try {
            session.start();

            assertThat(session.status().startedPluginIds()).contains("bootstrap-probe");
            assertThat(session.status().verifications())
                    .singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.result().status())
                            .isEqualTo(VerificationStatus.VERIFIED));
            assertThat(session.manager().generation("bootstrap-probe")).hasValue(1L);
        } finally {
            session.close();
        }
    }

    @Test
    @DisplayName("未配置 custom trusted key：同一 custom-signed plugin 在 PF4J classloader 创建前被阻断")
    void processBootstrapWithoutCustomTrustedKeyBlocksBeforeClassloader() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins-without-key");
        Path jar = stageProbeJar(pluginsDir);
        SigningFixture signing = SigningFixture.create("gui-custom-key");
        writeSignedProvenance(pluginsDir, jar, signing);
        Path config = writeConfig(false, signing.publicKeyBase64());
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir,
                PluginEnabledSnapshot.empty(), GuiLauncher.readPluginVerifierResolver(config));
        try {
            session.start();

            assertThat(session.status().startedPluginIds()).doesNotContain("bootstrap-probe");
            assertThat(session.status().hasFailures()).isTrue();
            assertThat(session.status().verifications())
                    .singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.result().status())
                            .isEqualTo(VerificationStatus.UNKNOWN_KEY));
            assertThat(session.manager().generation("bootstrap-probe"))
                    .as("缺少 custom trusted key 时必须在物理 generation / classloader 创建前阻断")
                    .isEmpty();
        } finally {
            session.close();
        }
    }

    @Test
    @DisplayName("跨会话命中扫描与准备缓存后仍拒绝已移除的签名信任密钥")
    void cachedScanDoesNotRetainPublisherTrust() throws Exception {
        Path plugins = tempDir.resolve("cached-trust");
        Path artifact = stageProbeJar(plugins);
        SigningFixture signing = SigningFixture.create("gui-custom-key");
        writeSignedProvenance(plugins, artifact, signing);
        Path config = writeConfig(true, signing.publicKeyBase64());
        Path cache = tempDir.toRealPath().resolve("scan-cache");
        Path prepared = tempDir.toRealPath().resolve("prepared-cache");
        try (var session = PluginBootstrapSession.createProcess(plugins,
                PluginEnabledSnapshot.empty(), GuiLauncher.readPluginVerifierResolver(config)).withScanCache(cache).withPreparationCache(prepared)) {
            session.start();
            assertThat(session.status().startedPluginIds()).contains("bootstrap-probe");
        }
        config = writeConfig(false, signing.publicKeyBase64());
        try (var verifier = org.mockito.Mockito.mockStatic(
                top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
             var session = PluginBootstrapSession.createProcess(plugins,
                     PluginEnabledSnapshot.empty(), GuiLauncher.readPluginVerifierResolver(config)).withScanCache(cache).withPreparationCache(prepared)) {
            session.start();
            assertThat(session.status().startedPluginIds()).isEmpty();
            assertThat(session.status().verifications()).singleElement().satisfies(snapshot ->
                    assertThat(snapshot.result().status()).isEqualTo(VerificationStatus.UNKNOWN_KEY));
            assertThat(session.manager().generation("bootstrap-probe")).isEmpty();
            verifier.verify(() -> top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier
                    .verifyAndMeasure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()),
                    org.mockito.Mockito.never());
        }
    }

    private Path writeConfig(boolean includeTrustedKey, String publicKey) throws IOException {
        Path config = tempDir.resolve(includeTrustedKey ? "with-key.yaml" : "without-key.yaml");
        Files.writeString(config, "plugin-catalog.enabled: true\nplugin-catalog.repositories:\n",
                StandardCharsets.UTF_8);
        List<TrustedKeyConfigEntry> trustedKeys = includeTrustedKey
                ? List.of(TrustedKeyConfigEntry.create("gui-custom-key", "Ed25519", publicKey, "ACTIVE",
                "GUI Test Publisher", "GUI Test Trust"))
                : List.of();
        new PluginRepositoryConfigEditor(config).write(List.of(new RepositoryConfigEntry(
                "custom", "", "https://example.invalid/manifest.json", true,
                "direct-strict", false, true, false, false,
                0, 0, 0, 0, trustedKeys, new LinkedHashMap<>())));
        return config;
    }

    private static Path stageProbeJar(Path pluginsDir) throws IOException {
        Files.createDirectories(pluginsDir);
        Path jar = pluginsDir.resolve("bootstrap-probe-1.0.0.jar");
        String props = "plugin.id=bootstrap-probe\nplugin.version=1.0.0\nplugin.requires=" + top.sywyar.pixivdownload.sdk.SdkVersion.VERSION + "\n"
                + "pixiv.execution-mode=host-process-full-trust\n"
                + "plugin.class=" + BackendRestartProbePlugin.class.getName() + "\n"
                + "plugin.provider=test\nplugin.description=bootstrap probe\n";
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("plugin.properties"));
            zos.write(props.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            addClassEntry(zos, BackendRestartProbePlugin.class);
            addClassEntry(zos, BackendRestartProbeFeaturePlugin.class);
        }
        return jar;
    }

    private static void writeSignedProvenance(Path pluginsDir, Path artifact, SigningFixture signing)
            throws IOException {
        SignatureMetadata metadata = signing.artifactSignature(artifact, "bootstrap-probe", "1.0.0");
        PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog("custom", false, Files.size(artifact),
                PluginPackageIntegrity.sha256Hex(artifact), metadata);
        VerificationResult result = new VerificationResult(VerificationStatus.VERIFIED,
                "bootstrap-probe", "1.0.0", signing.keyId(), SignatureMetadata.ED25519,
                "GUI Test Publisher", "GUI Test Trust",
                Hashing.hex(Hashing.sha256(Base64.getDecoder().decode(signing.publicKeyBase64()))),
                Instant.now(), Files.size(artifact),
                PluginPackageIntegrity.sha256Hex(artifact), "VERIFIED");
        PluginProvenanceRecord verified = PluginProvenanceRecord.from(origin, result);
        PluginProvenanceRecord approved = verified.withTrustDecision(PluginTrustPolicy.approve(
                PluginPackageReader.inspect(artifact, PluginPackageLimits.defaults()).descriptor(),
                verified,
                Instant.now()));
        new PluginProvenanceStore(pluginsDir).write(artifact, approved);
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

    private record SigningFixture(String keyId, PrivateKey privateKey, String publicKeyBase64) {

        static SigningFixture create(String keyId) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(SignatureMetadata.ED25519);
                KeyPair keyPair = generator.generateKeyPair();
                return new SigningFixture(keyId, keyPair.getPrivate(),
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("failed to create GUI verifier test key", e);
            }
        }

        SignatureMetadata artifactSignature(Path artifact, String pluginId, String version) throws IOException {
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
                throw new IllegalStateException("failed to sign GUI verifier test artifact", e);
            }
        }
    }
}
