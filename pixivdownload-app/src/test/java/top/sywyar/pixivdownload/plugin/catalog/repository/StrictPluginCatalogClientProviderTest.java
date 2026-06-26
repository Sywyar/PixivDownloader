package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link StrictPluginCatalogClientProvider} 单测：仅为 {@code direct-strict} 仓库装配客户端；{@code proxy-trusted}
 * （运行时未接线）与未知策略一律抛稳定的 {@code PROXY_POLICY_UNSUPPORTED}，不静默回落直连。
 */
@DisplayName("StrictPluginCatalogClientProvider 仓库 HTTP 客户端装配")
class StrictPluginCatalogClientProviderTest {

    private final StrictPluginCatalogClientProvider provider = new StrictPluginCatalogClientProvider();

    @Test
    @DisplayName("direct-strict 仓库：返回非空 HTTP 客户端")
    void directStrictBuildsClient() {
        PluginCatalogHttpClient client = provider.clientFor(
                PluginRepository.official(true, 3_000, 4_000, 1024, 2048));
        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("proxy-trusted 仓库：抛 PROXY_POLICY_UNSUPPORTED")
    void proxyTrustedUnsupported() {
        PluginRepository repo = new PluginRepository("p", "k", "https://x.example/m.json",
                true, false, false, RepositoryProxyPolicy.PROXY_TRUSTED, "proxy-trusted",
                3_000, 4_000, 1024, 2048);

        PluginCatalogException ex = catchThrowableOfType(
                () -> provider.clientFor(repo), PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.PROXY_POLICY_UNSUPPORTED);
    }

    @Test
    @DisplayName("未知策略仓库（proxyPolicy=null）：抛 PROXY_POLICY_UNSUPPORTED")
    void unknownPolicyUnsupported() {
        PluginRepository repo = new PluginRepository("u", "k", "https://x.example/m.json",
                true, false, false, null, "socks5", 3_000, 4_000, 1024, 2048);

        PluginCatalogException ex = catchThrowableOfType(
                () -> provider.clientFor(repo), PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.PROXY_POLICY_UNSUPPORTED);
    }
}
