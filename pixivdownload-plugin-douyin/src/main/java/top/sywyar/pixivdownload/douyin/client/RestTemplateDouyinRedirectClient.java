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
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinRuntimeSettings;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

public class RestTemplateDouyinRedirectClient implements DouyinRedirectClient {

    private final RestTemplate restTemplate;

    public RestTemplateDouyinRedirectClient(ProxyConfig proxyConfig) {
        this(proxyConfig, false);
    }

    public RestTemplateDouyinRedirectClient(ProxyConfig proxyConfig, boolean forceProxy) {
        this.restTemplate = buildNoRedirectRestTemplate(new DouyinProxyRoutePlanner(proxyConfig, forceProxy));
    }

    public RestTemplateDouyinRedirectClient(DouyinPluginSettingsService settingsService) {
        this.restTemplate = buildNoRedirectRestTemplate(new CustomProxyRoutePlanner(settingsService));
    }

    @Override
    public DouyinRedirectResponse get(URI uri) {
        return restTemplate.execute(uri, HttpMethod.GET, request -> {
            PixivRequestHeaders.applyBrowserDefaults(request.getHeaders(), uri, HttpMethod.GET);
            DouyinRequestHeaders.applyStandard(request.getHeaders());
        }, RestTemplateDouyinRedirectClient::toResponse);
    }

    private static DouyinRedirectResponse toResponse(ClientHttpResponse response) throws IOException {
        URI location = response.getHeaders().getLocation();
        String contentType = response.getHeaders().getContentType() == null
                ? null
                : response.getHeaders().getContentType().toString();
        byte[] body = response.getBody() == null ? new byte[0] : response.getBody().readAllBytes();
        return new DouyinRedirectResponse(response.getStatusCode().value(), location, contentType, body);
    }

    private static RestTemplate buildNoRedirectRestTemplate(HttpRoutePlanner routePlanner) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(10_000, TimeUnit.MILLISECONDS)
                .setSocketTimeout(10_000, TimeUnit.MILLISECONDS)
                .build();
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(8);
        connectionManager.setDefaultMaxPerRoute(4);
        connectionManager.setDefaultConnectionConfig(connectionConfig);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(10_000, TimeUnit.MILLISECONDS)
                .build();
        HttpClient httpClient = HttpClients.custom()
                .disableRedirectHandling()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setRoutePlanner(routePlanner)
                .build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    private static final class DouyinProxyRoutePlanner extends DefaultRoutePlanner {

        private final ProxyConfig proxyConfig;
        private final boolean forceProxy;

        private DouyinProxyRoutePlanner(ProxyConfig proxyConfig, boolean forceProxy) {
            super(null);
            this.proxyConfig = proxyConfig;
            this.forceProxy = forceProxy;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
            HttpHost override = OutboundProxyOverride.current();
            if (override != null) {
                return override;
            }
            if (proxyConfig == null || (!forceProxy && !proxyConfig.isEnabled())) {
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
            HttpHost override = OutboundProxyOverride.current();
            if (override != null) {
                return override;
            }
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
