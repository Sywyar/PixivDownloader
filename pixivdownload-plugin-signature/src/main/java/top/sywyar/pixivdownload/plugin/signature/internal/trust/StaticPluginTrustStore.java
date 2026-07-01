package top.sywyar.pixivdownload.plugin.signature.internal.trust;

import top.sywyar.pixivdownload.plugin.signature.PluginTrustStore;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变内存信任根存储。
 */
public final class StaticPluginTrustStore implements PluginTrustStore {

    private final Map<String, TrustedPluginKey> keys;

    public StaticPluginTrustStore(Collection<TrustedPluginKey> keys) {
        Map<String, TrustedPluginKey> copy = new LinkedHashMap<>();
        for (TrustedPluginKey key : Objects.requireNonNull(keys, "keys")) {
            validate(key);
            TrustedPluginKey previous = copy.putIfAbsent(key.keyId(), key);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate trusted plugin key id: " + key.keyId());
            }
        }
        this.keys = Map.copyOf(copy);
    }

    @Override
    public Optional<TrustedPluginKey> findByKeyId(String keyId) {
        return Optional.ofNullable(keyId == null ? null : keys.get(keyId));
    }

    private static void validate(TrustedPluginKey key) {
        if (key == null) {
            throw new IllegalArgumentException("trusted plugin key must not be null");
        }
        requireText(key.keyId(), "trusted plugin key id");
        requireText(key.algorithm(), "trusted plugin key algorithm");
        requireText(key.publicKeySpkiBase64(), "trusted plugin public key");
        if (!SignatureMetadata.ED25519.equals(key.algorithm())) {
            throw new IllegalArgumentException("unsupported trusted plugin key algorithm: " + key.algorithm());
        }
        KeyParsing.ed25519PublicKey(key.publicKeySpkiBase64());
        if (key.state() == null) {
            throw new IllegalArgumentException("trusted plugin key state must not be null");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
        }
    }
}
