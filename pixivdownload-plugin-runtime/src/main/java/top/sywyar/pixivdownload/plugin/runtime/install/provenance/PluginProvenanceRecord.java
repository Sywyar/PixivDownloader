package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * 单个已安装外置插件 artifact 的持久化验签来源记录。
 */
public record PluginProvenanceRecord(
        PluginPackageSource source,
        String repositoryId,
        boolean officialRepository,
        Long expectedSizeBytes,
        String expectedSha256,
        long artifactSizeBytes,
        String artifactSha256,
        SignatureMetadata signature,
        VerificationStatus status,
        String keyId,
        String publisher,
        String trustLabel,
        Instant verifiedAt,
        VerificationStatus offlineStatus,
        Instant offlineVerifiedAt,
        String diagnosticCode) {

    public PluginProvenanceRecord {
        source = Objects.requireNonNull(source, "source");
        status = Objects.requireNonNull(status, "status");
        if (artifactSizeBytes <= 0L) {
            throw new IllegalArgumentException("artifactSizeBytes must be positive");
        }
        artifactSha256 = normalizedSha256(artifactSha256, "artifactSha256");
        expectedSha256 = expectedSha256 != null
                ? normalizedSha256(expectedSha256, "expectedSha256") : null;
        repositoryId = optionalText(repositoryId, "repositoryId");
        keyId = optionalText(keyId, "keyId");
        publisher = optionalText(publisher, "publisher");
        trustLabel = optionalText(trustLabel, "trustLabel");
        diagnosticCode = optionalText(diagnosticCode, "diagnosticCode");
        if (verifiedAt == null) {
            throw new IllegalArgumentException("verifiedAt is required");
        }
        if ((offlineStatus == null) != (offlineVerifiedAt == null)) {
            throw new IllegalArgumentException(
                    "offline verification status and timestamp must be recorded together");
        }
        if (offlineVerifiedAt != null && offlineVerifiedAt.isBefore(verifiedAt)) {
            throw new IllegalArgumentException("offline verification timestamp must not precede installation");
        }
        VerificationStatus diagnosticStatus = offlineStatus != null ? offlineStatus : status;
        validateDiagnosticCode(diagnosticStatus, diagnosticCode);

        if (source == PluginPackageSource.LOCAL_UPLOAD) {
            if (officialRepository || repositoryId != null || expectedSizeBytes != null
                    || expectedSha256 != null || signature != null) {
                throw new IllegalArgumentException("local provenance must not claim catalog source bindings");
            }
            if (status != VerificationStatus.UNSIGNED_ALLOWED) {
                throw new IllegalArgumentException("local provenance initial status must be UNSIGNED_ALLOWED");
            }
            if (offlineStatus == VerificationStatus.VERIFIED) {
                throw new IllegalArgumentException("local provenance offline success must be UNSIGNED_ALLOWED");
            }
            if (keyId != null || publisher != null || trustLabel != null) {
                throw new IllegalArgumentException("local provenance must not claim trusted signer metadata");
            }
        } else if (source == PluginPackageSource.MARKET_CATALOG) {
            if (repositoryId == null || expectedSizeBytes == null || expectedSizeBytes <= 0L
                    || expectedSha256 == null || signature == null) {
                throw new IllegalArgumentException("catalog provenance is missing its signed source binding");
            }
            if (expectedSizeBytes != artifactSizeBytes || !expectedSha256.equals(artifactSha256)) {
                throw new IllegalArgumentException("catalog provenance observed artifact binding changed");
            }
            validateCatalogSignature(signature);
            if (status != VerificationStatus.VERIFIED) {
                throw new IllegalArgumentException("catalog provenance initial status must be VERIFIED");
            }
            if (offlineStatus == VerificationStatus.UNSIGNED_ALLOWED) {
                throw new IllegalArgumentException("catalog provenance offline success must be VERIFIED");
            }
            if (!signature.keyId().equals(keyId)) {
                throw new IllegalArgumentException("catalog provenance keyId must match signature.keyId");
            }
        } else {
            throw new IllegalArgumentException("unsupported provenance source: " + source);
        }
    }

    public PluginPackageOrigin originForOfflineVerification() {
        if (source == PluginPackageSource.MARKET_CATALOG) {
            return PluginPackageOrigin.forTrustedCatalog(repositoryId, officialRepository, expectedSizeBytes,
                    expectedSha256, signature);
        }
        return PluginPackageOrigin.localUpload();
    }

    public static PluginProvenanceRecord from(PluginPackageOrigin origin, VerificationResult result) {
        return new PluginProvenanceRecord(
                origin.source(),
                origin.repositoryId(),
                origin.officialRepository(),
                origin.expectedSizeBytes(),
                origin.expectedSha256(),
                result.sizeBytes(),
                result.sha256(),
                origin.signature(),
                result.status(),
                result.keyId(),
                result.publisher(),
                result.trustLabel(),
                result.verifiedAt(),
                null,
                null,
                result.diagnosticCode());
    }

    public PluginProvenanceRecord withOfflineResult(
            VerificationResult result, String expectedPluginId, String expectedVersion) {
        Objects.requireNonNull(result, "result");
        String pluginId = requiredText(expectedPluginId, "expectedPluginId");
        String version = requiredText(expectedVersion, "expectedVersion");
        if (!pluginId.equals(result.pluginId()) || !version.equals(result.version())) {
            throw new IllegalArgumentException("offline verification identity does not match the inspected package");
        }
        VerificationStatus resultStatus = Objects.requireNonNull(result.status(), "result.status");
        if (resultStatus != VerificationStatus.HASH_MISMATCH
                && resultStatus != VerificationStatus.IO_ERROR
                && (result.sizeBytes() != artifactSizeBytes
                || !artifactSha256.equals(normalizedSha256(result.sha256(), "result.sha256")))) {
            throw new IllegalArgumentException("offline verification result does not bind the installed artifact");
        }
        return new PluginProvenanceRecord(source, repositoryId, officialRepository, expectedSizeBytes, expectedSha256,
                artifactSizeBytes, artifactSha256, signature, status,
                result.keyId() != null ? result.keyId() : keyId,
                result.publisher() != null ? result.publisher() : publisher,
                result.trustLabel() != null ? result.trustLabel() : trustLabel,
                verifiedAt, result.status(), result.verifiedAt(), result.diagnosticCode());
    }

    private static void validateDiagnosticCode(VerificationStatus status, String diagnosticCode) {
        if (diagnosticCode == null) {
            throw new IllegalArgumentException("diagnosticCode is required");
        }
        boolean compatible = switch (status) {
            case VERIFIED -> "VERIFIED".equals(diagnosticCode);
            case UNSIGNED_ALLOWED -> "UNSIGNED_ALLOWED".equals(diagnosticCode);
            case SIGNATURE_REQUIRED -> "SIGNATURE_REQUIRED".equals(diagnosticCode);
            case MALFORMED_SIGNATURE -> "MALFORMED_SIGNATURE".equals(diagnosticCode)
                    || "BAD_SIGNATURE_BASE64".equals(diagnosticCode);
            case UNSUPPORTED_ALGORITHM -> "UNSUPPORTED_ALGORITHM".equals(diagnosticCode)
                    || "UNSUPPORTED_KEY_ALGORITHM".equals(diagnosticCode);
            case UNKNOWN_KEY -> "UNKNOWN_KEY".equals(diagnosticCode)
                    || "OFFICIAL_KEY_REQUIRED".equals(diagnosticCode);
            case RETIRED_KEY -> "RETIRED_KEY_NOT_ALLOWED".equals(diagnosticCode);
            case REVOKED_KEY -> "REVOKED_KEY".equals(diagnosticCode);
            case HASH_MISMATCH -> "HASH_MISMATCH".equals(diagnosticCode)
                    || "SIZE_MISMATCH".equals(diagnosticCode)
                    || "SHA256_MISMATCH".equals(diagnosticCode);
            case IDENTITY_MISMATCH -> "IDENTITY_MISMATCH".equals(diagnosticCode)
                    || "IDENTITY_MISSING".equals(diagnosticCode);
            case INVALID_SIGNATURE -> "INVALID_SIGNATURE".equals(diagnosticCode);
            case IO_ERROR -> "IO_ERROR".equals(diagnosticCode)
                    || "ARTIFACT_NOT_FOUND".equals(diagnosticCode)
                    || "ARTIFACT_IO_ERROR".equals(diagnosticCode)
                    || "MANIFEST_MISSING".equals(diagnosticCode);
        };
        if (!compatible) {
            throw new IllegalArgumentException(
                    "diagnosticCode is incompatible with verification status " + status);
        }
    }

    private static void validateCatalogSignature(SignatureMetadata signature) {
        if (signature.formatVersion() != SignatureMetadata.FORMAT_VERSION
                || !SignatureMetadata.ED25519.equals(signature.algorithm())) {
            throw new IllegalArgumentException("catalog provenance signature envelope is unsupported");
        }
        String signatureKeyId = requiredText(signature.keyId(), "signature.keyId");
        String signatureValue = requiredText(signature.value(), "signature.value");
        try {
            Base64.getDecoder().decode(signatureValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("catalog provenance signature.value is not valid Base64", e);
        }
        if (!signatureKeyId.equals(signature.keyId())) {
            throw new IllegalArgumentException("signature.keyId must not contain surrounding whitespace");
        }
    }

    private static String normalizedSha256(String value, String field) {
        String text = requiredText(value, field);
        if (!text.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException(field + " is not a SHA-256 digest");
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private static String optionalText(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requiredText(value, field);
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " is missing or malformed");
        }
        return value;
    }
}
