package top.sywyar.pixivdownload.plugin.signature.internal.ed25519;

import top.sywyar.pixivdownload.plugin.signature.internal.trust.KeyParsing;

import java.security.PublicKey;
import java.security.Signature;

/**
 * 使用 Java 17 原生 provider 的 Ed25519 验签实现。
 */
public final class Ed25519Verifier {

    private Ed25519Verifier() {
    }

    public static boolean verify(String publicKeySpkiBase64, byte[] message, byte[] signatureBytes) {
        try {
            PublicKey publicKey = KeyParsing.ed25519PublicKey(publicKeySpkiBase64);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }
}
