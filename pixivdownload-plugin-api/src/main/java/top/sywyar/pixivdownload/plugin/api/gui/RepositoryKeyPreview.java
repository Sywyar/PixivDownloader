package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 描述符预览中的完整发布密钥事实。
 *
 * @param keyId 仓库声明的密钥标识
 * @param algorithm 签名算法
 * @param state 密钥状态
 * @param publisher 发布者身份
 * @param trustLabel 用户可见的信任标签
 * @param fingerprint 公钥指纹
 * @param fingerprintDisplay 供用户核对的格式化公钥指纹
 */
public record RepositoryKeyPreview(
        String keyId,
        String algorithm,
        String state,
        String publisher,
        String trustLabel,
        String fingerprint,
        String fingerprintDisplay) {
}
