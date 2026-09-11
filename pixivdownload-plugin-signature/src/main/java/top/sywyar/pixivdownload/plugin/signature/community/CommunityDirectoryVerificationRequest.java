package top.sywyar.pixivdownload.plugin.signature.community;

import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;

/** 目录 root 原始字节快照；序号连续性由持久化目录消费者校验。 */
public record CommunityDirectoryVerificationRequest(byte[] documentBytes, String repositoryId,
        long sequence, SignatureMetadata signature, boolean retiredKeysAllowed) {
    public CommunityDirectoryVerificationRequest {
        documentBytes = documentBytes == null ? null : documentBytes.clone();
    }

    @Override
    public byte[] documentBytes() { return documentBytes == null ? null : documentBytes.clone(); }
}
