package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbeFeaturePlugin;
import top.sywyar.pixivdownload.bootstrapprobe.BackendRestartProbePlugin;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationProjector;

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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

@DisplayName("required 外置插件验签失败：PF4J 前阻断并进入恢复模式")
class PluginRequiredVerificationRecoveryTest {

    private static final String PLUGIN_ID = "bootstrap-probe";
    private static final String VERSION = "1.0.0";
    private static final RequiredPluginPolicy REQUIRED_POLICY = RequiredPluginPolicy.of(List.of(
            new RequiredPlugin(PLUGIN_ID, PluginApiRequirement.unspecified(), false, "plugin.recovery.blocked")));

    @TempDir
    Path tempDir;

    @AfterEach
    void clearMarkerProperty() {
        System.clearProperty("bootstrap.probe.marker");
    }

    @Test
    @DisplayName("required official 外置插件缺 sidecar：不加载，恢复模式 active")
    void requiredMissingSidecarEntersRecovery() throws Exception {
        Scenario scenario = scenario("missing-sidecar");

        startAndAssertRecovery(scenario, new PluginSupplyChainVerifier(), REQUIRED_POLICY);
    }

    @Test
    @DisplayName("required official 外置插件 UNKNOWN_KEY：不加载，恢复模式 active")
    void requiredUnknownKeyEntersRecovery() throws Exception {
        Scenario scenario = scenario("unknown-key");
        SignatureMetadata metadata = new SignatureMetadata(SignatureMetadata.FORMAT_VERSION,
                SignatureMetadata.ED25519, "unknown-key", "c2ln");
        writeOfficialProvenance(scenario, metadata, null,
                VerificationStatus.UNKNOWN_KEY, "UNKNOWN_KEY");

        startAndAssertRecovery(scenario, new PluginSupplyChainVerifier(), REQUIRED_POLICY);
    }

    @Test
    @DisplayName("required official 外置插件 REVOKED_KEY：不加载，恢复模式 active")
    void requiredRevokedKeyEntersRecovery() throws Exception {
        Scenario scenario = scenario("revoked-key");
        SigningFixture signing = SigningFixture.create("revoked-key", TrustedPluginKey.State.REVOKED);
        SignatureMetadata metadata = signing.artifactSignature(scenario.jar());
        writeOfficialProvenance(scenario, metadata, signing,
                VerificationStatus.VERIFIED, "VERIFIED");

        startAndAssertRecovery(scenario, signing.verifier(), REQUIRED_POLICY);
    }

    @Test
    @DisplayName("required official 外置插件 INVALID_SIGNATURE：不加载，恢复模式 active")
    void requiredInvalidSignatureEntersRecovery() throws Exception {
        Scenario scenario = scenario("invalid-signature");
        SigningFixture signing = SigningFixture.create("active-key", TrustedPluginKey.State.ACTIVE);
        SignatureMetadata metadata = new SignatureMetadata(SignatureMetadata.FORMAT_VERSION,
                SignatureMetadata.ED25519, signing.keyId(),
                Base64.getEncoder().encodeToString("bad-signature".getBytes(StandardCharsets.UTF_8)));
        writeOfficialProvenance(scenario, metadata, signing,
                VerificationStatus.VERIFIED, "VERIFIED");

        startAndAssertRecovery(scenario, signing.verifier(), REQUIRED_POLICY);
    }

    @Test
    @DisplayName("required official 外置插件 HASH_MISMATCH：写回 sidecar，管理投影不再显示已验证")
    void requiredHashMismatchEntersRecovery() throws Exception {
        Scenario scenario = scenario("hash-mismatch");
        SigningFixture signing = SigningFixture.create("active-key", TrustedPluginKey.State.ACTIVE);
        SignatureMetadata metadata = signing.artifactSignature(scenario.jar());
        writeOfficialProvenance(scenario, metadata, signing,
                "0000000000000000000000000000000000000000000000000000000000000000",
                VerificationStatus.VERIFIED, "VERIFIED");

        PluginRuntimeManager manager = start(scenario, signing.verifier());
        try {
            assertThat(manager.status().orElseThrow().startedPluginIds()).doesNotContain(PLUGIN_ID);
            assertThat(manager.status().orElseThrow().hasFailures()).isTrue();
            assertThat(Files.readString(scenario.marker(), StandardCharsets.UTF_8))
                    .as("验签失败必须发生在 PF4J 创建 classloader / 构造插件实例前")
                    .isEmpty();
            RecoveryModeService recovery = recovery(manager, scenario.pluginsDir(), REQUIRED_POLICY);
            assertThat(recovery.isActive()).isTrue();
            assertThat(recovery.decision().firstReason().orElseThrow().pluginId()).isEqualTo(PLUGIN_ID);

            PluginProvenanceRecord provenance =
                    new PluginProvenanceStore(scenario.pluginsDir()).read(scenario.jar()).orElseThrow();
            assertThat(provenance.offlineStatus()).isEqualTo(VerificationStatus.HASH_MISMATCH);
            assertThat(provenance.diagnosticCode()).isEqualTo("SHA256_MISMATCH");
            assertThat(provenance.keyId()).isEqualTo(signing.keyId());
            assertThat(provenance.publisher()).isEqualTo("Test Publisher");
            assertThat(provenance.trustLabel()).isEqualTo("Test Trust");
            assertThat(PluginVerificationProjector.fromProvenance(provenance).status())
                    .isEqualTo(PluginVerificationProjector.HASH_MISMATCH);
            assertThat(PluginVerificationProjector.fromProvenance(provenance).offlineReverifySuccess())
                    .isFalse();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("optional bad signature：不加载该插件，但不触发恢复模式")
    void optionalBadSignatureDoesNotEnterRecovery() throws Exception {
        Scenario scenario = scenario("optional-bad-signature");
        SignatureMetadata metadata = new SignatureMetadata(SignatureMetadata.FORMAT_VERSION,
                SignatureMetadata.ED25519, "unknown-key", "c2ln");
        writeOfficialProvenance(scenario, metadata, null,
                VerificationStatus.UNKNOWN_KEY, "UNKNOWN_KEY");

        PluginRuntimeManager manager = start(scenario, new PluginSupplyChainVerifier());
        try {
            assertThat(manager.status().orElseThrow().startedPluginIds()).doesNotContain(PLUGIN_ID);
            assertThat(manager.status().orElseThrow().hasFailures()).isTrue();
            assertThat(Files.readString(scenario.marker(), StandardCharsets.UTF_8)).isEmpty();
            RecoveryModeService recovery = recovery(manager, scenario.pluginsDir(), RequiredPluginPolicy.empty());
            assertThat(recovery.isActive()).isFalse();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("local unsigned optional：有显式本地 sidecar 时允许加载，并投影为 UNSIGNED_ALLOWED / 本地来源")
    void localUnsignedOptionalAllowedWithExplicitSidecar() throws Exception {
        Scenario scenario = scenario("local-unsigned");
        PluginTestProvenance.writeLocalUpload(scenario.pluginsDir(), scenario.jar(), PLUGIN_ID, VERSION);

        PluginRuntimeManager manager = start(scenario, new PluginSupplyChainVerifier());
        try {
            assertThat(manager.status().orElseThrow().startedPluginIds()).contains(PLUGIN_ID);
            PluginProvenanceRecord provenance =
                    new PluginProvenanceStore(scenario.pluginsDir()).read(scenario.jar()).orElseThrow();
            assertThat(provenance.offlineStatus()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
            assertThat(PluginVerificationProjector.fromProvenance(provenance).status())
                    .isEqualTo(PluginVerificationProjector.UNSIGNED_ALLOWED);
        } finally {
            manager.shutdown();
        }
    }

    private void startAndAssertRecovery(Scenario scenario, PluginSupplyChainVerifier verifier,
                                        RequiredPluginPolicy policy) throws IOException {
        PluginRuntimeManager manager = start(scenario, verifier);
        try {
            assertThat(manager.status().orElseThrow().startedPluginIds()).doesNotContain(PLUGIN_ID);
            assertThat(manager.status().orElseThrow().hasFailures()).isTrue();
            assertThat(Files.readString(scenario.marker(), StandardCharsets.UTF_8))
                    .as("验签失败必须发生在 PF4J 创建 classloader / 构造插件实例前")
                    .isEmpty();
            RecoveryModeService recovery = recovery(manager, scenario.pluginsDir(), policy);
            assertThat(recovery.isActive()).isTrue();
            assertThat(recovery.decision().firstReason().orElseThrow().pluginId()).isEqualTo(PLUGIN_ID);
        } finally {
            manager.shutdown();
        }
    }

    private static PluginRuntimeManager start(Scenario scenario, PluginSupplyChainVerifier verifier) {
        PluginRuntimeManager manager = new PluginRuntimeManager(scenario.pluginsDir(), verifier);
        manager.start();
        return manager;
    }

    private static RecoveryModeService recovery(PluginRuntimeManager manager, Path pluginsDir,
                                                RequiredPluginPolicy policy) {
        PluginRegistry registry = new PluginRegistry(List.of(), new PluginToggleProperties(),
                manager.discoverFeaturePlugins());
        ExternalPluginInstaller installer = new ExternalPluginInstaller(
                pluginsDir, PluginPackageLimits.defaults(), new PluginSupplyChainVerifier());
        PluginStatusService statusService = new PluginStatusService(registry, manager, installer, policy);
        return new RecoveryModeService(statusService, policy);
    }

    private Scenario scenario(String name) throws IOException {
        Path pluginsDir = tempDir.resolve(name).resolve("plugins");
        Path jar = stageProbeJar(pluginsDir);
        Path marker = tempDir.resolve(name + "-events.log");
        Files.createDirectories(marker.getParent());
        Files.createFile(marker);
        System.setProperty("bootstrap.probe.marker", marker.toString());
        return new Scenario(pluginsDir, jar, marker);
    }

    private static Path stageProbeJar(Path pluginsDir) throws IOException {
        Files.createDirectories(pluginsDir);
        Path jar = pluginsDir.resolve("bootstrap-probe-1.0.0.jar");
        String props = "plugin.id=" + PLUGIN_ID + "\nplugin.version=" + VERSION + "\nplugin.requires=1.0\n"
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

    private static void writeOfficialProvenance(Scenario scenario, SignatureMetadata metadata,
                                                SigningFixture signing, VerificationStatus status,
                                                String diagnostic) throws IOException {
        writeOfficialProvenance(scenario, metadata, signing,
                PluginPackageIntegrity.sha256Hex(scenario.jar()), status, diagnostic);
    }

    private static void writeOfficialProvenance(Scenario scenario, SignatureMetadata metadata,
                                                SigningFixture signing, String expectedSha,
                                                VerificationStatus status, String diagnostic) throws IOException {
        PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog("official", true,
                Files.size(scenario.jar()), expectedSha, metadata);
        VerificationResult result = new VerificationResult(status, PLUGIN_ID, VERSION,
                metadata != null ? metadata.keyId() : null,
                metadata != null ? metadata.algorithm() : null,
                signing != null ? "Test Publisher" : null,
                signing != null ? "Test Trust" : null,
                Instant.now(), Files.size(scenario.jar()), PluginPackageIntegrity.sha256Hex(scenario.jar()),
                diagnostic);
        new PluginProvenanceStore(scenario.pluginsDir()).write(scenario.jar(), origin, result);
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

    private record Scenario(Path pluginsDir, Path jar, Path marker) {
    }

    private record SigningFixture(String keyId, PrivateKey privateKey, TrustedPluginKey trustedKey) {

        static SigningFixture create(String keyId, TrustedPluginKey.State state) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(SignatureMetadata.ED25519);
                KeyPair keyPair = generator.generateKeyPair();
                TrustedPluginKey trustedKey = new TrustedPluginKey(keyId, SignatureMetadata.ED25519,
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                        state, "Test Publisher", "Test Trust", true);
                return new SigningFixture(keyId, keyPair.getPrivate(), trustedKey);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("failed to create required plugin test key", e);
            }
        }

        PluginSupplyChainVerifier verifier() {
            return new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(trustedKey)));
        }

        SignatureMetadata artifactSignature(Path artifact) throws IOException {
            byte[] sha256 = Hashing.sha256(artifact);
            byte[] message = EnvelopeV1Codec.artifactMessage(SignatureMetadata.ED25519, keyId,
                    PLUGIN_ID, VERSION, Files.size(artifact), sha256);
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
                throw new IllegalStateException("failed to sign required plugin test artifact", e);
            }
        }
    }
}
