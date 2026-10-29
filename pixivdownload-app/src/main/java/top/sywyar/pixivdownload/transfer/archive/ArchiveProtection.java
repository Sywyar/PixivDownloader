package top.sywyar.pixivdownload.transfer.archive;

import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** 版本固定的口令封装；分段认证交给 Tink，避免整份数据库解密时驻留内存。 */
final class ArchiveProtection {
    private static final byte[] MAGIC = "PDTX0001".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 600_000;
    private static final int SEGMENT_BYTES = 1024 * 1024;

    private ArchiveProtection() {}

    static OutputStream encrypt(OutputStream output, char[] password) throws IOException {
        byte[] header = new byte[MAGIC.length + 1 + SALT_BYTES];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        boolean encrypted = password != null && password.length > 0;
        header[MAGIC.length] = (byte) (encrypted ? 1 : 0);
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        System.arraycopy(salt, 0, header, MAGIC.length + 1, SALT_BYTES);
        output.write(header);
        if (!encrypted) return output;
        try {
            return primitive(password, salt).newEncryptingStream(output, header);
        } catch (GeneralSecurityException failure) {
            throw new IOException("transfer.crypto-unavailable");
        }
    }

    static Opened decrypt(InputStream input, char[] password) throws IOException {
        byte[] header = input.readNBytes(MAGIC.length + 1 + SALT_BYTES);
        if (header.length != MAGIC.length + 1 + SALT_BYTES
                || !Arrays.equals(MAGIC, Arrays.copyOf(header, MAGIC.length))) {
            throw new IOException("transfer.archive-invalid");
        }
        int mode = header[MAGIC.length];
        if (mode == 0) return new Opened(input, false);
        if (mode != 1) throw new IOException("transfer.archive-invalid");
        if (password == null || password.length == 0) throw new IOException("transfer.password-required");
        try {
            byte[] salt = Arrays.copyOfRange(header, MAGIC.length + 1, header.length);
            return new Opened(primitive(password, salt).newDecryptingStream(input, header), true);
        } catch (GeneralSecurityException failure) {
            throw new IOException("transfer.archive-invalid");
        }
    }

    private static StreamingAead primitive(char[] password, byte[] salt) throws GeneralSecurityException {
        if (password.length > 1024) throw new GeneralSecurityException("PASSWORD_LIMIT");
        var spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new AesGcmHkdfStreaming(key, "HmacSha256", 32, SEGMENT_BYTES, 0);
        } finally {
            spec.clearPassword();
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    record Opened(InputStream stream, boolean encrypted) {}
}
