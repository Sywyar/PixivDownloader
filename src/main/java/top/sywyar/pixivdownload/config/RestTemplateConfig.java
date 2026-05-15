package top.sywyar.pixivdownload.config;

import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

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
     * 下载专用 RestTemplate，超时更长、连接池更大。
     */
    @Bean("downloadRestTemplate")
    public RestTemplate downloadRestTemplate() {
        return buildRestTemplate(30_000, 60_000);
    }

    private RestTemplate buildRestTemplate(int connectTimeoutMs, int socketTimeoutMs) {
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

        HttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setRoutePlanner(new DynamicProxyRoutePlanner(proxyConfig))
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return new RestTemplate(factory);
    }

    /**
     * 路由规划器：每次确定路由时实时读取 {@link ProxyConfig}，从而支持热重载。
     *
     * <p>注意：连接池中已经建立的 keep-alive 连接仍会沿用旧代理，直到自然过期或被回收；
     * 新建立的连接会立即应用新配置。
     */
    private static final class DynamicProxyRoutePlanner extends DefaultRoutePlanner {

        private final ProxyConfig proxyConfig;

        DynamicProxyRoutePlanner(ProxyConfig proxyConfig) {
            super(null); // null → DefaultSchemePortResolver
            this.proxyConfig = proxyConfig;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
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
