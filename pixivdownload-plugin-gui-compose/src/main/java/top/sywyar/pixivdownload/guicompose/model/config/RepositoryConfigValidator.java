package top.sywyar.pixivdownload.guicompose.model.config;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.RepositoryProxyPolicy;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Compose 自定义插件仓库表单使用的无界面输入验证器。 */
public final class RepositoryConfigValidator {
    static final long MAX_BYTES_LIMIT = 10L * 1024 * 1024 * 1024;
    static final long MAX_TIMEOUT_MS = 60L * 60 * 1000;

    private RepositoryConfigValidator() {
    }

    /** 对照宿主保留 id 和其它已配置仓库验证仓库 id。 */
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

    /** 使用默认严格 HTTPS 策略验证清单 URL。 */
    public static String validateManifestUrl(String manifestUrl) {
        return validateManifestUrl(manifestUrl, true);
    }

    /** 验证绝对 HTTP(S) 清单 URL，并可选择要求 HTTPS。 */
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

    /** 验证已持久化的代理策略，不静默降级未知值。 */
    public static String validateProxyPolicy(String proxyPolicy) {
        String value = proxyPolicy == null ? "" : proxyPolicy.trim();
        if (value.isEmpty()) return null;
        return java.util.Arrays.stream(RepositoryProxyPolicy.values())
                .anyMatch(policy -> policy.configId().equalsIgnoreCase(value))
                ? null : "gui.config.market.repo.error.proxy-policy-unknown";
    }

    /** 验证可选的超时毫秒覆盖值。 */
    public static String validateTimeoutOverride(String raw) {
        return validatePositiveOptional(raw, MAX_TIMEOUT_MS);
    }

    /** 验证可选的字节大小覆盖值。 */
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

    /** 解析已验证的可选数值覆盖；空白表示继承。 */
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

    /** 验证一个仓库信任密钥表单。 */
    public static String validateTrustedKey(String keyId, String algorithm, String publicKey, String state,
                                            List<String> otherKeyIds) {
        String normalizedId = keyId == null ? "" : keyId.trim();
        if (normalizedId.isEmpty()) return "gui.config.market.repo.trust.error.key-id-empty";
        if (otherKeyIds != null && otherKeyIds.stream().filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalizedId.toLowerCase(Locale.ROOT)::equals)) {
            return "gui.config.market.repo.trust.error.key-id-duplicate";
        }
        if (!"Ed25519".equals(algorithm == null ? "" : algorithm.trim())) {
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
