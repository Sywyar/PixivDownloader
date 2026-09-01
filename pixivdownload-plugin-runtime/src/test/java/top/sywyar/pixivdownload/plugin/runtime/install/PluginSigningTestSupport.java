package top.sywyar.pixivdownload.plugin.runtime.install;

import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.RepositoryIdentityMigrationAuthorization;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
        return create("test-key", "Test Publisher", false);
    }

    static PluginSigningTestSupport createOfficial() {
        return create("test-key", "Test Publisher", true);
    }

    static PluginSigningTestSupport create(String keyId, String publisher, boolean official) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(SignatureMetadata.ED25519);
            KeyPair keyPair = generator.generateKeyPair();
            TrustedPluginKey trustedKey = new TrustedPluginKey(
                    keyId,
                    SignatureMetadata.ED25519,
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    TrustedPluginKey.State.ACTIVE,
                    publisher,
                    "Test Trust",
                    official);
            return new PluginSigningTestSupport(keyId, keyPair.getPrivate(), trustedKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成测试签名密钥", e);
        }
    }

    static PluginSupplyChainVerifier verifierFor(PluginSigningTestSupport... signers) {
        return new PluginSupplyChainVerifier(PluginTrustStores.of(
                Arrays.stream(signers).map(signer -> signer.trustedKey).toList()));
    }

    PluginSupplyChainVerifier verifier() {
        return new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(trustedKey)));
    }

    TrustedPluginKey trustedKey(TrustedPluginKey.State state) {
        return new TrustedPluginKey(
                trustedKey.keyId(),
                trustedKey.algorithm(),
                trustedKey.publicKeySpkiBase64(),
                state,
                trustedKey.publisher(),
                trustedKey.trustLabel(),
                trustedKey.official());
    }

    PluginPackageOrigin originFor(String repositoryId, Path artifact, String pluginId, String version)
            throws IOException {
        return originFor(repositoryId, false, artifact, pluginId, version, Map.of());
    }

    PluginPackageOrigin originFor(
            String repositoryId,
            boolean officialRepository,
            Path artifact,
            String pluginId,
            String version) throws IOException {
        return originFor(repositoryId, officialRepository, artifact, pluginId, version, Map.of());
    }

    PluginPackageOrigin originFor(
            String repositoryId,
            Path artifact,
            String pluginId,
            String version,
            Map<String, SignatureMetadata> identityMigrationSignatures) throws IOException {
        return originFor(repositoryId, false, artifact, pluginId, version, identityMigrationSignatures);
    }

    PluginPackageOrigin originFor(
            String repositoryId,
            boolean officialRepository,
            Path artifact,
            String pluginId,
            String version,
            Map<String, SignatureMetadata> identityMigrationSignatures) throws IOException {
        long size = Files.size(artifact);
        String sha256 = Hashing.hex(Hashing.sha256(artifact));
        return PluginPackageOrigin.forTrustedCatalog(repositoryId, officialRepository, size, sha256,
                artifactSignature(artifact, pluginId, version), identityMigrationSignatures);
    }

    PluginPackageOrigin originFor(
            String repositoryId,
            Path artifact,
            String pluginId,
            String version,
            Map<String, SignatureMetadata> identityMigrationSignatures,
            Map<String, RepositoryIdentityMigrationAuthorization> repositoryIdentityMigrationAuthorizations,
            boolean identityMigrationConfirmed) throws IOException {
        long size = Files.size(artifact);
        String sha256 = Hashing.hex(Hashing.sha256(artifact));
        return PluginPackageOrigin.forTrustedCatalog(
                repositoryId,
                false,
                size,
                sha256,
                artifactSignature(artifact, pluginId, version),
                identityMigrationSignatures,
                repositoryIdentityMigrationAuthorizations,
                identityMigrationConfirmed);
    }

    PluginPackageOrigin confirmed(PluginPackageOrigin origin) {
        return PluginPackageOrigin.forTrustedCatalog(
                origin.repositoryId(),
                origin.officialRepository(),
                origin.expectedSizeBytes(),
                origin.expectedSha256(),
                origin.signature(),
                origin.identityMigrationSignatures(),
                origin.repositoryIdentityMigrationAuthorizations(),
                origin.identityMigrationConfirmed(),
                origin.expectedSha256());
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

    SignatureMetadata identityMigrationSignature(
            String fromPluginId,
            String fromRepositoryId,
            String toPluginId,
            String toRepositoryId,
            PluginSigningTestSupport toSigner,
            Path artifact,
            String version) throws IOException {
        return identityMigrationSignature(
                fromPluginId, fromRepositoryId, false,
                toPluginId, toRepositoryId, false,
                toSigner, artifact, version);
    }

    SignatureMetadata identityMigrationSignature(
            String fromPluginId,
            String fromRepositoryId,
            boolean fromOfficialRepository,
            String toPluginId,
            String toRepositoryId,
            boolean toOfficialRepository,
            PluginSigningTestSupport toSigner,
            Path artifact,
            String version) throws IOException {
        byte[] sha256 = Hashing.sha256(artifact);
        byte[] message = EnvelopeV1Codec.identityMigrationMessage(
                SignatureMetadata.ED25519,
                keyId,
                fromPluginId,
                "MARKET_CATALOG",
                fromRepositoryId,
                fromOfficialRepository,
                trustedKey.publisher(),
                keyId,
                toPluginId,
                "MARKET_CATALOG",
                toRepositoryId,
                toOfficialRepository,
                toSigner.trustedKey.publisher(),
                toSigner.keyId,
                version,
                Files.size(artifact),
                sha256);
        return new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION,
                SignatureMetadata.ED25519,
                keyId,
                Base64.getEncoder().encodeToString(sign(message)));
    }

    RepositoryIdentityMigrationAuthorization repositoryIdentityMigrationAuthorization(
            String reason,
            String fromPluginId,
            String fromRepositoryId,
            PluginSigningTestSupport fromSigner,
            String toPluginId,
            String toRepositoryId,
            PluginSigningTestSupport toSigner,
            Path artifact,
            String version) throws IOException {
        byte[] sha256 = Hashing.sha256(artifact);
        byte[] message = EnvelopeV1Codec.repositoryIdentityMigrationMessage(
                SignatureMetadata.ED25519,
                keyId,
                reason,
                fromPluginId,
                "MARKET_CATALOG",
                fromRepositoryId,
                false,
                fromSigner.trustedKey.publisher(),
                fromSigner.keyId,
                toPluginId,
                "MARKET_CATALOG",
                toRepositoryId,
                false,
                toSigner.trustedKey.publisher(),
                toSigner.keyId,
                version,
                Files.size(artifact),
                sha256);
        return new RepositoryIdentityMigrationAuthorization(
                reason,
                new SignatureMetadata(
                        SignatureMetadata.FORMAT_VERSION,
                        SignatureMetadata.ED25519,
                        keyId,
                        Base64.getEncoder().encodeToString(sign(message))));
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
