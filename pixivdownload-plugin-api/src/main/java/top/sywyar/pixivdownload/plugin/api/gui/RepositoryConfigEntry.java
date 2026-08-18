package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Toolkit-neutral configuration for one custom plugin repository. */
public record RepositoryConfigEntry(
        String id, String displayNameKey, String manifestUrl, boolean enabled, String proxyPolicy,
        boolean allowRedirects, boolean strictHttps, boolean allowNonPublicAddresses, boolean useProxy,
        long connectTimeoutMs, long readTimeoutMs, long maxManifestBytes, long maxPackageBytes,
        List<TrustedKeyConfigEntry> trustedKeys, Map<String, Object> extraFields) {

    public RepositoryConfigEntry {
        id = id == null ? "" : id;
        displayNameKey = displayNameKey == null ? "" : displayNameKey;
        manifestUrl = manifestUrl == null ? "" : manifestUrl;
        proxyPolicy = proxyPolicy == null || proxyPolicy.isBlank() ? "direct-strict" : proxyPolicy;
        trustedKeys = trustedKeys == null ? List.of() : List.copyOf(trustedKeys);
        extraFields = extraFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraFields);
    }

    public RepositoryConfigEntry(String id, String displayNameKey, String manifestUrl, boolean enabled,
                                 String proxyPolicy, boolean allowRedirects, boolean strictHttps,
                                 boolean allowNonPublicAddresses, boolean useProxy, long connectTimeoutMs,
                                 long readTimeoutMs, long maxManifestBytes, long maxPackageBytes,
                                 Map<String, Object> extraFields) {
        this(id, displayNameKey, manifestUrl, enabled, proxyPolicy, allowRedirects, strictHttps,
                allowNonPublicAddresses, useProxy, connectTimeoutMs, readTimeoutMs, maxManifestBytes,
                maxPackageBytes, List.of(), extraFields);
    }

    /** Creates a repository with the standard network policy fields. */
    public static RepositoryConfigEntry create(String id, String displayNameKey, String manifestUrl, boolean enabled,
                                               String proxyPolicy, long connectTimeoutMs, long readTimeoutMs,
                                               long maxManifestBytes, long maxPackageBytes) {
        return new RepositoryConfigEntry(id, displayNameKey, manifestUrl, enabled, proxyPolicy,
                false, true, false, false, connectTimeoutMs, readTimeoutMs, maxManifestBytes, maxPackageBytes,
                List.of(), new LinkedHashMap<>());
    }

    /** Creates a repository with explicit custom network policy fields. */
    public static RepositoryConfigEntry createCustom(String id, String displayNameKey, String manifestUrl,
                                                     boolean enabled, boolean allowRedirects, boolean strictHttps,
                                                     boolean allowNonPublicAddresses, boolean useProxy,
                                                     long connectTimeoutMs, long readTimeoutMs,
                                                     long maxManifestBytes, long maxPackageBytes) {
        return new RepositoryConfigEntry(id, displayNameKey, manifestUrl, enabled, "custom", allowRedirects,
                strictHttps, allowNonPublicAddresses, useProxy, connectTimeoutMs, readTimeoutMs,
                maxManifestBytes, maxPackageBytes, List.of(), new LinkedHashMap<>());
    }
}
