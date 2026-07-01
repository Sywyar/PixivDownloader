package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.time.Instant;

/**
 * 单个已安装外置插件 artifact 的持久化验签来源记录。
 */
public record PluginProvenanceRecord(
        PluginPackageSource source,
        String repositoryId,
        boolean officialRepository,
        Long expectedSizeBytes,
        String expectedSha256,
        SignatureMetadata signature,
        VerificationStatus status,
        String keyId,
        String publisher,
        String trustLabel,
        Instant verifiedAt,
        VerificationStatus offlineStatus,
        Instant offlineVerifiedAt,
        String diagnosticCode) {

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

    public PluginProvenanceRecord withOfflineResult(VerificationResult result) {
        return new PluginProvenanceRecord(source, repositoryId, officialRepository, expectedSizeBytes, expectedSha256,
                signature, status, result.keyId() != null ? result.keyId() : keyId,
                result.publisher() != null ? result.publisher() : publisher,
                result.trustLabel() != null ? result.trustLabel() : trustLabel,
                verifiedAt, result.status(), result.verifiedAt(), result.diagnosticCode());
    }
}
