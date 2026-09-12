package top.sywyar.pixivdownload.plugin.signature.internal.trust;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Arrays;

/**
 * Ed25519 私钥解析工具，仅供发布链路 CLI 使用。
 */
public final class KeyParsing {

    private KeyParsing() {
    }

    public static PrivateKey ed25519PrivateKey(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("private key is empty");
        }
        String base64 = text
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid Ed25519 PKCS#8 private key", e);
        }
    }

    public static PublicKey ed25519PublicKey(String publicKeySpkiBase64) {
        if (publicKeySpkiBase64 == null || publicKeySpkiBase64.isBlank()) {
            throw new IllegalArgumentException("public key is empty");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(publicKeySpkiBase64);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid Ed25519 X.509 public key", e);
        }
    }

    /** 社区合同要求标准 Base64 与规范 SPKI DER，不能在验签时默默改写输入身份。 */
    public static PublicKey canonicalEd25519PublicKey(String encoded) {
        PublicKey key = ed25519PublicKey(encoded);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)
                || !Arrays.equals(bytes, key.getEncoded())) {
            throw new IllegalArgumentException("noncanonical Ed25519 SPKI");
        }
        return key;
    }
}
