package top.sywyar.pixivdownload.plugin.signature.internal.envelope;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 验签内部使用的流式 SHA-256 工具。
 */
public final class Hashing {

    private Hashing() {
    }

    public static byte[] sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[8192];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    public static byte[] sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        digest.update(bytes);
        return digest.digest();
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
