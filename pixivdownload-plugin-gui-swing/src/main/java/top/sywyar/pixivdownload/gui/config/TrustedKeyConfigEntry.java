package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * GUI 仓库编辑器持有的单个仓库级 trusted key 配置（对应
 * {@code plugin-catalog.repositories[*].trusted-keys[*]}）。公钥为 Base64 X.509 SubjectPublicKeyInfo；不承载私钥。
 */
public record TrustedKeyConfigEntry(
        String keyId,
        String algorithm,
        String publicKey,
        String state,
        String publisher,
        String trustLabel,
        Map<String, Object> extraFields) {

    public TrustedKeyConfigEntry {
        keyId = keyId == null ? "" : keyId.trim();
        algorithm = algorithm == null || algorithm.isBlank() ? SignatureMetadata.ED25519 : algorithm.trim();
        publicKey = publicKey == null ? "" : publicKey.trim();
        state = state == null || state.isBlank()
                ? TrustedPluginKey.State.ACTIVE.name()
                : state.trim().toUpperCase(Locale.ROOT);
        publisher = publisher == null ? "" : publisher.trim();
        trustLabel = trustLabel == null ? "" : trustLabel.trim();
        extraFields = extraFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraFields);
    }

    public static TrustedKeyConfigEntry create(String keyId, String algorithm, String publicKey, String state,
                                               String publisher, String trustLabel) {
        return new TrustedKeyConfigEntry(keyId, algorithm, publicKey, state, publisher, trustLabel,
                new LinkedHashMap<>());
    }

    public static TrustedKeyConfigEntry fromTrustedPluginKey(TrustedPluginKey key) {
        return create(key.keyId(), key.algorithm(), key.publicKeySpkiBase64(), key.state().name(),
                key.publisher(), key.trustLabel());
    }

    public static TrustedKeyConfigEntry officialRoot() {
        return fromTrustedPluginKey(PluginTrustStores.builtInOfficialRoot());
    }

    public boolean matchesBuiltInOfficialRoot() {
        return matches(PluginTrustStores.builtInOfficialRoot());
    }

    private boolean matches(TrustedPluginKey key) {
        return keyId.equals(key.keyId())
                && algorithm.equals(key.algorithm())
                && publicKey.equals(key.publicKeySpkiBase64())
                && state.equals(key.state().name())
                && publisher.equals(key.publisher())
                && trustLabel.equals(key.trustLabel());
    }
}
