package top.sywyar.pixivdownload.config;

import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;

import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
public class RestTemplateConfig {

    private final ProxyConfig proxyConfig;

    /**
     * 通用 RestTemplate，用于代理 Pixiv API 请求（短超时）。
     */
    @Bean
    public RestTemplate restTemplate() {
        return buildRestTemplate(15_000, 30_000);
    }

    /**
     * Pixiv 凭证请求专用客户端。主页、JSON 与收藏请求会携带登录 Cookie，因此禁止底层自动跟随重定向，避免敏感
     * 请求头被转发到未经端口校验的目标。
     */
    @Bean("pixivCredentialRestTemplate")
    public RestTemplate pixivCredentialRestTemplate() {
        return buildRestTemplate(15_000, 30_000, new DynamicProxyRoutePlanner(proxyConfig), false, false);
    }

    /**
     * 下载专用 RestTemplate，超时更长、连接池更大。
     */
    @Bean("downloadRestTemplate")
    public RestTemplate downloadRestTemplate() {
        return buildRestTemplate(30_000, 60_000, new DynamicProxyRoutePlanner(proxyConfig));
    }

    /**
     * Pixiv 图片稳定端口专用客户端。图片请求同样可能携带登录 Cookie，禁止自动重定向后由调用方将 3xx 作为失败处理。
     */
    @Bean("pixivImageRestTemplate")
    public RestTemplate pixivImageRestTemplate() {
        return buildRestTemplate(30_000, 60_000, new DynamicProxyRoutePlanner(proxyConfig), false, false);
    }

    private RestTemplate buildRestTemplate(int connectTimeoutMs, int socketTimeoutMs) {
        return buildRestTemplate(connectTimeoutMs, socketTimeoutMs, new DynamicProxyRoutePlanner(proxyConfig));
    }

    private RestTemplate buildRestTemplate(int connectTimeoutMs, int socketTimeoutMs, HttpRoutePlanner routePlanner) {
        return buildRestTemplate(connectTimeoutMs, socketTimeoutMs, routePlanner, true, true);
    }

    private RestTemplate buildRestTemplate(
            int connectTimeoutMs,
            int socketTimeoutMs,
            HttpRoutePlanner routePlanner,
            boolean followRedirects,
            boolean manageCookies
    ) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .setSocketTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS)
                .build();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(20);
        connectionManager.setDefaultMaxPerRoute(10);
        connectionManager.setDefaultConnectionConfig(connectionConfig);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .build();

        var httpClientBuilder = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig);
        if (!followRedirects) {
            httpClientBuilder.disableRedirectHandling();
        }
        if (!manageCookies) {
            httpClientBuilder.disableCookieManagement();
        }
        if (routePlanner != null) {
            httpClientBuilder.setRoutePlanner(routePlanner);
        }
        HttpClient httpClient = httpClientBuilder.build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add((request, body, execution) -> {
            PixivRequestHeaders.applyBrowserDefaults(request.getHeaders(), request.getURI(), request.getMethod());
            return execution.execute(request, body);
        });
        return restTemplate;
    }

    /**
     * 路由规划器：每次确定路由时实时读取 {@link ProxyConfig}，从而支持热重载。
     *
     * <p>注意：连接池中已经建立的 keep-alive 连接仍会沿用旧代理，直到自然过期或被回收；
     * 新建立的连接会立即应用新配置。
     *
     * <p><b>线程级覆盖优先</b>：当前线程存在 {@link OutboundProxyOverride}（计划任务的「任务级单独代理」）时，
     * 无论全局 {@code proxy.enabled} 与否都改走覆盖代理。HttpClient 连接池按路由（含代理）区分连接，不会串用。
     */
    private static final class DynamicProxyRoutePlanner extends DefaultRoutePlanner {

        private final ProxyConfig proxyConfig;

        DynamicProxyRoutePlanner(ProxyConfig proxyConfig) {
            super(null); // null → DefaultSchemePortResolver
            this.proxyConfig = proxyConfig;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
            if (OutboundProxyOverride.isActive()) {
                OutboundProxyEndpoint endpoint = OutboundProxyOverride.current();
                return endpoint == null
                        ? null
                        : new HttpHost("http", endpoint.getHostName(), endpoint.getPort());
            }
            if (!proxyConfig.isEnabled()) {
                return null;
            }
            String host = proxyConfig.getHost();
            int port = proxyConfig.getPort();
            if (host == null || host.isBlank() || port <= 0) {
                return null;
            }
            return new HttpHost("http", host, port);
        }
    }

}
