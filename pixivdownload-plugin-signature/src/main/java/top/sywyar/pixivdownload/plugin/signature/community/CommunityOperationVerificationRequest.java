package top.sywyar.pixivdownload.plugin.signature.community;

import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;

/** 操作的规范正文及其 ID；调用者负责按 RFC 8785 生成正文并校验领域身份。 */
public record CommunityOperationVerificationRequest(CommunityOperation operation, byte[] canonicalBytes,
        String requestId, SignatureMetadata signature, boolean retiredKeysAllowed) {
    public CommunityOperationVerificationRequest {
        canonicalBytes = canonicalBytes == null ? null : canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() { return canonicalBytes == null ? null : canonicalBytes.clone(); }
}
