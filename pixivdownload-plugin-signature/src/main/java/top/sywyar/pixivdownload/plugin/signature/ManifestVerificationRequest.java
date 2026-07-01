package top.sywyar.pixivdownload.plugin.signature;

import java.util.Arrays;

/**
 * 原始 manifest 字节验签请求。manifest 字节必须是下载到内存的原始字节。
 *
 * @param manifestBytes 原始 manifest 字节
 * @param repositoryId  绑定进签名 envelope 的仓库 id
 * @param signature     detached manifest 签名元数据
 * @param policy        信任策略
 */
public record ManifestVerificationRequest(
        byte[] manifestBytes,
        String repositoryId,
        SignatureMetadata signature,
        VerificationPolicy policy) {

    public ManifestVerificationRequest {
        manifestBytes = manifestBytes == null ? null : Arrays.copyOf(manifestBytes, manifestBytes.length);
    }

    @Override
    public byte[] manifestBytes() {
        return manifestBytes == null ? null : Arrays.copyOf(manifestBytes, manifestBytes.length);
    }
}
