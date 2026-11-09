package top.sywyar.pixivdownload.config.credential;

import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** 安装主密钥只以 DPAPI 或部署方独立提供的密钥保护后落盘。 */
final class CredentialKeyProtection {
    static final String KEY_FILE_PROPERTY = "pixivdownload.credential-key-file";
    static final String KEY_FILE_ENV = "PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE";
    static final int MAX_WRAPPED_BYTES = 64 * 1024;
    static final byte WINDOWS = 1;
    static final byte EXTERNAL = 2;
    private static final byte[] MAGIC = "PDCRED01".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;

    private CredentialKeyProtection() {}

    static byte mode() throws IOException {
        if (externalKeyPath() != null) return EXTERNAL;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return WINDOWS;
        throw new IOException("Credential protection requires PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE pointing to a private Base64-encoded 32-byte secret");
    }

    static byte[] protect(byte[] payload, byte mode) throws IOException {
        byte[] wrapped = switch (mode) {
            case WINDOWS -> WindowsCredentialProtection.apply(payload, true);
            case EXTERNAL -> external(payload, true);
            default -> throw new IOException("Unsupported credential key protection");
        };
        try {
            if (wrapped.length > MAX_WRAPPED_BYTES - MAGIC.length - 1) throw new IOException("Protected credential key is too large");
            return ByteBuffer.allocate(MAGIC.length + 1 + wrapped.length).put(MAGIC).put(mode).put(wrapped).array();
        } finally { Arrays.fill(wrapped, (byte) 0); }
    }

    static byte storedMode(byte[] content) throws IOException {
        if (content.length <= MAGIC.length || content.length > MAX_WRAPPED_BYTES
                || !Arrays.equals(MAGIC, Arrays.copyOf(content, MAGIC.length)))
            throw new IOException("Invalid protected credential key file");
        byte mode = content[MAGIC.length];
        if (mode != WINDOWS && mode != EXTERNAL) throw new IOException("Unsupported credential key protection");
        return mode;
    }

    static byte[] unprotect(byte[] content) throws IOException {
        byte mode = storedMode(content);
        byte[] wrapped = Arrays.copyOfRange(content, MAGIC.length + 1, content.length);
        try {
            return mode == WINDOWS ? WindowsCredentialProtection.apply(wrapped, false) : external(wrapped, false);
        } finally { Arrays.fill(wrapped, (byte) 0); }
    }

    private static byte[] external(byte[] input, boolean encrypt) throws IOException {
        byte[] key = readExternalKey();
        byte[] nonce = new byte[NONCE_BYTES];
        try {
            if (encrypt) new SecureRandom().nextBytes(nonce);
            else {
                if (input.length < NONCE_BYTES + 16) throw new IOException("Invalid protected credential key size");
                System.arraycopy(input, 0, nonce, 0, NONCE_BYTES);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            cipher.updateAAD(new byte[]{EXTERNAL});
            if (!encrypt) return cipher.doFinal(input, NONCE_BYTES, input.length - NONCE_BYTES);
            byte[] result = cipher.doFinal(input);
            try { return ByteBuffer.allocate(NONCE_BYTES + result.length).put(nonce).put(result).array(); }
            finally { Arrays.fill(result, (byte) 0); }
        } catch (GeneralSecurityException failure) {
            throw new IOException("Credential key protection could not be unlocked with the configured secret", failure);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    private static String externalKeyPath() {
        String configured = System.getProperty(KEY_FILE_PROPERTY);
        if (configured == null) configured = System.getenv(KEY_FILE_ENV);
        return configured == null || configured.isBlank() ? null : configured;
    }

    private static byte[] readExternalKey() throws IOException {
        String configured = externalKeyPath();
        if (configured == null) throw new IOException("Credential protection requires PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE");
        Path path;
        try { path = Path.of(configured); }
        catch (RuntimeException invalid) { throw new IOException("Invalid credential protection secret path"); }
        MigrationFiles.requirePlain(path, false);
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(65); }
        try {
            if (bytes.length > 64) throw new IOException("Invalid credential protection secret length");
            String encoded = new String(bytes, StandardCharsets.US_ASCII).strip();
            byte[] key;
            try { key = Base64.getDecoder().decode(encoded); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid credential protection secret encoding"); }
            if (key.length == 32 && Base64.getEncoder().encodeToString(key).equals(encoded)) return key;
            Arrays.fill(key, (byte) 0);
            throw new IOException("Credential protection secret must contain exactly 32 bytes in canonical Base64");
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
}
