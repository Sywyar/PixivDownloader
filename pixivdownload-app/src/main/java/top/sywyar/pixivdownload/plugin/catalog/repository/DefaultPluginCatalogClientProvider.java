package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.util.Set;
import java.util.EnumMap;
import java.util.Map;

/**
 * 生产环境的 {@link PluginCatalogClientProvider}：按仓库的代理策略装配 SSRF 安全 HTTP 客户端。
 *
 * <ul>
 *   <li>{@link RepositoryProxyPolicy#DIRECT_STRICT}（默认档，任何普通 / 自定义仓库）：仅 https + 拒非公网地址（严格
 *       SSRF）+ 禁重定向 + <b>不走代理</b>，按该仓库的连接 / 读取超时构造。<b>绝不放宽</b>。</li>
 *   <li>{@link RepositoryProxyPolicy#PROXY_TRUSTED}（仅对用户显式信任的仓库，如内嵌官方仓库）：<b>经应用全局代理</b>
 *       （{@code proxy.*}）拉取，仅 https，并按内置主机白名单（GitHub release 资产 CDN {@code *.githubusercontent.com}）
 *       <b>跟随至多五跳</b>重定向。完整性仍由 {@code ExternalPluginInstaller} 的 sha256/size 逐字节兜底——本档放宽只关
 *       SSRF/滥用、<b>不</b>关完整性。代理未启用（{@code proxy.enabled=false}）时直连（仍按白名单跟随重定向）。</li>
 *   <li>{@link RepositoryProxyPolicy#CUSTOM}：采用仓库条目声明的重定向、HTTPS、非公网地址与应用全局代理开关；重定向仍限制
 *       为至多五跳并对每个目标重新校验。</li>
 * </ul>
 *
 * <p>未知 / 不可识别的策略（{@code proxyPolicy} 为 {@code null}）一律抛稳定的
 * {@link PluginCatalogErrorCode#PROXY_POLICY_UNSUPPORTED}，<b>绝不</b>静默回落直连——避免把「显式信任代理」或未知意图
 * 悄悄当成直连。
 */
@Component
public class DefaultPluginCatalogClientProvider implements PluginCatalogClientProvider {

    /**
     * 受信仓库（PROXY_TRUSTED）允许跟随有界重定向的目标主机域：GitHub release 资产 CDN（{@code release-assets.}
     * / {@code objects.githubusercontent.com} 等）统一收口为 {@code githubusercontent.com} 子域。内置常量、不对外开放配置，
     * 缩小可被滥用面（GitHub 自有域，第三方无法注册其子域）。
     */
    static final Set<String> TRUSTED_REDIRECT_HOSTS = Set.of("githubusercontent.com");

    private final ProxyConfig proxyConfig;
    private final Map<RepositoryProxyPolicy, CachedClient> clients = new EnumMap<>(RepositoryProxyPolicy.class);

    public DefaultPluginCatalogClientProvider(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    @Override
    public synchronized PluginCatalogHttpClient clientFor(PluginRepository repository) {
        var policy = repository.proxyPolicy();
        if (policy == null) return create(repository, null);
        boolean proxied = policy != RepositoryProxyPolicy.DIRECT_STRICT
                && (policy != RepositoryProxyPolicy.CUSTOM || repository.useProxy()) && proxyConfig.isEnabled();
        var settings = new Settings(repository.connectTimeoutMs(), repository.readTimeoutMs(),
                policy == RepositoryProxyPolicy.CUSTOM && repository.allowRedirects(),
                policy != RepositoryProxyPolicy.CUSTOM || repository.strictHttps(),
                policy == RepositoryProxyPolicy.CUSTOM && repository.allowNonPublicAddresses(),
                proxied ? proxyConfig.getHost() : null, proxied ? proxyConfig.getPort() : 0);
        var cached = clients.get(policy);
        if (cached != null && cached.settings().equals(settings)) return cached.client();
        ProxySelector proxy = proxied ? ProxySelector.of(new InetSocketAddress(settings.host(), settings.port())) : null;
        var client = create(repository, proxy);
        // 每种策略只保留当前配置；仓库代次、签名与内容校验不属于连接池身份。
        clients.put(policy, new CachedClient(settings, client));
        return client;
    }

    private PluginCatalogHttpClient create(PluginRepository repository, ProxySelector proxy) {
        RepositoryProxyPolicy policy = repository.proxyPolicy();
        if (policy == RepositoryProxyPolicy.DIRECT_STRICT) {
            // 直连严格档：仅 https、拒非公网（严格 SSRF）、禁重定向、不走代理。
            return new PluginCatalogHttpClient(true, false,
                    (int) repository.connectTimeoutMs(), (int) repository.readTimeoutMs());
        }
        if (policy == RepositoryProxyPolicy.PROXY_TRUSTED) {
            // 受信档：经应用全局代理拉取（启用时）；仅 https；按内置白名单跟随有界重定向（GitHub release 资产 CDN）。
            return new PluginCatalogHttpClient(true, false,
                    (int) repository.connectTimeoutMs(), (int) repository.readTimeoutMs(),
                    proxy, TRUSTED_REDIRECT_HOSTS);
        }
        if (policy == RepositoryProxyPolicy.GITHUB_RELEASES) {
            return new PluginCatalogHttpClient(true, false,
                    (int) repository.connectTimeoutMs(), (int) repository.readTimeoutMs(),
                    proxy, true, TRUSTED_REDIRECT_HOSTS, false,
                    1, Set.of("github.com", "githubusercontent.com"));
        }
        if (policy == RepositoryProxyPolicy.CUSTOM) {
            return new PluginCatalogHttpClient(repository.strictHttps(), repository.allowNonPublicAddresses(),
                    (int) repository.connectTimeoutMs(), (int) repository.readTimeoutMs(),
                    proxy,
                    repository.allowRedirects(), Set.of(), true);
        }
        // 无法识别的未知策略（policy == null）：稳定报错，绝不静默回落直连。
        throw new PluginCatalogException(PluginCatalogErrorCode.PROXY_POLICY_UNSUPPORTED,
                "unsupported proxy policy '" + repository.rawProxyPolicy() + "' for repository "
                        + repository.repositoryId());
    }

    private record Settings(long connectTimeout, long readTimeout, boolean redirects, boolean https,
                            boolean nonPublic, String host, int port) { }
    private record CachedClient(Settings settings, PluginCatalogHttpClient client) { }
}
