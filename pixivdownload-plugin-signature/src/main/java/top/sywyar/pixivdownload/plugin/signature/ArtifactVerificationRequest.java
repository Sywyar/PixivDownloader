package top.sywyar.pixivdownload.plugin.signature;

import java.nio.file.Path;

/**
 * 验证一个插件包所需的调用方期望值。
 *
 * @param artifactPath      本地 artifact 路径
 * @param pluginId          来自已验证来源 / 描述符的期望插件 id
 * @param version           来自已验证来源 / 描述符的期望插件版本
 * @param expectedSizeBytes 期望字节数；仅本地 unsigned 策略可为空
 * @param expectedSha256    期望 SHA-256 十六进制；仅本地 unsigned 策略可为空
 * @param signature         detached 签名元数据
 * @param policy            信任策略
 */
public record ArtifactVerificationRequest(
        Path artifactPath,
        String pluginId,
        String version,
        Long expectedSizeBytes,
        String expectedSha256,
        SignatureMetadata signature,
        VerificationPolicy policy) {
}
