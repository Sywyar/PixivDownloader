package top.sywyar.pixivdownload.plugin.signature;

/**
 * 已信任仓库连续性证明的验签请求。原始 JSON 字节在独立签名域内绑定仓库、序号、长度与 SHA-256。
 */
public record RepositoryUpdateVerificationRequest(
        byte[] documentBytes,
        String repositoryId,
        long sequence,
        SignatureMetadata signature,
        VerificationPolicy policy) {

    public RepositoryUpdateVerificationRequest {
        documentBytes = documentBytes != null ? documentBytes.clone() : null;
    }

    @Override
    public byte[] documentBytes() {
        return documentBytes != null ? documentBytes.clone() : null;
    }
}
