package top.sywyar.pixivdownload.plugin;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSessionHandoff;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

public final class PluginTestProvenance {

    private static final String KEY_ID = "app-external-plugin-test-key";
    private static final String PUBLISHER = "App External Plugin Tests";
    private static final String TRUST_LABEL = "App Test Trust";
    private static final KeyPair KEY_PAIR = createKeyPair();
    private static final PluginSupplyChainVerifier VERIFIER = new PluginSupplyChainVerifier(PluginTrustStores.of(
            List.of(new TrustedPluginKey(KEY_ID, SignatureMetadata.ED25519,
                    Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()),
                    TrustedPluginKey.State.ACTIVE, PUBLISHER, TRUST_LABEL, true))));

    private PluginTestProvenance() {
    }

    public static void writeLocalUpload(Path pluginsDir, Path artifact, String pluginId, String version)
            throws IOException {
        VerificationResult result = new VerificationResult(VerificationStatus.UNSIGNED_ALLOWED,
                pluginId, version, null, null, null, null, Instant.now(), Files.size(artifact),
                PluginPackageIntegrity.sha256Hex(artifact), "UNSIGNED_ALLOWED");
        new PluginProvenanceStore(pluginsDir).write(artifact, PluginPackageOrigin.localUpload(), result);
    }

    public static void writeVerifiedLocalUpload(Path pluginsDir, Path artifact, String pluginId, String version)
            throws IOException {
        String sha256 = PluginPackageIntegrity.sha256Hex(artifact);
        SignatureMetadata metadata = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION,
                SignatureMetadata.ED25519,
                KEY_ID,
                Base64.getEncoder().encodeToString(sign(EnvelopeV1Codec.artifactMessage(
                        SignatureMetadata.ED25519,
                        KEY_ID,
                        pluginId,
                        version,
                        Files.size(artifact),
                        HexFormat.of().parseHex(sha256)))));
        VerificationResult result = new VerificationResult(
                VerificationStatus.VERIFIED,
                pluginId,
                version,
                KEY_ID,
                SignatureMetadata.ED25519,
                PUBLISHER,
                TRUST_LABEL,
                Instant.now(),
                Files.size(artifact),
                sha256,
                "VERIFIED");
        new PluginProvenanceStore(pluginsDir).write(
                artifact,
                PluginPackageOrigin.localUpload(metadata),
                result);
    }

    public static PluginSupplyChainVerifier verifier() {
        return VERIFIER;
    }

    public static final class VerifiedLocalPluginBootstrapInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            registerBootstrapSession(context, PluginEnabledSnapshot.empty());
        }
    }

    public static final class DisabledNovelPluginBootstrapInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            registerBootstrapSession(context, PluginEnabledSnapshot.ofDisabled(List.of("novel"), List.of()));
        }
    }

    private static void registerBootstrapSession(
            ConfigurableApplicationContext context,
            PluginEnabledSnapshot enabledSnapshot) {
        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                RuntimeFiles.pluginsDirectory(),
                enabledSnapshot,
                ignored -> VERIFIER);
        session.start();
        session.releaseStartupSnapshot();
        context.getBeanFactory().registerSingleton(
                "pluginBootstrapSessionHandoff",
                new PluginBootstrapSessionHandoff(session));
        context.addApplicationListener(event -> {
            if (event instanceof ContextRefreshedEvent refreshed
                    && refreshed.getApplicationContext() == context) {
                session.updateVerifierResolver(ignored -> VERIFIER);
            }
        });
    }

    private static KeyPair createKeyPair() {
        try {
            return KeyPairGenerator.getInstance(SignatureMetadata.ED25519).generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("failed to create app external plugin test key", failure);
        }
    }

    private static byte[] sign(byte[] message) {
        try {
            Signature signature = Signature.getInstance(SignatureMetadata.ED25519);
            signature.initSign(KEY_PAIR.getPrivate());
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("failed to sign app external plugin test artifact", failure);
        }
    }
}
