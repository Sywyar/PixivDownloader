package top.sywyar.pixivdownload.config.credential;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** AES-256-GCM envelope codec with an HKDF-SHA-256 owner key derivation. */
final class PluginCredentialCipher {

    static final String FORMAT = "pixivdownload-plugin-credentials-v1";
    private static final String FIELD_FORMAT = "format";
    private static final String FIELD_KEY_ID = "key-id";
    private static final String FIELD_NONCE = "nonce";
    private static final String FIELD_CIPHERTEXT = "ciphertext";
    private static final Set<String> ENVELOPE_FIELDS =
            Set.of(FIELD_FORMAT, FIELD_KEY_ID, FIELD_NONCE, FIELD_CIPHERTEXT);
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final int NONCE_BYTES = 12;
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] HKDF_SALT =
            "pixivdownload-plugin-credentials/hkdf/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO_PREFIX =
            "pixivdownload/plugin-credential-owner/v1\0".getBytes(StandardCharsets.UTF_8);

    private final PluginCredentialKeyMaterial keyMaterial;
    private final SecureRandom secureRandom;

    PluginCredentialCipher(PluginCredentialKeyMaterial keyMaterial) {
        this(keyMaterial, new SecureRandom());
    }

    PluginCredentialCipher(PluginCredentialKeyMaterial keyMaterial, SecureRandom secureRandom) {
        this.keyMaterial = java.util.Objects.requireNonNull(keyMaterial, "keyMaterial");
        this.secureRandom = java.util.Objects.requireNonNull(secureRandom, "secureRandom");
    }

    byte[] encrypt(String ownerPluginId, Map<String, String> values) throws IOException {
        String owner = requireOwner(ownerPluginId);
        Map<String, String> safeValues = validatedValues(values);
        byte[] plaintext = serializePlaintext(safeValues);
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        String keyId = keyMaterial.currentKeyId();
        byte[] rootKey = keyMaterial.currentRootKey();
        byte[] ownerKey = null;
        try {
            ownerKey = deriveOwnerKey(rootKey, owner);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ownerKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(owner, keyId));
            byte[] ciphertext = cipher.doFinal(plaintext);
            String envelope = FIELD_FORMAT + "=" + FORMAT + "\n"
                    + FIELD_KEY_ID + "=" + keyId + "\n"
                    + FIELD_NONCE + "=" + encodeUrl(nonce) + "\n"
                    + FIELD_CIPHERTEXT + "=" + encodeUrl(ciphertext) + "\n";
            Arrays.fill(ciphertext, (byte) 0);
            return envelope.getBytes(StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to encrypt plugin credentials for owner: " + owner, e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(rootKey, (byte) 0);
            if (ownerKey != null) {
                Arrays.fill(ownerKey, (byte) 0);
            }
        }
    }

    Decoded decode(String ownerPluginId, byte[] content) throws IOException {
        String owner = requireOwner(ownerPluginId);
        byte[] safeContent = content == null ? new byte[0] : content;
        String text = decodeUtf8(safeContent);
        Map<String, String> envelope = parseEnvelopeCandidate(text);
        if (!envelope.containsKey(FIELD_FORMAT)) {
            if (ENVELOPE_FIELDS.stream().anyMatch(envelope::containsKey)) {
                throw new IOException("Incomplete plugin credential envelope for owner: " + owner);
            }
            throw new IOException("Missing authenticated plugin credential envelope for owner: " + owner);
        }
        return decryptEnvelope(owner, envelope);
    }

    private Decoded decryptEnvelope(String owner, Map<String, String> envelope) throws IOException {
        if (!FORMAT.equals(envelope.get(FIELD_FORMAT))) {
            throw new IOException("Unsupported plugin credential format for owner: " + owner);
        }
        if (!envelope.keySet().equals(ENVELOPE_FIELDS)) {
            throw new IOException("Invalid plugin credential envelope fields for owner: " + owner);
        }
        String keyId = requireEnvelopeValue(envelope, FIELD_KEY_ID, owner);
        byte[] nonce = decodeUrl(requireEnvelopeValue(envelope, FIELD_NONCE, owner), FIELD_NONCE, owner);
        byte[] ciphertext =
                decodeUrl(requireEnvelopeValue(envelope, FIELD_CIPHERTEXT, owner), FIELD_CIPHERTEXT, owner);
        if (nonce.length != NONCE_BYTES || ciphertext.length < GCM_TAG_BITS / Byte.SIZE) {
            throw new IOException("Invalid plugin credential envelope size for owner: " + owner);
        }
        byte[] rootKey = keyMaterial.rootKey(keyId);
        byte[] ownerKey = null;
        byte[] plaintext = null;
        try {
            ownerKey = deriveOwnerKey(rootKey, owner);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ownerKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(owner, keyId));
            plaintext = cipher.doFinal(ciphertext);
            return new Decoded(
                    parseLegacyPlaintext(decodeUtf8(plaintext)),
                    !keyMaterial.isCurrent(keyId));
        } catch (GeneralSecurityException e) {
            throw new IOException("Plugin credential authentication failed for owner: " + owner, e);
        } finally {
            Arrays.fill(rootKey, (byte) 0);
            if (ownerKey != null) {
                Arrays.fill(ownerKey, (byte) 0);
            }
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private static Map<String, String> parseEnvelopeCandidate(String text) throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String[] lines = text.split("\\R", -1);
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                return Map.of();
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (result.putIfAbsent(key, value) != null) {
                throw new IOException("Duplicate plugin credential envelope field");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> parseLegacyPlaintext(String text) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new StringReader(text)) {
            properties.load(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid legacy plugin credential properties", e);
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
            if (!SAFE_KEY.matcher(key).matches() || ENVELOPE_FIELDS.contains(key)) {
                throw new IOException("Invalid plugin credential key");
            }
            String value = properties.getProperty(key, "");
            if (value.indexOf('\0') >= 0) {
                throw new IOException("Plugin credential contains an unsupported NUL character");
            }
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private static byte[] serializePlaintext(Map<String, String> values) {
        List<String> lines = new ArrayList<>();
        values.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> lines.add(entry.getKey() + "=" + escape(entry.getValue())));
        return String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> validatedValues(Map<String, String> values) throws IOException {
        if (values == null) {
            throw new IOException("Plugin credential values must not be null");
        }
        LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!SAFE_KEY.matcher(key).matches() || ENVELOPE_FIELDS.contains(key)) {
                throw new IOException("Invalid plugin credential key");
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.indexOf('\0') >= 0) {
                throw new IOException("Plugin credential contains an unsupported NUL character");
            }
            if (safe.putIfAbsent(key, value) != null) {
                throw new IOException(
                        "Duplicate normalized plugin credential key: " + key);
            }
        }
        return Map.copyOf(safe);
    }

    private static byte[] deriveOwnerKey(byte[] rootKey, String owner) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HKDF_SALT, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(rootKey);
        byte[] ownerBytes = owner.getBytes(StandardCharsets.UTF_8);
        byte[] info = new byte[HKDF_INFO_PREFIX.length + ownerBytes.length + 1];
        System.arraycopy(HKDF_INFO_PREFIX, 0, info, 0, HKDF_INFO_PREFIX.length);
        System.arraycopy(ownerBytes, 0, info, HKDF_INFO_PREFIX.length, ownerBytes.length);
        info[info.length - 1] = 1;
        try {
            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            return Arrays.copyOf(mac.doFinal(info), AES_KEY_BYTES);
        } finally {
            Arrays.fill(pseudoRandomKey, (byte) 0);
            Arrays.fill(info, (byte) 0);
        }
    }

    private static byte[] aad(String owner, String keyId) {
        return (FORMAT + "\0" + owner + "\0" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private static String requireOwner(String ownerPluginId) {
        String owner = ownerPluginId == null ? "" : ownerPluginId.trim();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("ownerPluginId must not be blank");
        }
        return owner;
    }

    private static String requireEnvelopeValue(
            Map<String, String> envelope, String field, String owner) throws IOException {
        String value = envelope.get(field);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IOException("Invalid plugin credential envelope field for owner: "
                    + owner + "/" + field);
        }
        return value;
    }

    private static String encodeUrl(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decodeUrl(String value, String field, String owner) throws IOException {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!encodeUrl(decoded).equals(value)) {
                Arrays.fill(decoded, (byte) 0);
                throw new IOException("Non-canonical plugin credential envelope field for owner: "
                        + owner + "/" + field);
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Base64 plugin credential envelope field for owner: "
                    + owner + "/" + field, e);
        }
    }

    private static String decodeUtf8(byte[] content) throws IOException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Plugin credential content is not valid UTF-8", e);
        }
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        boolean leadingWhitespace = true;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\f' -> escaped.append("\\f");
                case ' ' -> {
                    if (leadingWhitespace) {
                        escaped.append("\\ ");
                    } else {
                        escaped.append(ch);
                    }
                }
                default -> escaped.append(ch);
            }
            if (!Character.isWhitespace(ch)) {
                leadingWhitespace = false;
            }
        }
        return escaped.toString();
    }

    record Decoded(Map<String, String> values, boolean rewriteWithCurrentKey) {
        Decoded {
            values = Map.copyOf(values);
        }
    }
}
