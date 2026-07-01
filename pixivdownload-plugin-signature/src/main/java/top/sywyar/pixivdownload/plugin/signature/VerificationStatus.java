package top.sywyar.pixivdownload.plugin.signature;

/**
 * 插件供应链验签的稳定机器状态。
 */
public enum VerificationStatus {
    VERIFIED,
    UNSIGNED_ALLOWED,
    SIGNATURE_REQUIRED,
    MALFORMED_SIGNATURE,
    UNSUPPORTED_ALGORITHM,
    UNKNOWN_KEY,
    RETIRED_KEY,
    REVOKED_KEY,
    HASH_MISMATCH,
    IDENTITY_MISMATCH,
    INVALID_SIGNATURE,
    IO_ERROR
}
