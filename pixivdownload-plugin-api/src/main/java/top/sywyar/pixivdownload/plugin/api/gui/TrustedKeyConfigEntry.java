package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Toolkit-neutral public trust-key configuration for one plugin repository. */
public record TrustedKeyConfigEntry(String keyId, String algorithm, String publicKey, String state,
                                    String publisher, String trustLabel, Map<String, Object> extraFields) {
    public TrustedKeyConfigEntry {
        keyId = keyId == null ? "" : keyId.trim();
        algorithm = algorithm == null || algorithm.isBlank() ? "Ed25519" : algorithm.trim();
        publicKey = publicKey == null ? "" : publicKey.trim();
        state = state == null || state.isBlank() ? "ACTIVE" : state.trim().toUpperCase(Locale.ROOT);
        publisher = publisher == null ? "" : publisher.trim();
        trustLabel = trustLabel == null ? "" : trustLabel.trim();
        extraFields = extraFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraFields);
    }

    /** Creates a trust key without unknown fields. */
    public static TrustedKeyConfigEntry create(String keyId, String algorithm, String publicKey, String state,
                                               String publisher, String trustLabel) {
        return new TrustedKeyConfigEntry(keyId, algorithm, publicKey, state, publisher, trustLabel,
                new LinkedHashMap<>());
    }
}
