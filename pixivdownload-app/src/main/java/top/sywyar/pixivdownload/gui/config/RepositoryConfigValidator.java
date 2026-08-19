package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.RepositoryProxyPolicy;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Headless validation for the app-owned custom plugin repository editor. */
public final class RepositoryConfigValidator {
    static final long MAX_BYTES_LIMIT = 10L * 1024 * 1024 * 1024;
    static final long MAX_TIMEOUT_MS = 60L * 60 * 1000;

    private RepositoryConfigValidator() {
    }

    /** Validates a repository id against host-reserved ids and the other configured repositories. */
    public static String validateId(String id, List<RepositoryConfigEntry> others, Set<String> reservedIds) {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty()) return "gui.config.market.repo.error.id-empty";
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (reservedIds != null && reservedIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals)) {
            return "gui.config.market.repo.error.id-reserved";
        }
        if (others != null && others.stream().anyMatch(other -> normalized.equals(
                other.id().trim().toLowerCase(Locale.ROOT)))) {
            return "gui.config.market.repo.error.id-duplicate";
        }
        return null;
    }

    /** Validates a manifest URL using the default strict-HTTPS policy. */
    public static String validateManifestUrl(String manifestUrl) {
        return validateManifestUrl(manifestUrl, true);
    }

    /** Validates an absolute HTTP(S) manifest URL, optionally requiring HTTPS. */
    public static String validateManifestUrl(String manifestUrl, boolean strictHttps) {
        String url = manifestUrl == null ? "" : manifestUrl.trim();
        if (url.isEmpty()) return "gui.config.market.repo.error.url-empty";
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException invalid) {
            return "gui.config.market.repo.error.url-invalid";
        }
        if (!uri.isAbsolute()) return "gui.config.market.repo.error.url-not-absolute";
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) && strictHttps) {
            return "gui.config.market.repo.error.url-not-https";
        }
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            return "gui.config.market.repo.error.url-unsupported-scheme";
        }
        return uri.getHost() == null || uri.getHost().isBlank()
                ? "gui.config.market.repo.error.url-no-host" : null;
    }

    /** Validates a persisted proxy policy without silently downgrading unknown values. */
    public static String validateProxyPolicy(String proxyPolicy) {
        String value = proxyPolicy == null ? "" : proxyPolicy.trim();
        if (value.isEmpty()) return null;
        return java.util.Arrays.stream(RepositoryProxyPolicy.values())
                .anyMatch(policy -> policy.configId().equalsIgnoreCase(value))
                ? null : "gui.config.market.repo.error.proxy-policy-unknown";
    }

    /** Validates an optional timeout override in milliseconds. */
    public static String validateTimeoutOverride(String raw) {
        return validatePositiveOptional(raw, MAX_TIMEOUT_MS);
    }

    /** Validates an optional byte-size override. */
    public static String validateSizeOverride(String raw) {
        return validatePositiveOptional(raw, MAX_BYTES_LIMIT);
    }

    private static String validatePositiveOptional(String raw, long upperBound) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return null;
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            return "gui.config.market.repo.error.number-invalid";
        }
        if (value <= 0) return "gui.config.market.repo.error.number-not-positive";
        return value > upperBound ? "gui.config.market.repo.error.number-too-large" : null;
    }

    /** Parses a validated optional numeric override; blank means inherit. */
    public static long parseOverride(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return 0;
        try {
            long value = Long.parseLong(text);
            return value > 0 ? value : 0;
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    /** Validates one repository trust-key form. */
    public static String validateTrustedKey(String keyId, String algorithm, String publicKey, String state,
                                            List<String> otherKeyIds) {
        String normalizedId = keyId == null ? "" : keyId.trim();
        if (normalizedId.isEmpty()) return "gui.config.market.repo.trust.error.key-id-empty";
        if (otherKeyIds != null && otherKeyIds.stream().filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalizedId.toLowerCase(Locale.ROOT)::equals)) {
            return "gui.config.market.repo.trust.error.key-id-duplicate";
        }
        if (!SignatureMetadata.ED25519.equals(algorithm == null ? "" : algorithm.trim())) {
            return "gui.config.market.repo.trust.error.algorithm-unsupported";
        }
        String encoded = publicKey == null ? "" : publicKey.trim();
        if (encoded.isEmpty()) return "gui.config.market.repo.trust.error.public-key-empty";
        try {
            Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            return "gui.config.market.repo.trust.error.public-key-invalid";
        }
        return Set.of("ACTIVE", "RETIRED", "REVOKED").contains(state == null ? "" : state.trim())
                ? null : "gui.config.market.repo.trust.error.state-invalid";
    }
}
