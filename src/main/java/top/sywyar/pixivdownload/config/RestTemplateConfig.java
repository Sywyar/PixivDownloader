package top.sywyar.pixivdownload.config;

import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
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

        var clientBuilder = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig);

        if (proxyConfig.isEnabled()) {
            clientBuilder.setProxy(new HttpHost("http", proxyConfig.getHost(), proxyConfig.getPort()));
        }

        HttpClient httpClient = clientBuilder.build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return new RestTemplate(factory);
    }
}
