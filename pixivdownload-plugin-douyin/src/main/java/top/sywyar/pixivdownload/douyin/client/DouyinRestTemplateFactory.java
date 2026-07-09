package top.sywyar.pixivdownload.douyin.client;

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
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinRuntimeSettings;

import java.util.concurrent.TimeUnit;

public final class DouyinRestTemplateFactory {

    private DouyinRestTemplateFactory() {
    }

    public static RestTemplate directDownloadTemplate() {
        return build(30_000, 60_000, null);
    }

    public static RestTemplate forcedProxyDownloadTemplate(ProxyConfig proxyConfig) {
        return build(30_000, 60_000, new ForcedProxyRoutePlanner(proxyConfig));
    }

    public static RestTemplate customProxyDownloadTemplate(DouyinPluginSettingsService settingsService) {
        return build(30_000, 60_000, new CustomProxyRoutePlanner(settingsService));
    }

    private static RestTemplate build(int connectTimeoutMs,
                                      int socketTimeoutMs,
                                      HttpRoutePlanner routePlanner) {
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
        var builder = HttpClients.custom()
                .disableRedirectHandling()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig);
        if (routePlanner != null) {
            builder.setRoutePlanner(routePlanner);
        }
        HttpClient httpClient = builder.build();
        RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
        restTemplate.getInterceptors().add((request, body, execution) -> {
            PixivRequestHeaders.applyBrowserDefaults(request.getHeaders(), request.getURI(), request.getMethod());
            return execution.execute(request, body);
        });
        return restTemplate;
    }

    private static final class ForcedProxyRoutePlanner extends DefaultRoutePlanner {

        private final ProxyConfig proxyConfig;

        private ForcedProxyRoutePlanner(ProxyConfig proxyConfig) {
            super(null);
            this.proxyConfig = proxyConfig;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
            if (proxyConfig == null) {
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

    private static final class CustomProxyRoutePlanner extends DefaultRoutePlanner {

        private final DouyinPluginSettingsService settingsService;

        private CustomProxyRoutePlanner(DouyinPluginSettingsService settingsService) {
            super(null);
            this.settingsService = settingsService;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
            if (settingsService == null) {
                return null;
            }
            DouyinRuntimeSettings settings = settingsService.runtimeSettings();
            if (!settings.hasCustomProxyEndpoint()) {
                return null;
            }
            return OutboundProxyOverride.parse(settings.proxyHost() + ":" + settings.proxyPort());
        }
    }
}
