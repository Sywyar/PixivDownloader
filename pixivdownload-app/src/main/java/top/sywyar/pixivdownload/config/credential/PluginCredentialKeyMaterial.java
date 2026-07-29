package top.sywyar.pixivdownload.config.credential;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Host-owned root keyring for encrypted plugin credentials.
 *
 * <p>The current key encrypts new writes. The open-source fallback remains readable so an
 * installation built from source can be upgraded to an official build without losing credentials.
 * Root keys never enter a plugin child context or a stable plugin contract.
 */
final class PluginCredentialKeyMaterial {

    static final String RESOURCE_NAME = "plugin-credential-key.properties";
    static final String PROFILE_OPEN_SOURCE = "open-source";
    static final String PROFILE_PRODUCTION = "production";
    private static final int ROOT_KEY_BYTES = 32;

    private final String profile;
    private final KeyEntry current;
    private final Map<String, KeyEntry> byId;

    private PluginCredentialKeyMaterial(String profile, byte[] currentKey, byte[] fallbackKey) {
        this.profile = requireProfile(profile);
        this.current = KeyEntry.of(currentKey);
        LinkedHashMap<String, KeyEntry> entries = new LinkedHashMap<>();
        entries.put(current.id(), current);
        KeyEntry fallback = KeyEntry.of(fallbackKey);
        entries.putIfAbsent(fallback.id(), fallback);
        this.byId = Map.copyOf(entries);
    }

    static PluginCredentialKeyMaterial load() {
        Properties properties = new Properties();
        ClassLoader loader = PluginCredentialKeyMaterial.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IllegalStateException("Missing plugin credential key resource: " + RESOURCE_NAME);
            }
            properties.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load plugin credential key resource", e);
        }
        return fromProperties(properties);
    }

    static PluginCredentialKeyMaterial fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String profile = requireProfile(properties.getProperty("profile"));
        byte[] current = decodeRootKey(
                properties.getProperty("current-key-base64"), "current-key-base64");
        byte[] fallback = decodeRootKey(
                properties.getProperty("open-source-fallback-key-base64"),
                "open-source-fallback-key-base64");
        try {
            if (PROFILE_OPEN_SOURCE.equals(profile) && !Arrays.equals(current, fallback)) {
                throw new IllegalStateException(
                        "Open-source plugin credential profile must use its published fallback key");
            }
            if (PROFILE_PRODUCTION.equals(profile) && Arrays.equals(current, fallback)) {
                throw new IllegalStateException(
                        "Production plugin credential key must differ from the open-source fallback");
            }
            return new PluginCredentialKeyMaterial(profile, current, fallback);
        } finally {
            Arrays.fill(current, (byte) 0);
            Arrays.fill(fallback, (byte) 0);
        }
    }

    static PluginCredentialKeyMaterial forTesting(byte[] currentKey, byte[] fallbackKey) {
        return new PluginCredentialKeyMaterial(
                Arrays.equals(currentKey, fallbackKey) ? PROFILE_OPEN_SOURCE : PROFILE_PRODUCTION,
                currentKey,
                fallbackKey);
    }

    String profile() {
        return profile;
    }

    String currentKeyId() {
        return current.id();
    }

    byte[] currentRootKey() {
        return current.key();
    }

    byte[] rootKey(String keyId) throws IOException {
        KeyEntry entry = byId.get(keyId);
        if (entry == null) {
            throw new IOException("Unknown plugin credential key id");
        }
        return entry.key();
    }

    boolean isCurrent(String keyId) {
        return current.id().equals(keyId);
    }

    private static String requireProfile(String profile) {
        String normalized = profile == null ? "" : profile.trim();
        if (!normalized.equals(profile)
                || !PROFILE_OPEN_SOURCE.equals(normalized) && !PROFILE_PRODUCTION.equals(normalized)) {
            throw new IllegalStateException("Unsupported plugin credential key profile");
        }
        return normalized;
    }

    private static byte[] decodeRootKey(String encoded, String propertyName) {
        if (encoded == null || encoded.isBlank() || !encoded.equals(encoded.trim())) {
            throw new IllegalStateException("Invalid plugin credential key property: " + propertyName);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid Base64 plugin credential key property: " + propertyName, e);
        }
        if (decoded.length != ROOT_KEY_BYTES
                || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException(
                    "Plugin credential key must be canonical Base64 for exactly 32 bytes: "
                            + propertyName);
        }
        return decoded;
    }

    private record KeyEntry(String id, byte[] key) {

        private KeyEntry {
            Objects.requireNonNull(id, "id");
            key = key.clone();
        }

        private static KeyEntry of(byte[] key) {
            Objects.requireNonNull(key, "key");
            if (key.length != ROOT_KEY_BYTES) {
                throw new IllegalArgumentException("Plugin credential root key must contain 32 bytes");
            }
            return new KeyEntry(fingerprint(key), key);
        }

        @Override
        public byte[] key() {
            return key.clone();
        }
    }

    private static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
