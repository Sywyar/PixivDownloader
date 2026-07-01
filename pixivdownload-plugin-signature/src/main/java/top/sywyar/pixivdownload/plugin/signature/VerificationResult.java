package top.sywyar.pixivdownload.plugin.signature;

import java.time.Instant;

/**
 * artifact 与 manifest 的不可变验签结果。
 *
 * @param status         机器状态
 * @param pluginId       artifact 验签绑定的插件 id
 * @param version        artifact 验签绑定的插件版本
 * @param keyId          签名元数据使用的 key id
 * @param algorithm      签名算法
 * @param publisher      已知的可信发布者标签
 * @param trustLabel     已知的信任根标签
 * @param verifiedAt     结果创建时间
 * @param sizeBytes      已验证字节数
 * @param sha256         已验证 SHA-256 十六进制
 * @param diagnosticCode 稳定诊断码
 */
public record VerificationResult(
        VerificationStatus status,
        String pluginId,
        String version,
        String keyId,
        String algorithm,
        String publisher,
        String trustLabel,
        Instant verifiedAt,
        long sizeBytes,
        String sha256,
        String diagnosticCode) {

    public boolean accepted() {
        return status == VerificationStatus.VERIFIED || status == VerificationStatus.UNSIGNED_ALLOWED;
    }
}
