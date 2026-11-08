package top.sywyar.pixivdownload.update;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 更新下载的网络策略：官方 Release 经代理解析，自定义来源保持逐跳公网地址检查。 */
@Component
public class UpdateHttpClientProvider {
    private static final Set<String> GITHUB_HOSTS = Set.of("github.com", "githubusercontent.com");
    private static final String RELEASE_PATH = "/" + AppInfo.GITHUB_REPOSITORY + "/releases/";

    private final ProxyConfig proxyConfig;
    private final Map<Boolean, CachedClient> clients = new HashMap<>();

    public UpdateHttpClientProvider(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    public synchronized PluginCatalogHttpClient clientFor(String url) {
        boolean official = isOfficialReleaseUrl(url);
        boolean proxied = proxyConfig.isEnabled();
        var settings = new Settings(proxied ? proxyConfig.getHost() : null, proxied ? proxyConfig.getPort() : 0);
        var cached = clients.get(official);
        if (cached != null && cached.settings().equals(settings)) return cached.client();
        ProxySelector proxy = proxied ? ProxySelector.of(new InetSocketAddress(settings.host(), settings.port())) : null;
        // 官方入口在整个重定向链中固定 GitHub 域名边界；代理的 DNS 视图不由本机 Fake-IP 判断。
        var client = new PluginCatalogHttpClient(true, false, 30_000, 60_000, proxy,
                true, official ? GITHUB_HOSTS : Set.of(), !official,
                5, official ? GITHUB_HOSTS : Set.of());
        clients.put(official, new CachedClient(settings, client));
        return client;
    }

    private static boolean isOfficialReleaseUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawPath() != null && uri.getRawPath().startsWith(RELEASE_PATH);
        } catch (IllegalArgumentException invalid) {
            // 格式错误仍交给下载客户端返回原有受控错误。
            return false;
        }
    }

    private record Settings(String host, int port) { }
    private record CachedClient(Settings settings, PluginCatalogHttpClient client) { }
}
