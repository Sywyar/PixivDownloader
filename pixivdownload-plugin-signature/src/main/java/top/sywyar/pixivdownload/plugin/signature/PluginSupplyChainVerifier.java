package top.sywyar.pixivdownload.plugin.signature;

import top.sywyar.pixivdownload.plugin.signature.internal.ed25519.Ed25519Verifier;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;
import top.sywyar.pixivdownload.plugin.signature.internal.trust.KeyParsing;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityPackageVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityDirectoryVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityOperationVerificationRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 插件 artifact 与 catalog manifest 共用的宿主 owned 验签门面。
 */
public final class PluginSupplyChainVerifier {

    private final PluginTrustStore trustStore;

    public PluginSupplyChainVerifier() {
        this(PluginTrustStores.builtInOfficialPlugins());
    }

    public PluginSupplyChainVerifier(PluginTrustStore trustStore) {
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
    }

    /** 从工件字节计算的实际摘要，独立于来源记录中的预期摘要。 */
    public record MeasuredArtifact(long size, String sha256) {
        public MeasuredArtifact {
            if (size < 0 || !lowercaseSha256(sha256)) throw new IllegalArgumentException("invalid measured artifact identity");
        }
    }

    public VerificationResult verifyArtifact(ArtifactVerificationRequest request) {
        return verifyArtifact(request, null);
    }

    public VerificationResult verifyArtifact(ArtifactVerificationRequest request, MeasuredArtifact measured) {
        Objects.requireNonNull(request, "request");
        if (measured == null) {
            if (request.artifactPath() == null || !Files.isRegularFile(request.artifactPath())) {
                return fail(VerificationStatus.IO_ERROR, request, null, 0L, null, "ARTIFACT_NOT_FOUND");
            }
            try {
                measured = measure(request.artifactPath());
            } catch (IOException e) {
                return fail(VerificationStatus.IO_ERROR, request, null, 0L, null, "ARTIFACT_IO_ERROR");
            }
        }
        long size = measured.size();
        String sha256Hex = measured.sha256();

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
        return verifyArtifactEnvelope(request, size, sha256Hex, HexFormat.of().parseHex(sha256Hex));
    }

    /** 校验目录中的制品身份签名；本机字节摘要由已完成的安装清点独立提供。 */
    public boolean verifiesOfficialArtifactIdentity(String pluginId, String version, long size,
                                                   String sha256, SignatureMetadata signature) {
        if (signature == null || size <= 0 || sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) return false;
        var request = new ArtifactVerificationRequest(null, pluginId, version, size, sha256, signature,
                VerificationPolicy.officialRepository());
        return verifyArtifactEnvelope(request, size, sha256, HexFormat.of().parseHex(sha256)).accepted();
    }

    private VerificationResult verifyArtifactEnvelope(ArtifactVerificationRequest request, long size,
                                                      String sha256Hex, byte[] sha256Bytes) {
        return verifySignedEnvelope(request.signature(), policy(request.policy()), request.pluginId(), request.version(), size,
                sha256Hex, () -> EnvelopeV1Codec.artifactMessage(
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
        return verifySignedEnvelope(request.signature(), policy, null, null, bytes.length, sha256Hex,
                () -> EnvelopeV1Codec.manifestMessage(request.repositoryId(), bytes.length, sha256Bytes),
                null);
    }

    public VerificationResult verifyRepositoryUpdate(RepositoryUpdateVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        return verifySignedDocument(request.documentBytes(), request.repositoryId(), request.sequence(),
                request.signature(), request.policy(), true);
    }

    public VerificationResult verifyPluginRevocations(PluginRevocationsVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        return verifySignedDocument(request.documentBytes(), request.repositoryId(), request.sequence(),
                request.signature(), request.policy(), false);
    }

    private static MeasuredArtifact measure(Path path) throws IOException {
        return new MeasuredArtifact(Files.size(path), Hashing.hex(Hashing.sha256(path)));
    }

    /** 验证社区签名与实际包；不替代发布者原签、审核关联和来源连续性。 */
    public VerificationResult verifyCommunityPackage(CommunityPackageVerificationRequest request) {
        return verifyCommunityPackage(request, null);
    }

    public VerificationResult verifyCommunityPackage(CommunityPackageVerificationRequest request, MeasuredArtifact measured) {
        Objects.requireNonNull(request, "request");
        ArtifactVerificationRequest artifact = new ArtifactVerificationRequest(request.artifactPath(),
                request.pluginId(), request.version(), request.expectedSizeBytes(), request.expectedSha256(),
                request.signature(), null);
        if (!hasText(request.repositoryId()) || !hasText(request.pluginId()) || !hasText(request.version())
                || !hasText(request.assuranceLevel()) || !hasText(request.sourceCommit())
                || !lowercaseSha256(request.reviewRecordSha256()) || !lowercaseSha256(request.expectedSha256())
                || request.expectedSizeBytes() <= 0) {
            return fail(VerificationStatus.IDENTITY_MISMATCH, artifact, request.signature(), 0, null,
                    "COMMUNITY_PACKAGE_IDENTITY_INVALID");
        }
        if (measured == null) {
            if (request.artifactPath() == null || !Files.isRegularFile(request.artifactPath())) {
                return fail(VerificationStatus.IO_ERROR, artifact, request.signature(), 0, null, "ARTIFACT_NOT_FOUND");
            }
            try {
                measured = measure(request.artifactPath());
            } catch (IOException e) {
                return fail(VerificationStatus.IO_ERROR, artifact, request.signature(), 0, null, "ARTIFACT_IO_ERROR");
            }
        }
        long size = measured.size();
        String hex = measured.sha256();
        byte[] hash = HexFormat.of().parseHex(hex);
        if (size != request.expectedSizeBytes() || !hex.equals(request.expectedSha256())) {
            return fail(VerificationStatus.HASH_MISMATCH, artifact, request.signature(), size, hex,
                    size != request.expectedSizeBytes() ? "SIZE_MISMATCH" : "SHA256_MISMATCH");
        }
        return verifyCommunityEnvelope(request.signature(), request.retiredKeysAllowed(), request.pluginId(),
                request.version(), size, hash, () -> EnvelopeV1Codec.communityPackageMessage(
                        request.signature().algorithm(), request.signature().keyId(), request.repositoryId(),
                        request.pluginId(), request.version(), size, hash, request.assuranceLevel(),
                        request.sourceCommit(), HexFormat.of().parseHex(request.reviewRecordSha256())), artifact);
    }

    /** 只验证当前提供的目录快照；调用者另行核对单调序号及完整 generation。 */
    public VerificationResult verifyCommunityDirectory(CommunityDirectoryVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] bytes = request.documentBytes();
        if (bytes == null) return fail(VerificationStatus.IO_ERROR, null, request.signature(), 0, null, "DOCUMENT_MISSING");
        byte[] hash = Hashing.sha256(bytes);
        if (!hasText(request.repositoryId()) || request.sequence() <= 0) {
            return fail(VerificationStatus.IDENTITY_MISMATCH, null, request.signature(), bytes.length,
                    Hashing.hex(hash), "DOCUMENT_IDENTITY_INVALID");
        }
        return verifyCommunityEnvelope(request.signature(), request.retiredKeysAllowed(), null, null,
                bytes.length, hash, () -> EnvelopeV1Codec.communityDirectoryMessage(
                        request.repositoryId(), request.sequence(), bytes.length, hash), null);
    }

    /** 验证动作专属证明；不会把同一证明用于另一类操作。 */
    public VerificationResult verifyCommunityOperation(CommunityOperationVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] bytes = request.canonicalBytes();
        if (bytes == null) return fail(VerificationStatus.IO_ERROR, null, request.signature(), 0, null, "DOCUMENT_MISSING");
        byte[] hash = Hashing.sha256(bytes);
        String hex = Hashing.hex(hash);
        if (request.operation() == null || !lowercaseSha256(request.requestId())) {
            return fail(VerificationStatus.IDENTITY_MISMATCH, null, request.signature(), bytes.length, hex,
                    "COMMUNITY_OPERATION_IDENTITY_INVALID");
        }
        if (!hex.equals(request.requestId())) {
            return fail(VerificationStatus.HASH_MISMATCH, null, request.signature(), bytes.length, hex,
                    "REQUEST_ID_MISMATCH");
        }
        return verifyCommunityEnvelope(request.signature(), request.retiredKeysAllowed(), null, null,
                bytes.length, hash, () -> EnvelopeV1Codec.communityOperationMessage(request.operation(),
                        request.signature().algorithm(), request.signature().keyId(), bytes.length, hash), null);
    }

    private VerificationResult verifyCommunityEnvelope(SignatureMetadata metadata, boolean allowRetired,
            String pluginId, String version, long size, byte[] hash, Supplier<byte[]> message,
            ArtifactVerificationRequest artifact) {
        String hex = Hashing.hex(hash);
        if (metadata != null) {
            if (!SignatureMetadata.ED25519.equals(metadata.algorithm())) {
                return fail(VerificationStatus.UNSUPPORTED_ALGORITHM, artifact, metadata, size, hex,
                        "UNSUPPORTED_ALGORITHM");
            }
            try {
                byte[] signature = Base64.getDecoder().decode(metadata.value());
                if (metadata.formatVersion() != SignatureMetadata.FORMAT_VERSION || signature.length != 64
                        || !Base64.getEncoder().encodeToString(signature).equals(metadata.value())
                        || !hasText(metadata.keyId()) || !metadata.keyId().equals(metadata.keyId().trim())) {
                    throw new IllegalArgumentException("noncanonical signature");
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                return fail(VerificationStatus.MALFORMED_SIGNATURE, artifact, metadata, size, hex,
                        "MALFORMED_SIGNATURE");
            }
            TrustedPluginKey key = trustStore.findByKeyId(metadata.keyId()).orElse(null);
            if (key != null) {
                if (OfficialArtifactTrustRoots.isOfficialKey(key)) {
                    return fail(VerificationStatus.UNKNOWN_KEY, artifact, metadata, size, hex,
                            "COMMUNITY_KEY_REQUIRED");
                }
                try {
                    KeyParsing.canonicalEd25519PublicKey(key.publicKeySpkiBase64());
                } catch (IllegalArgumentException e) {
                    return fail(VerificationStatus.MALFORMED_SIGNATURE, artifact, metadata, size, hex,
                            "MALFORMED_PUBLIC_KEY");
                }
            }
        }
        return verifySignedEnvelope(metadata, new VerificationPolicy(true, false, false, allowRetired, "community"),
                pluginId, version, size, hex, message, artifact);
    }

    private static boolean lowercaseSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private VerificationResult verifySignedDocument(byte[] bytes, String repositoryId, long sequence,
                                                    SignatureMetadata signature, VerificationPolicy requestedPolicy,
                                                    boolean repositoryUpdate) {
        VerificationPolicy effectivePolicy = policy(requestedPolicy);
        if (bytes == null) {
            return fail(VerificationStatus.IO_ERROR, null, null, 0L, null, "DOCUMENT_MISSING");
        }
        byte[] sha256Bytes = Hashing.sha256(bytes);
        String sha256Hex = Hashing.hex(sha256Bytes);
        if (!hasText(repositoryId) || sequence <= 0L) {
            return fail(VerificationStatus.IDENTITY_MISMATCH, null, signature, bytes.length, sha256Hex,
                    "DOCUMENT_IDENTITY_INVALID");
        }
        return verifySignedEnvelope(signature, effectivePolicy, null, null, bytes.length, sha256Hex,
                () -> repositoryUpdate
                        ? EnvelopeV1Codec.repositoryUpdateMessage(repositoryId, sequence, bytes.length, sha256Bytes)
                        : EnvelopeV1Codec.pluginRevocationsMessage(repositoryId, sequence, bytes.length, sha256Bytes),
                null);
    }

    /**
     * 复用旧来源 trust store，验证旧 key 是否授权候选制品迁移到精确的新身份。
     */
    public VerificationResult verifyIdentityMigration(IdentityMigrationVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        IdentityMigrationVerificationRequest.Identity from = request.from();
        IdentityMigrationVerificationRequest.Identity to = request.to();
        if (!validIdentity(from) || !validIdentity(to) || !hasText(request.version())
                || request.artifactSizeBytes() <= 0L || !validSha256(request.artifactSha256())) {
            return migrationFail(VerificationStatus.IDENTITY_MISMATCH, request, null,
                    "MIGRATION_BINDING_MALFORMED");
        }
        SignatureMetadata signature = request.signature();
        if (signature != null && !from.keyId().equals(signature.keyId())) {
            return migrationFail(VerificationStatus.IDENTITY_MISMATCH, request, signature,
                    "MIGRATION_SIGNER_MISMATCH");
        }
        byte[] sha256 = HexFormat.of().parseHex(request.artifactSha256().trim());
        VerificationResult result = verifySignedEnvelope(
                signature,
                policy(request.policy()),
                to.pluginId(),
                request.version(),
                request.artifactSizeBytes(),
                request.artifactSha256().toLowerCase(Locale.ROOT),
                () -> EnvelopeV1Codec.identityMigrationMessage(
                        signature.algorithm(),
                        signature.keyId(),
                        from.pluginId(),
                        from.source(),
                        from.repositoryId(),
                        from.officialRepository(),
                        from.publisher(),
                        from.keyId(),
                        to.pluginId(),
                        to.source(),
                        to.repositoryId(),
                        to.officialRepository(),
                        to.publisher(),
                        to.keyId(),
                        request.version(),
                        request.artifactSizeBytes(),
                        sha256),
                null);
        if (result.status() == VerificationStatus.VERIFIED
                && !from.publisher().equals(result.publisher())) {
            return migrationFail(VerificationStatus.IDENTITY_MISMATCH, request, signature,
                    "MIGRATION_PUBLISHER_MISMATCH");
        }
        return result;
    }

    private VerificationResult verifySignedEnvelope(SignatureMetadata metadata, VerificationPolicy policy,
                                                    String pluginId, String version, long size,
                                                    String sha256Hex, Supplier<byte[]> messageFactory,
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
                    algorithm, key.publisher(), key.trustLabel(), key.publicKeyFingerprint(), Instant.now(),
                    size, sha256Hex, "REVOKED_KEY");
        }
        if (key.state() == TrustedPluginKey.State.RETIRED && !policy.retiredKeysAllowed()) {
            return new VerificationResult(VerificationStatus.RETIRED_KEY, pluginId, version, metadata.keyId(),
                    algorithm, key.publisher(), key.trustLabel(), key.publicKeyFingerprint(), Instant.now(), size, sha256Hex,
                    "RETIRED_KEY_NOT_ALLOWED");
        }
        boolean valid = Ed25519Verifier.verify(key.publicKeySpkiBase64(), messageFactory.get(), signature);
        if (!valid) {
            return new VerificationResult(VerificationStatus.INVALID_SIGNATURE, pluginId, version, metadata.keyId(),
                    algorithm, key.publisher(), key.trustLabel(), key.publicKeyFingerprint(), Instant.now(), size, sha256Hex,
                    "INVALID_SIGNATURE");
        }
        return new VerificationResult(VerificationStatus.VERIFIED, pluginId, version, metadata.keyId(), algorithm,
                key.publisher(), key.trustLabel(), key.publicKeyFingerprint(), Instant.now(), size, sha256Hex,
                "VERIFIED");
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

    private static boolean validIdentity(IdentityMigrationVerificationRequest.Identity identity) {
        return identity != null
                && hasText(identity.pluginId())
                && hasText(identity.source())
                && hasText(identity.publisher())
                && hasText(identity.keyId())
                && (identity.repositoryId() == null || hasText(identity.repositoryId()));
    }

    private static boolean validSha256(String value) {
        return value != null && value.matches("[0-9A-Fa-f]{64}");
    }

    private static VerificationResult migrationFail(
            VerificationStatus status,
            IdentityMigrationVerificationRequest request,
            SignatureMetadata metadata,
            String diagnosticCode) {
        return new VerificationResult(
                status,
                request.to() != null ? request.to().pluginId() : null,
                request.version(),
                metadata != null ? metadata.keyId() : null,
                metadata != null ? metadata.algorithm() : null,
                null,
                null,
                Instant.now(),
                request.artifactSizeBytes(),
                request.artifactSha256(),
                diagnosticCode);
    }

}
