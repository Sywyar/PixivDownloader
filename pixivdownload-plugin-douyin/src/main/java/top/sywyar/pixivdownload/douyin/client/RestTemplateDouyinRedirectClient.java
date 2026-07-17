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
import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinRuntimeSettings;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

public class RestTemplateDouyinRedirectClient implements DouyinRedirectClient {

    private final RestTemplate restTemplate;

    public RestTemplateDouyinRedirectClient(OutboundProxySettings proxySettings) {
        this(proxySettings, false);
    }

    public RestTemplateDouyinRedirectClient(OutboundProxySettings proxySettings, boolean forceProxy) {
        this.restTemplate = buildNoRedirectRestTemplate(new DouyinProxyRoutePlanner(proxySettings, forceProxy));
    }

    public RestTemplateDouyinRedirectClient(DouyinPluginSettingsService settingsService) {
        this.restTemplate = buildNoRedirectRestTemplate(new CustomProxyRoutePlanner(settingsService));
    }

    @Override
    public DouyinRedirectResponse get(URI uri) throws DouyinClientException {
        return get(uri, null);
    }

    @Override
    public DouyinRedirectResponse get(URI uri, String cookie) throws DouyinClientException {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        DouyinRequestHeaders.applyCredentials(headers, uri, cookie);
        return restTemplate.execute(uri, HttpMethod.GET, request -> {
            request.getHeaders().putAll(headers);
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

        private final OutboundProxySettings proxySettings;
        private final boolean forceProxy;

        private DouyinProxyRoutePlanner(OutboundProxySettings proxySettings, boolean forceProxy) {
            super(null);
            this.proxySettings = proxySettings;
            this.forceProxy = forceProxy;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
            if (OutboundProxyOverride.isActive()) {
                return toHttpHost(OutboundProxyOverride.current());
            }
            if (!forceProxy && (proxySettings == null || !proxySettings.isEnabled())) {
                return null;
            }
            if (proxySettings == null) {
                throw new HttpException("Douyin proxy mode requires a configured proxy");
            }
            String host = proxySettings.getHost();
            int port = proxySettings.getPort();
            if (host == null || host.isBlank() || port <= 0) {
                if (forceProxy) {
                    throw new HttpException("Douyin proxy mode requires a valid proxy endpoint");
                }
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
            if (OutboundProxyOverride.isActive()) {
                return toHttpHost(OutboundProxyOverride.current());
            }
            if (settingsService == null) {
                throw new HttpException("Douyin custom proxy mode requires plugin settings");
            }
            DouyinRuntimeSettings settings = settingsService.runtimeSettings();
            if (!settings.hasCustomProxyEndpoint()) {
                throw new HttpException("Douyin custom proxy mode requires a valid proxy endpoint");
            }
            OutboundProxyEndpoint proxy = OutboundProxyOverride.parse(
                    settings.proxyHost() + ":" + settings.proxyPort());
            if (proxy == null) {
                throw new HttpException("Douyin custom proxy mode requires a valid proxy endpoint");
            }
            return toHttpHost(proxy);
        }
    }

    private static HttpHost toHttpHost(OutboundProxyEndpoint endpoint) {
        return endpoint == null ? null : new HttpHost("http", endpoint.hostName(), endpoint.port());
    }
}
