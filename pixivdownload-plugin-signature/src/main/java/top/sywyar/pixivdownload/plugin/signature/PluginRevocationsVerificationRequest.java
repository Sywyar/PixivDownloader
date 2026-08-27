package top.sywyar.pixivdownload.plugin.signature;

/**
 * 插件撤销文档的验签请求。原始 JSON 字节在独立签名域内绑定仓库、序号、长度与 SHA-256。
 */
public record PluginRevocationsVerificationRequest(
        byte[] documentBytes,
        String repositoryId,
        long sequence,
        SignatureMetadata signature,
        VerificationPolicy policy) {

    public PluginRevocationsVerificationRequest {
        documentBytes = documentBytes != null ? documentBytes.clone() : null;
    }

    @Override
    public byte[] documentBytes() {
        return documentBytes != null ? documentBytes.clone() : null;
    }
}
