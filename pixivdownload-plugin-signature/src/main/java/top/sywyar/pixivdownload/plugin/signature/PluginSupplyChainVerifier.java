package top.sywyar.pixivdownload.plugin.signature;

import top.sywyar.pixivdownload.plugin.signature.internal.ed25519.Ed25519Verifier;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * 插件 artifact 与 catalog manifest 共用的宿主 owned 验签门面。
 */
public final class PluginSupplyChainVerifier {

    private final PluginTrustStore trustStore;

    public PluginSupplyChainVerifier() {
        this(PluginTrustStores.builtInOfficial());
    }

    public PluginSupplyChainVerifier(PluginTrustStore trustStore) {
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
    }

    public VerificationResult verifyArtifact(ArtifactVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        VerificationPolicy policy = policy(request.policy());
        long size;
        byte[] sha256Bytes;
        String sha256Hex;
        try {
            if (request.artifactPath() == null || !Files.isRegularFile(request.artifactPath())) {
                return fail(VerificationStatus.IO_ERROR, request, null, 0L, null, "ARTIFACT_NOT_FOUND");
            }
            size = Files.size(request.artifactPath());
            sha256Bytes = Hashing.sha256(request.artifactPath());
            sha256Hex = Hashing.hex(sha256Bytes);
        } catch (IOException e) {
            return fail(VerificationStatus.IO_ERROR, request, null, 0L, null, "ARTIFACT_IO_ERROR");
        }

        if (request.expectedSizeBytes() != null && size != request.expectedSizeBytes()) {
            return fail(VerificationStatus.HASH_MISMATCH, request, null, size, sha256Hex, "SIZE_MISMATCH");
        }
        if (hasText(request.expectedSha256())
                && !sha256Hex.equalsIgnoreCase(request.expectedSha256().trim())) {
            return fail(VerificationStatus.HASH_MISMATCH, request, null, size, sha256Hex, "SHA256_MISMATCH");
        }
        if (!hasText(request.pluginId()) || !hasText(request.version())) {
            return fail(VerificationStatus.IDENTITY_MISMATCH, request, null, size, sha256Hex, "IDENTITY_MISSING");
        }
        return verifySignedEnvelope(request.signature(), policy, request.pluginId(), request.version(), size, sha256Bytes,
                sha256Hex, key -> EnvelopeV1Codec.artifactMessage(
                        request.signature().algorithm(),
                        request.signature().keyId(),
                        request.pluginId(),
                        request.version(),
                        size,
                        sha256Bytes),
                request);
    }

    public VerificationResult verifyManifest(ManifestVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        VerificationPolicy policy = policy(request.policy());
        byte[] bytes = request.manifestBytes();
        if (bytes == null) {
            return fail(VerificationStatus.IO_ERROR, null, null, 0L, null, "MANIFEST_MISSING");
        }
        byte[] sha256Bytes = Hashing.sha256(bytes);
        String sha256Hex = Hashing.hex(sha256Bytes);
        if (!hasText(request.repositoryId())) {
            return fail(VerificationStatus.IDENTITY_MISMATCH, null, null, bytes.length, sha256Hex,
                    "REPOSITORY_ID_MISSING");
        }
        return verifySignedEnvelope(request.signature(), policy, null, null, bytes.length, sha256Bytes, sha256Hex,
                key -> EnvelopeV1Codec.manifestMessage(request.repositoryId(), bytes.length, sha256Bytes),
                null);
    }

    private VerificationResult verifySignedEnvelope(SignatureMetadata metadata, VerificationPolicy policy,
                                                    String pluginId, String version, long size, byte[] sha256Bytes,
                                                    String sha256Hex, MessageFactory messageFactory,
                                                    ArtifactVerificationRequest artifactRequest) {
        if (metadata == null) {
            VerificationStatus status = policy.unsignedAllowed()
                    ? VerificationStatus.UNSIGNED_ALLOWED : VerificationStatus.SIGNATURE_REQUIRED;
            return new VerificationResult(status, pluginId, version, null, null, null, null, Instant.now(),
                    size, sha256Hex, status.name());
        }
        if (metadata.formatVersion() != SignatureMetadata.FORMAT_VERSION
                || !hasText(metadata.keyId()) || !hasText(metadata.value())) {
            return fail(VerificationStatus.MALFORMED_SIGNATURE, artifactRequest, metadata, size, sha256Hex,
                    "MALFORMED_SIGNATURE");
        }
        String algorithm = metadata.algorithm();
        if (!SignatureMetadata.ED25519.equals(algorithm)) {
            return fail(VerificationStatus.UNSUPPORTED_ALGORITHM, artifactRequest, metadata, size, sha256Hex,
                    "UNSUPPORTED_ALGORITHM");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(metadata.value().trim());
        } catch (IllegalArgumentException e) {
            return fail(VerificationStatus.MALFORMED_SIGNATURE, artifactRequest, metadata, size, sha256Hex,
                    "BAD_SIGNATURE_BASE64");
        }
        TrustedPluginKey key = trustStore.findByKeyId(metadata.keyId().trim()).orElse(null);
        if (key == null) {
            return fail(VerificationStatus.UNKNOWN_KEY, artifactRequest, metadata, size, sha256Hex, "UNKNOWN_KEY");
        }
        if (!SignatureMetadata.ED25519.equals(key.algorithm())) {
            return fail(VerificationStatus.UNSUPPORTED_ALGORITHM, artifactRequest, metadata, size, sha256Hex,
                    "UNSUPPORTED_KEY_ALGORITHM");
        }
        if (policy.officialTrustRequired() && !key.official()) {
            return fail(VerificationStatus.UNKNOWN_KEY, artifactRequest, metadata, size, sha256Hex,
                    "OFFICIAL_KEY_REQUIRED");
        }
        if (key.state() == TrustedPluginKey.State.REVOKED) {
            return new VerificationResult(VerificationStatus.REVOKED_KEY, pluginId, version, metadata.keyId(),
                    algorithm, key.publisher(), key.trustLabel(), Instant.now(), size, sha256Hex, "REVOKED_KEY");
        }
        if (key.state() == TrustedPluginKey.State.RETIRED && !policy.retiredKeysAllowed()) {
            return new VerificationResult(VerificationStatus.RETIRED_KEY, pluginId, version, metadata.keyId(),
                    algorithm, key.publisher(), key.trustLabel(), Instant.now(), size, sha256Hex,
                    "RETIRED_KEY_NOT_ALLOWED");
        }
        boolean valid = Ed25519Verifier.verify(key.publicKeySpkiBase64(), messageFactory.message(key), signature);
        if (!valid) {
            return new VerificationResult(VerificationStatus.INVALID_SIGNATURE, pluginId, version, metadata.keyId(),
                    algorithm, key.publisher(), key.trustLabel(), Instant.now(), size, sha256Hex,
                    "INVALID_SIGNATURE");
        }
        return new VerificationResult(VerificationStatus.VERIFIED, pluginId, version, metadata.keyId(), algorithm,
                key.publisher(), key.trustLabel(), Instant.now(), size, sha256Hex, "VERIFIED");
    }

    private static VerificationPolicy policy(VerificationPolicy policy) {
        return policy != null ? policy : VerificationPolicy.officialRepository();
    }

    private static VerificationResult fail(VerificationStatus status, ArtifactVerificationRequest request,
                                           SignatureMetadata metadata, long size, String sha256Hex,
                                           String diagnosticCode) {
        return new VerificationResult(status,
                request != null ? request.pluginId() : null,
                request != null ? request.version() : null,
                metadata != null ? metadata.keyId() : null,
                metadata != null ? metadata.algorithm() : null,
                null, null, Instant.now(), size, sha256Hex, diagnosticCode);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface MessageFactory {
        byte[] message(TrustedPluginKey key);
    }
}
