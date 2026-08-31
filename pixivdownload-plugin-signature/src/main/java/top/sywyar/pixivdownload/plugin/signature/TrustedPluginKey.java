package top.sywyar.pixivdownload.plugin.signature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

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

    /** 与可变 keyId 无关的公钥身份；用于把第三方插件信任绑定到实际发布密钥。 */
    public String publicKeyFingerprint() {
        try {
            byte[] spki = Base64.getDecoder().decode(publicKeySpkiBase64);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spki));
        } catch (IllegalArgumentException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot fingerprint trusted plugin key " + keyId, e);
        }
    }

    public enum State {
        ACTIVE,
        RETIRED,
        REVOKED
    }
}
