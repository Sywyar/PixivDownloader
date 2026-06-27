package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RepositoryProxyPolicy} 单测：配置串解析（缺省 → 直连默认、大小写 / 空白不敏感、未知 → {@code null} 不静默降级）
 * 与稳定 configId。
 */
@DisplayName("RepositoryProxyPolicy 仓库代理策略解析")
class RepositoryProxyPolicyTest {

    @Test
    @DisplayName("configId 稳定：direct-strict / proxy-trusted")
    void configIds() {
        assertThat(RepositoryProxyPolicy.DIRECT_STRICT.configId()).isEqualTo("direct-strict");
        assertThat(RepositoryProxyPolicy.PROXY_TRUSTED.configId()).isEqualTo("proxy-trusted");
        assertThat(RepositoryProxyPolicy.CUSTOM.configId()).isEqualTo("custom");
        assertThat(RepositoryProxyPolicy.DEFAULT).isEqualTo(RepositoryProxyPolicy.DIRECT_STRICT);
    }

    @Test
    @DisplayName("null / 空白 → 默认（直连严格）")
    void blankIsDefault() {
        assertThat(RepositoryProxyPolicy.fromConfig(null)).isEqualTo(RepositoryProxyPolicy.DIRECT_STRICT);
        assertThat(RepositoryProxyPolicy.fromConfig("")).isEqualTo(RepositoryProxyPolicy.DIRECT_STRICT);
        assertThat(RepositoryProxyPolicy.fromConfig("   ")).isEqualTo(RepositoryProxyPolicy.DIRECT_STRICT);
    }

    @Test
    @DisplayName("已知策略：大小写 / 首尾空白不敏感")
    void knownPolicies() {
        assertThat(RepositoryProxyPolicy.fromConfig("direct-strict")).isEqualTo(RepositoryProxyPolicy.DIRECT_STRICT);
        assertThat(RepositoryProxyPolicy.fromConfig("  PROXY-TRUSTED ")).isEqualTo(RepositoryProxyPolicy.PROXY_TRUSTED);
        assertThat(RepositoryProxyPolicy.fromConfig(" CUSTOM ")).isEqualTo(RepositoryProxyPolicy.CUSTOM);
        assertThat(RepositoryProxyPolicy.fromConfig("Direct-Strict")).isEqualTo(RepositoryProxyPolicy.DIRECT_STRICT);
    }

    @Test
    @DisplayName("未知策略 → null（不静默回落到直连）")
    void unknownIsNull() {
        assertThat(RepositoryProxyPolicy.fromConfig("bogus")).isNull();
        assertThat(RepositoryProxyPolicy.fromConfig("socks5")).isNull();
    }
}
