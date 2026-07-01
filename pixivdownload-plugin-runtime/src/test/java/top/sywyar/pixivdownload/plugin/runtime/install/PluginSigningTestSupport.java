package top.sywyar.pixivdownload.plugin.runtime.install;

import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;

final class PluginSigningTestSupport {

    private final String keyId;
    private final PrivateKey privateKey;
    private final TrustedPluginKey trustedKey;

    private PluginSigningTestSupport(String keyId, PrivateKey privateKey, TrustedPluginKey trustedKey) {
        this.keyId = keyId;
        this.privateKey = privateKey;
        this.trustedKey = trustedKey;
    }

    static PluginSigningTestSupport create() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(SignatureMetadata.ED25519);
            KeyPair keyPair = generator.generateKeyPair();
            String keyId = "test-key";
            TrustedPluginKey trustedKey = new TrustedPluginKey(
                    keyId,
                    SignatureMetadata.ED25519,
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    TrustedPluginKey.State.ACTIVE,
                    "Test Publisher",
                    "Test Trust",
                    false);
            return new PluginSigningTestSupport(keyId, keyPair.getPrivate(), trustedKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成测试签名密钥", e);
        }
    }

    PluginSupplyChainVerifier verifier() {
        return new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(trustedKey)));
    }

    PluginPackageOrigin originFor(String repositoryId, Path artifact, String pluginId, String version)
            throws IOException {
        long size = Files.size(artifact);
        String sha256 = Hashing.hex(Hashing.sha256(artifact));
        return PluginPackageOrigin.forTrustedCatalog(repositoryId, false, size, sha256,
                artifactSignature(artifact, pluginId, version));
    }

    SignatureMetadata artifactSignature(Path artifact, String pluginId, String version) throws IOException {
        byte[] sha256 = Hashing.sha256(artifact);
        byte[] message = EnvelopeV1Codec.artifactMessage(
                SignatureMetadata.ED25519, keyId, pluginId, version, Files.size(artifact), sha256);
        return new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION,
                SignatureMetadata.ED25519,
                keyId,
                Base64.getEncoder().encodeToString(sign(message)));
    }

    private byte[] sign(byte[] message) {
        try {
            Signature signature = Signature.getInstance(SignatureMetadata.ED25519);
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成测试签名", e);
        }
    }
}
