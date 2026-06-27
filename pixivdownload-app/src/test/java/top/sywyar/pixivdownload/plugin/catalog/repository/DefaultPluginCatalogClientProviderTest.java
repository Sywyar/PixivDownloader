package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link DefaultPluginCatalogClientProvider} 单测：按代理策略装配客户端——{@code direct-strict} 与 {@code proxy-trusted}
 * （含内嵌官方仓库）均返回客户端；代理关闭时受信档仍可装配（直连 + 白名单重定向）；未知策略（{@code null}）抛稳定的
 * {@code PROXY_POLICY_UNSUPPORTED}，不静默回落直连。
 */
@DisplayName("DefaultPluginCatalogClientProvider 仓库 HTTP 客户端装配")
class DefaultPluginCatalogClientProviderTest {

    private final DefaultPluginCatalogClientProvider provider =
            new DefaultPluginCatalogClientProvider(new ProxyConfig());

    private static PluginRepository repo(RepositoryProxyPolicy policy, String rawPolicy) {
        return new PluginRepository("r", "k", "https://x.example/m.json",
                true, false, false, policy, rawPolicy, 3_000, 4_000, 1024, 2048);
    }

    @Test
    @DisplayName("direct-strict 仓库：返回非空 HTTP 客户端")
    void directStrictBuildsClient() {
        PluginCatalogHttpClient client = provider.clientFor(repo(RepositoryProxyPolicy.DIRECT_STRICT, "direct-strict"));
        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("proxy-trusted 仓库（含内嵌官方）：返回非空 HTTP 客户端")
    void proxyTrustedBuildsClient() {
        assertThat(provider.clientFor(repo(RepositoryProxyPolicy.PROXY_TRUSTED, "proxy-trusted"))).isNotNull();
        assertThat(provider.clientFor(PluginRepository.official(true, 3_000, 4_000, 1024, 2048))).isNotNull();
    }

    @Test
    @DisplayName("proxy-trusted 但全局代理关闭：仍返回非空客户端（直连 + 白名单重定向）")
    void proxyTrustedWithProxyDisabled() {
        ProxyConfig disabled = new ProxyConfig();
        disabled.setEnabled(false);
        DefaultPluginCatalogClientProvider p = new DefaultPluginCatalogClientProvider(disabled);
        assertThat(p.clientFor(repo(RepositoryProxyPolicy.PROXY_TRUSTED, "proxy-trusted"))).isNotNull();
    }

    @Test
    @DisplayName("未知策略仓库（proxyPolicy=null）：抛 PROXY_POLICY_UNSUPPORTED")
    void unknownPolicyUnsupported() {
        PluginCatalogException ex = catchThrowableOfType(
                () -> provider.clientFor(repo(null, "socks5")), PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.PROXY_POLICY_UNSUPPORTED);
    }
}
