package top.sywyar.pixivdownload.plugin.signature.internal.trust;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

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
        // 保留具体套件生成的参数原始字节；JDK 26 的通用 PBES2 参数重编码会丢失 PRF。
        // 这里只封装 PKCS#8 外层 SEQUENCE，算法参数与密码原语仍由 JDK 提供。
        byte[] pbes2Oid = HexFormat.of().parseHex("06092a864886f70d01050d"); // 1.2.840.113549.1.5.13
        return der(0x30, der(0x30, pbes2Oid, parameters.getEncoded()),
                der(0x04, cipher(Cipher.ENCRYPT_MODE, password, spec).doFinal(pkcs8)));
    }

    /** 为内部生成的参数和密文写出 DER 标签及最短长度，不解析外部输入。 */
    private static byte[] der(int tag, byte[]... values) throws IOException {
        var body = new ByteArrayOutputStream();
        for (byte[] value : values) {
            if (value.length > SigningKeyFiles.MAX_KEY_BYTES - body.size()) throw new IOException("INPUT_LIMIT_EXCEEDED");
            body.writeBytes(value);
        }
        var result = new ByteArrayOutputStream();
        result.write(tag);
        int length = body.size();
        if (length < 128) result.write(length);
        else {
            int octets = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
            result.write(0x80 | octets);
            for (int shift = (octets - 1) * 8; shift >= 0; shift -= 8) result.write(length >>> shift);
        }
        body.writeTo(result);
        return result.toByteArray();
    }

    static PrivateKey decrypt(byte[] encoded, char[] password) throws IOException {
        try {
            var information = new EncryptedPrivateKeyInfo(encoded);
            var parameters = information.getAlgParameters();
            // 新版 JDK 的 getAlgName() 会展开为具体套件名，参数容器仍标识 PBES2。
            if (parameters == null || !"PBES2".equals(parameters.getAlgorithm())
                    || !ALGORITHM.equals(parameters.toString())) throw new IOException("KEY_ENCRYPTION_UNSUPPORTED");
            var spec = parameters.getParameterSpec(PBEParameterSpec.class);
            if (spec.getIterationCount() < 1 || spec.getIterationCount() > MAX_ITERATIONS
                    || spec.getSalt().length < 8 || spec.getSalt().length > 64
                    || !(spec.getParameterSpec() instanceof IvParameterSpec iv) || iv.getIV().length != 16) {
                throw new IOException("KEY_ENCRYPTION_PARAMETERS_INVALID");
            }
            if (password == null || password.length == 0) throw new IOException("KEY_PASSWORD_REQUIRED");
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
