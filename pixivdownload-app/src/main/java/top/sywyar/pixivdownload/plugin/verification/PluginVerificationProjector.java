package top.sywyar.pixivdownload.plugin.verification;

import top.sywyar.pixivdownload.plugin.catalog.model.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginRuntimeVerificationSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.time.Instant;
import java.util.List;

/**
 * 把验签结果 / provenance / catalog 包签名元数据投影为后端稳定视图字段。
 */
public final class PluginVerificationProjector {

    public static final String VERIFIED_OFFICIAL = "VERIFIED_OFFICIAL";
    public static final String VERIFIED_CUSTOM = "VERIFIED_CUSTOM";
    public static final String UNVERIFIED_LOCAL = "UNVERIFIED_LOCAL";
    public static final String UNSIGNED_ALLOWED = "UNSIGNED_ALLOWED";
    public static final String SIGNATURE_REQUIRED = "SIGNATURE_REQUIRED";
    public static final String UNKNOWN_KEY = "UNKNOWN_KEY";
    public static final String REVOKED_KEY = "REVOKED_KEY";
    public static final String INVALID_SIGNATURE = "INVALID_SIGNATURE";
    public static final String HASH_MISMATCH = "HASH_MISMATCH";
    public static final String IO_ERROR = "IO_ERROR";
    public static final String PROVENANCE_INVALID = "PROVENANCE_INVALID";
    public static final String NOT_INSTALLED = "NOT_INSTALLED";

    private PluginVerificationProjector() {
    }

    public static PluginVerificationView builtInOfficial() {
        return new PluginVerificationView(VERIFIED_OFFICIAL, "built-in", null, "PixivDownloader",
                "built-in", null, true, null);
    }

    public static PluginVerificationView notInstalled() {
        return new PluginVerificationView(NOT_INSTALLED, "not-installed", null, null, null, null, false, null);
    }

    public static PluginVerificationView unverifiedLocal() {
        return new PluginVerificationView(UNVERIFIED_LOCAL, "local", null, null, null, null, false,
                "PROVENANCE_MISSING");
    }

    public static PluginVerificationView missingProvenance() {
        return new PluginVerificationView(PROVENANCE_INVALID, "unknown", null, null, null, null, false,
                "PROVENANCE_MISSING");
    }

    public static PluginVerificationView invalidProvenance() {
        return new PluginVerificationView(PROVENANCE_INVALID, "unknown", null, null, null, null, false,
                PROVENANCE_INVALID);
    }

    public static PluginVerificationView fromProvenance(PluginProvenanceRecord record) {
        if (record == null) {
            return missingProvenance();
        }
        if (!hasCompatibleStatusSemantics(
                record.source(), record.signature() != null, record.status(), record.offlineStatus())) {
            return invalidProvenance();
        }
        VerificationStatus offline = record.offlineStatus();
        VerificationStatus effectiveStatus = offline != null ? offline : record.status();
        String status = statusFrom(effectiveStatus, record.officialRepository(), record.source());
        boolean offlineOk = offline == VerificationStatus.VERIFIED || offline == VerificationStatus.UNSIGNED_ALLOWED;
        Instant verifiedAt = record.offlineVerifiedAt() != null ? record.offlineVerifiedAt() : record.verifiedAt();
        return new PluginVerificationView(status, source(record), record.keyId(), record.publisher(),
                record.trustLabel(), verifiedAt != null ? verifiedAt.toString() : null, offlineOk,
                diagnosticCode(record, offline));
    }

    /** 投影本次启动对当前冻结字节得到的结构化结果，优先级高于安装时 / sidecar 历史结果。 */
    public static PluginVerificationView fromRuntimeVerification(
            PluginRuntimeVerificationSnapshot snapshot) {
        if (snapshot == null) {
            return invalidProvenance();
        }
        PluginProvenanceRecord provenance = snapshot.provenance();
        VerificationResult result = snapshot.result();
        if (provenance == null) {
            return new PluginVerificationView(
                    statusFrom(result.status(), false, null),
                    "unknown",
                    result.keyId(),
                    result.publisher(),
                    result.trustLabel(),
                    result.verifiedAt() != null ? result.verifiedAt().toString() : null,
                    result.accepted(),
                    result.diagnosticCode() != null && !result.diagnosticCode().isBlank()
                            ? result.diagnosticCode() : result.status().name());
        }
        PluginPackageSource source = provenance.source();
        if (!hasCompatibleStatusSemantics(
                source, provenance.signature() != null, provenance.status(), result.status())) {
            return invalidProvenance();
        }
        boolean official = provenance.officialRepository();
        String keyId = result.keyId();
        String publisher = result.publisher();
        String trustLabel = result.trustLabel();
        keyId = keyId != null ? keyId : provenance.keyId();
        publisher = publisher != null ? publisher : provenance.publisher();
        trustLabel = trustLabel != null ? trustLabel : provenance.trustLabel();
        return new PluginVerificationView(
                statusFrom(result.status(), official, source),
                source(source, official),
                keyId,
                publisher,
                trustLabel,
                result.verifiedAt() != null ? result.verifiedAt().toString() : null,
                result.accepted(),
                result.diagnosticCode() != null && !result.diagnosticCode().isBlank()
                        ? result.diagnosticCode() : result.status().name());
    }

    static boolean hasCompatibleStatusSemantics(PluginPackageSource source, boolean signed,
                                                VerificationStatus installed, VerificationStatus offline) {
        if (source == null || installed == null) {
            return false;
        }
        if (source == PluginPackageSource.LOCAL_UPLOAD) {
            return signed
                    ? installed == VerificationStatus.VERIFIED
                    && offline != VerificationStatus.UNSIGNED_ALLOWED
                    : installed == VerificationStatus.UNSIGNED_ALLOWED
                    && offline != VerificationStatus.VERIFIED;
        }
        if (source == PluginPackageSource.MARKET_CATALOG) {
            return signed && installed == VerificationStatus.VERIFIED
                    && offline != VerificationStatus.UNSIGNED_ALLOWED;
        }
        return false;
    }

    public static PluginVerificationView forCatalogPackage(PluginRepository repository, PluginCatalogPackage pkg) {
        SignatureMetadata signature = pkg != null ? pkg.signature() : null;
        boolean official = repository != null && repository.official();
        if (signature == null) {
            return new PluginVerificationView(SIGNATURE_REQUIRED,
                    official ? "official" : "custom", null, null, null, null, false,
                    "PACKAGE_SIGNATURE_MISSING");
        }
        if (signature.formatVersion() != SignatureMetadata.FORMAT_VERSION
                || signature.algorithm() == null || !SignatureMetadata.ED25519.equals(signature.algorithm())
                || signature.value() == null || signature.value().isBlank()) {
            return new PluginVerificationView(INVALID_SIGNATURE, official ? "official" : "custom",
                    signature.keyId(), null, null, null, false, "MALFORMED_SIGNATURE");
        }
        TrustedPluginKey key = (repository != null ? repository.trustedKeys() : List.<TrustedPluginKey>of()).stream()
                .filter(candidate -> signature.keyId() != null && signature.keyId().equals(candidate.keyId()))
                .findFirst().orElse(null);
        if (key == null) {
            return new PluginVerificationView(UNKNOWN_KEY, official ? "official" : "custom",
                    signature.keyId(), null, null, null, false, "UNKNOWN_KEY");
        }
        if (key.state() == TrustedPluginKey.State.REVOKED) {
            return new PluginVerificationView(REVOKED_KEY, official ? "official" : "custom",
                    signature.keyId(), key.publisher(), key.trustLabel(), null, false, "REVOKED_KEY");
        }
        if (key.state() == TrustedPluginKey.State.RETIRED) {
            return new PluginVerificationView(UNKNOWN_KEY, official ? "official" : "custom",
                    signature.keyId(), key.publisher(), key.trustLabel(), null, false, "RETIRED_KEY_NOT_ALLOWED");
        }
        if (official && !key.official()) {
            return new PluginVerificationView(UNKNOWN_KEY, "official",
                    signature.keyId(), key.publisher(), key.trustLabel(), null, false, "OFFICIAL_KEY_REQUIRED");
        }
        String status = official ? VERIFIED_OFFICIAL : VERIFIED_CUSTOM;
        return new PluginVerificationView(status, official ? "official" : "custom",
                signature.keyId(), key.publisher(), key.trustLabel(), null, false, "SIGNATURE_DECLARED");
    }

    private static String statusFrom(VerificationStatus status, boolean official, PluginPackageSource source) {
        if (status == VerificationStatus.VERIFIED) {
            return official || source == PluginPackageSource.LOCAL_UPLOAD
                    ? VERIFIED_OFFICIAL : VERIFIED_CUSTOM;
        }
        if (status == VerificationStatus.UNSIGNED_ALLOWED) {
            return source == PluginPackageSource.LOCAL_UPLOAD ? UNSIGNED_ALLOWED : UNVERIFIED_LOCAL;
        }
        if (status == VerificationStatus.SIGNATURE_REQUIRED) {
            return SIGNATURE_REQUIRED;
        }
        if (status == VerificationStatus.UNKNOWN_KEY || status == VerificationStatus.RETIRED_KEY) {
            return UNKNOWN_KEY;
        }
        if (status == VerificationStatus.REVOKED_KEY) {
            return REVOKED_KEY;
        }
        if (status == VerificationStatus.HASH_MISMATCH) {
            return HASH_MISMATCH;
        }
        if (status == VerificationStatus.IO_ERROR) {
            return IO_ERROR;
        }
        if (status == VerificationStatus.INVALID_SIGNATURE || status == VerificationStatus.MALFORMED_SIGNATURE
                || status == VerificationStatus.UNSUPPORTED_ALGORITHM
                || status == VerificationStatus.IDENTITY_MISMATCH) {
            return INVALID_SIGNATURE;
        }
        return status.name();
    }

    private static String source(PluginProvenanceRecord record) {
        return source(record.source(), record.officialRepository());
    }

    private static String source(PluginPackageSource source, boolean official) {
        if (source == PluginPackageSource.LOCAL_UPLOAD) {
            return "local";
        }
        return official ? "official" : "custom";
    }

    private static String diagnosticCode(PluginProvenanceRecord record, VerificationStatus offline) {
        if (record.diagnosticCode() != null && !record.diagnosticCode().isBlank()) {
            return record.diagnosticCode();
        }
        return offline != null ? offline.name() : null;
    }
}
