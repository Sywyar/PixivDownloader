package top.sywyar.pixivdownload.plugin.signature.internal.ed25519;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;

/**
 * Ed25519 detached 签名内部实现。
 */
public final class Ed25519Signer {

    private Ed25519Signer() {
    }

    public static byte[] sign(PrivateKey privateKey, byte[] message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("failed to sign Ed25519 message", e);
        }
    }
}
