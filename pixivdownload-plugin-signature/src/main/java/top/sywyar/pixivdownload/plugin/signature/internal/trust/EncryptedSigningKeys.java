package top.sywyar.pixivdownload.plugin.signature.internal.trust;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Arrays;

/** 标准 PBES2 / PBKDF2-HMAC-SHA256 / AES-256-CBC 加密 PKCS#8，全部密码原语由 JDK 提供。 */
final class EncryptedSigningKeys {
    static final int ITERATIONS = 600_000;
    static final int MAX_ITERATIONS = 2_000_000;
    private static final String ALGORITHM = "PBEWithHmacSHA256AndAES_256";

    private EncryptedSigningKeys() { }

    static byte[] encrypt(byte[] pkcs8, char[] password) throws GeneralSecurityException, IOException {
        byte[] salt = new byte[32];
        byte[] iv = new byte[16];
        var random = new SecureRandom();
        random.nextBytes(salt); random.nextBytes(iv);
        var spec = new PBEParameterSpec(salt, ITERATIONS, new IvParameterSpec(iv));
        var parameters = AlgorithmParameters.getInstance(ALGORITHM);
        parameters.init(spec);
        var pbes2 = AlgorithmParameters.getInstance("PBES2");
        pbes2.init(parameters.getEncoded());
        return new EncryptedPrivateKeyInfo(pbes2, cipher(Cipher.ENCRYPT_MODE, password, spec).doFinal(pkcs8)).getEncoded();
    }

    static PrivateKey decrypt(byte[] encoded, char[] password) throws IOException {
        if (password == null || password.length == 0) throw new IOException("KEY_PASSWORD_REQUIRED");
        try {
            var information = new EncryptedPrivateKeyInfo(encoded);
            var parameters = information.getAlgParameters();
            if (!"PBES2".equals(information.getAlgName()) || parameters == null
                    || !ALGORITHM.equals(parameters.toString())) throw new IOException("KEY_ENCRYPTION_UNSUPPORTED");
            var spec = parameters.getParameterSpec(PBEParameterSpec.class);
            if (spec.getIterationCount() < 1 || spec.getIterationCount() > MAX_ITERATIONS
                    || spec.getSalt().length < 8 || spec.getSalt().length > 64
                    || !(spec.getParameterSpec() instanceof IvParameterSpec iv) || iv.getIV().length != 16) {
                throw new IOException("KEY_ENCRYPTION_PARAMETERS_INVALID");
            }
            return KeyParsing.ed25519PrivateKey(information.getKeySpec(cipher(Cipher.DECRYPT_MODE, password, spec)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("KEY_PASSWORD_INVALID");
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("KEY_")) throw e;
            throw new IOException("KEY_FORMAT_INVALID");
        }
    }

    private static Cipher cipher(int mode, char[] password, PBEParameterSpec parameters) throws GeneralSecurityException {
        var specification = new PBEKeySpec(password, parameters.getSalt(), parameters.getIterationCount(), 256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), parameters.getParameterSpec());
            return cipher;
        } finally {
            specification.clearPassword();
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }
}
