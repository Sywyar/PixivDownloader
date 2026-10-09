package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link DefaultPluginCatalogClientProvider} 单测：按代理策略装配客户端——{@code direct-strict}、{@code proxy-trusted} 与 custom
 * （含内嵌官方仓库）均返回客户端；代理关闭时受信档仍可装配（直连 + 白名单重定向）；未知策略（{@code null}）抛稳定的
 * {@code PROXY_POLICY_UNSUPPORTED}，不静默回落直连。
 */
@DisplayName("DefaultPluginCatalogClientProvider 仓库 HTTP 客户端装配")
class DefaultPluginCatalogClientProviderTest {

    private final DefaultPluginCatalogClientProvider provider =
            new DefaultPluginCatalogClientProvider(new ProxyConfig());

    private static PluginRepository repo(RepositoryProxyPolicy policy, String rawPolicy) {
        return new PluginRepository("r", "k", "https://x.example/m.json",
                true, false, false, policy, rawPolicy, false, true, false, false,
                3_000, 4_000, 1024, 2048, List.of());
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
    @DisplayName("custom 仓库：按仓库级网络开关返回 HTTP 客户端")
    void customBuildsClient() {
        PluginRepository custom = new PluginRepository("custom", "k", "http://127.0.0.1/m.json",
                true, false, false, RepositoryProxyPolicy.CUSTOM, "custom",
                true, false, true, true, 3_000, 4_000, 1024, 2048, List.of());
        assertThat(provider.clientFor(custom)).isNotNull();
    }

    @Test
    @DisplayName("未知策略仓库（proxyPolicy=null）：抛 PROXY_POLICY_UNSUPPORTED")
    void unknownPolicyUnsupported() {
        PluginCatalogException ex = catchThrowableOfType(
                () -> provider.clientFor(repo(null, "socks5")), PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.PROXY_POLICY_UNSUPPORTED);
    }

    @Test
    @DisplayName("相同网络策略复用连接，代理变更及不同策略不能复用旧客户端")
    void reusesEffectiveSettingsAndInvalidatesProxyChanges() {
        var proxy = new ProxyConfig();
        proxy.setEnabled(true);
        proxy.setHost("127.0.0.1");
        proxy.setPort(7890);
        var clients = new DefaultPluginCatalogClientProvider(proxy);
        var official = PluginRepository.official(true, 3_000, 4_000, 1024, 2048);
        var first = clients.clientFor(official);
        assertThat(clients.clientFor(repo(RepositoryProxyPolicy.PROXY_TRUSTED, "proxy-trusted"))).isSameAs(first);
        assertThat(clients.clientFor(repo(RepositoryProxyPolicy.DIRECT_STRICT, "direct-strict"))).isNotSameAs(first);
        proxy.setPort(7891);
        var changed = clients.clientFor(official);
        assertThat(changed).isNotSameAs(first);
        assertThat(clients.clientFor(official)).isSameAs(changed);
        proxy.setEnabled(false);
        assertThat(clients.clientFor(official)).isNotSameAs(changed);
        assertThat(clients.clientFor(PluginRepository.official(true, 5_000, 4_000, 1024, 2048)))
                .isNotSameAs(clients.clientFor(official));
    }

    @Test
    @DisplayName("连续元数据读取实际复用同一条 HTTP 连接")
    void reusesTcpConnection() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var peers = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        server.createContext("/metadata", exchange -> {
            peers.add(exchange.getRemoteAddress().getPort());
            exchange.sendResponseHeaders(200, 2);
            try (var body = exchange.getResponseBody()) { body.write(new byte[] {1, 2}); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/metadata";
            var repository = new PluginRepository("test", "", url, true, false, false,
                    RepositoryProxyPolicy.CUSTOM, "custom", false, false, true, false,
                    3000, 4000, 1024, 2048, List.of());
            for (int i = 0; i < 4; i++) assertThat(provider.clientFor(repository).fetchBytes(url, 16))
                    .containsExactly(1, 2);
            assertThat(peers).hasSize(1);
        } finally { server.stop(0); }
    }
}
