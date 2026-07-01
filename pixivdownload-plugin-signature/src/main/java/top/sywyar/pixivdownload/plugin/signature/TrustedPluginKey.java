package top.sywyar.pixivdownload.plugin.signature;

/**
 * 已配置的插件发布者信任根。
 *
 * @param keyId               用于查找的稳定 key id
 * @param algorithm           公钥算法，当前只支持 {@code Ed25519}
 * @param publicKeySpkiBase64 Base64 编码的 X.509 SubjectPublicKeyInfo
 * @param state               key 生命周期状态
 * @param publisher           诊断与 UI 投影使用的发布者名称
 * @param trustLabel          诊断与 UI 投影使用的信任根标签
 * @param official            是否属于内置官方信任根集合
 */
public record TrustedPluginKey(
        String keyId,
        String algorithm,
        String publicKeySpkiBase64,
        State state,
        String publisher,
        String trustLabel,
        boolean official) {

    public enum State {
        ACTIVE,
        RETIRED,
        REVOKED
    }
}
