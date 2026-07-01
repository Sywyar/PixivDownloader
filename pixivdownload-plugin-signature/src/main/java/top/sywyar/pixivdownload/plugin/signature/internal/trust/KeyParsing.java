package top.sywyar.pixivdownload.plugin.signature.internal.trust;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

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
}
