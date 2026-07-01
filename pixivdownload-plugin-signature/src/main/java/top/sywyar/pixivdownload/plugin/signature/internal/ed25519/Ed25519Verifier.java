package top.sywyar.pixivdownload.plugin.signature.internal.ed25519;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 使用 Java 17 原生 provider 的 Ed25519 验签实现。
 */
public final class Ed25519Verifier {

    private Ed25519Verifier() {
    }

    public static boolean verify(String publicKeySpkiBase64, byte[] message, byte[] signatureBytes) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeySpkiBase64)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }
}
