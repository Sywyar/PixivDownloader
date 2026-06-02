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
     * 下载专用 RestTemplate，超时更长、连接池更大。
     */
    @Bean("downloadRestTemplate")
    public RestTemplate downloadRestTemplate() {
        return buildRestTemplate(30_000, 60_000, new DynamicProxyRoutePlanner(proxyConfig));
    }

    /**
     * AI 调用专用 RestTemplate（直连，不走代理）。读超时放宽到 120s：大模型尤其是推理模型可能很慢。
     * {@link top.sywyar.pixivdownload.ai.AiService} 在 {@code ai.use-proxy=false} 时使用本 bean。
     */
    @Bean("aiRestTemplate")
    public RestTemplate aiRestTemplate() {
        return buildRestTemplate(30_000, 120_000, null);
    }

    /**
     * AI 调用专用 RestTemplate（经 {@link ProxyConfig} 的 host:port 出站）。
     * {@link top.sywyar.pixivdownload.ai.AiService} 在 {@code ai.use-proxy=true} 时使用本 bean——AI 是否走代理
     * 由各配置自己的开关决定，独立于全局 {@code proxy.enabled}，因此此处的路由规划器不检查 {@code proxy.enabled}。
     */
    @Bean("aiProxyRestTemplate")
    public RestTemplate aiProxyRestTemplate() {
        return buildRestTemplate(30_000, 120_000, new FixedProxyRoutePlanner(proxyConfig));
    }

    /**
     * 推送通知专用 RestTemplate（直连，不走代理）。短超时即可：推送目标是体量很小的 webhook 调用。
     * {@link top.sywyar.pixivdownload.push.PushHttpSender} 在通道 {@code use-proxy=false} 时使用本 bean。
     */
    @Bean("pushRestTemplate")
    public RestTemplate pushRestTemplate() {
        return buildRestTemplate(10_000, 15_000, null);
    }

    /**
     * 推送通知专用 RestTemplate（经 {@link ProxyConfig} 的 host:port 出站）。是否走代理由各推送通道自身的
     * {@code use-proxy} 决定（如 Telegram 默认开启），与全局 {@code proxy.enabled} 相互独立，故此处不检查它。
     * {@link top.sywyar.pixivdownload.push.PushHttpSender} 在通道 {@code use-proxy=true} 时使用本 bean。
     */
    @Bean("pushProxyRestTemplate")
    public RestTemplate pushProxyRestTemplate() {
        return buildRestTemplate(10_000, 15_000, new FixedProxyRoutePlanner(proxyConfig));
    }

    private RestTemplate buildRestTemplate(int connectTimeoutMs, int socketTimeoutMs) {
        return buildRestTemplate(connectTimeoutMs, socketTimeoutMs, new DynamicProxyRoutePlanner(proxyConfig));
    }

    private RestTemplate buildRestTemplate(int connectTimeoutMs, int socketTimeoutMs, HttpRoutePlanner routePlanner) {
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

    /**
     * 路由规划器：每次确定路由时实时读取 {@link ProxyConfig} 的 host:port，但<b>不检查</b> {@code proxy.enabled}。
     * <p>
     * 专供 {@code aiProxyRestTemplate} 使用——AI 是否走代理由 {@code ai.use-proxy} 决定（由
     * {@link top.sywyar.pixivdownload.ai.AiService} 选择本 bean 来体现），与全局代理开关相互独立。
     * host / port 缺失或非法时回退直连。
     */
    private static final class FixedProxyRoutePlanner extends DefaultRoutePlanner {

        private final ProxyConfig proxyConfig;

        FixedProxyRoutePlanner(ProxyConfig proxyConfig) {
            super(null); // null → DefaultSchemePortResolver
            this.proxyConfig = proxyConfig;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
            String host = proxyConfig.getHost();
            int port = proxyConfig.getPort();
            if (host == null || host.isBlank() || port <= 0) {
                return null;
            }
            return new HttpHost("http", host, port);
        }
    }
}
