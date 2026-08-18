package top.sywyar.pixivdownload.plugin.catalog.repository;

import java.util.Locale;

public enum RepositoryProxyPolicy {
    DIRECT_STRICT("direct-strict"), PROXY_TRUSTED("proxy-trusted"), CUSTOM("custom");
    public static final RepositoryProxyPolicy DEFAULT = DIRECT_STRICT;
    private final String configId;
    RepositoryProxyPolicy(String configId) { this.configId = configId; }
    public String configId() { return configId; }
    public static RepositoryProxyPolicy fromConfig(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (RepositoryProxyPolicy policy : values()) if (policy.configId.equals(normalized)) return policy;
        return null;
    }
}
