package top.sywyar.pixivdownload.download.controller;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

final class AggregatedSseCloseTokenCodec {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] key = new byte[32];

    AggregatedSseCloseTokenCodec() {
        secureRandom.nextBytes(key);
    }

    String create(String connectionId, String ownerUuid, boolean admin, long issuedAtMillis) {
        String payload = String.join("|",
                "v1",
                connectionId,
                ownerUuid == null ? "" : ownerUuid,
                String.valueOf(admin),
                String.valueOf(issuedAtMillis));
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] token = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to create SSE close token", e);
        }
    }

    Payload parse(String token) {
        if (token == null || token.isBlank() || token.length() > 2048) {
            return null;
        }
        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(token);
            if (tokenBytes.length <= GCM_IV_BYTES) {
                return null;
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] encrypted = new byte[tokenBytes.length - GCM_IV_BYTES];
            System.arraycopy(tokenBytes, 0, iv, 0, GCM_IV_BYTES);
            System.arraycopy(tokenBytes, GCM_IV_BYTES, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            String decoded = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 5 || !"v1".equals(parts[0])) {
                return null;
            }
            return new Payload(
                    parts[1],
                    parts[2].isBlank() ? null : parts[2],
                    Boolean.parseBoolean(parts[3]),
                    Long.parseLong(parts[4]));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return null;
        }
    }

    record Payload(String connectionId, String ownerUuid, boolean admin, long issuedAtMillis) {
    }
}
