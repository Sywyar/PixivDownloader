package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Toolkit-neutral public trust-key configuration for one plugin repository. */
public record TrustedKeyConfigEntry(String keyId, String algorithm, String publicKey, String state,
                                    String publisher, String trustLabel, Map<String, Object> extraFields) {
    /**
     * Validates and defensively copies one public trust-key configuration.
     *
     * @param keyId stable key id
     * @param algorithm signature algorithm
     * @param publicKey encoded public key
     * @param state trust-key state
     * @param publisher publisher identity
     * @param trustLabel user-visible trust label
     * @param extraFields unknown fields preserved during round trips
     */
    public TrustedKeyConfigEntry {
        keyId = keyId == null ? "" : keyId.trim();
        algorithm = algorithm == null || algorithm.isBlank() ? "Ed25519" : algorithm.trim();
        publicKey = publicKey == null ? "" : publicKey.trim();
        state = state == null || state.isBlank() ? "ACTIVE" : state.trim().toUpperCase(Locale.ROOT);
        publisher = publisher == null ? "" : publisher.trim();
        trustLabel = trustLabel == null ? "" : trustLabel.trim();
        extraFields = extraFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraFields);
    }

    /**
     * Creates a trust key without unknown fields.
     *
     * @param keyId stable key id
     * @param algorithm signature algorithm
     * @param publicKey encoded public key
     * @param state trust-key state
     * @param publisher publisher identity
     * @param trustLabel user-visible trust label
     * @return trust-key configuration
     */
    public static TrustedKeyConfigEntry create(String keyId, String algorithm, String publicKey, String state,
                                               String publisher, String trustLabel) {
        return new TrustedKeyConfigEntry(keyId, algorithm, publicKey, state, publisher, trustLabel,
                new LinkedHashMap<>());
    }
}
