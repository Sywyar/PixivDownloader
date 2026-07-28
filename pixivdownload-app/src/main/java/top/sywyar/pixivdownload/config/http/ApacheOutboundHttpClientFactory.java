package top.sywyar.pixivdownload.config.http;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpCookiePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRedirectPolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The app-owned Apache HttpClient implementation of the stable outbound HTTP capability.
 */
final class ApacheOutboundHttpClientFactory implements OutboundHttpClientFactory {

    private final ProxyConfig proxyConfig;

    ApacheOutboundHttpClientFactory(ProxyConfig proxyConfig) {
        this.proxyConfig = Objects.requireNonNull(proxyConfig, "proxyConfig");
    }

    @Override
    public OutboundHttpClient open(OutboundHttpClientProfile profile) {
        Objects.requireNonNull(profile, "profile");
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(profile.connectTimeout()))
                .setSocketTimeout(Timeout.of(profile.readTimeout()))
                .build();
        PoolingHttpClientConnectionManager connectionManager =
                new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(profile.maxConnections());
        connectionManager.setDefaultMaxPerRoute(profile.maxConnectionsPerRoute());
        connectionManager.setDefaultConnectionConfig(connectionConfig);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(profile.connectTimeout()))
                .build();
        OutboundHttpProxyResolver proxyResolver = new OutboundHttpProxyResolver(proxyConfig);
        var builder = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setRoutePlanner(new ProfileRoutePlanner(profile.route(), proxyResolver));
        if (profile.redirectPolicy() == OutboundHttpRedirectPolicy.NEVER) {
            builder.disableRedirectHandling();
        }
        if (profile.cookiePolicy() == OutboundHttpCookiePolicy.DISABLED) {
            builder.disableCookieManagement();
        }
        return new ApacheOutboundHttpClient(builder.build());
    }

    private static final class ProfileRoutePlanner extends DefaultRoutePlanner {

        private final OutboundHttpRoute route;
        private final OutboundHttpProxyResolver proxyResolver;

        private ProfileRoutePlanner(
                OutboundHttpRoute route,
                OutboundHttpProxyResolver proxyResolver
        ) {
            super(null);
            this.route = route;
            this.proxyResolver = proxyResolver;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
            try {
                OutboundProxyEndpoint endpoint = proxyResolver.resolve(route);
                return endpoint == null
                        ? null
                        : new HttpHost("http", endpoint.hostName(), endpoint.port());
            } catch (OutboundHttpProxyResolver.OutboundProxyResolutionException e) {
                throw new HttpException(e.getMessage(), e);
            }
        }
    }

    private static final class ApacheOutboundHttpClient implements OutboundHttpClient {

        private final CloseableHttpClient httpClient;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ApacheOutboundHttpClient(CloseableHttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            Objects.requireNonNull(request, "request");
            if (closed.get()) {
                throw new OutboundHttpTransportException("Outbound HTTP client is closed");
            }
            ClassicRequestBuilder builder =
                    ClassicRequestBuilder.create(request.method()).setUri(request.uri());
            request.headers().forEach((name, values) -> {
                if (!isTransportManagedFramingHeader(name)) {
                    values.forEach(value -> builder.addHeader(name, value));
                }
            });
            byte[] requestBody = request.body();
            if (requestBody.length > 0) {
                builder.setEntity(new ByteArrayEntity(requestBody, null));
            }
            ClassicHttpResponse response = null;
            try {
                response = httpClient.executeOpen(null, builder.build(), null);
                Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
                for (Header header : response.getHeaders()) {
                    responseHeaders.computeIfAbsent(
                            header.getName(),
                            ignored -> new ArrayList<>()).add(header.getValue());
                }
                InputStream responseBody = response.getEntity() == null
                        ? InputStream.nullInputStream()
                        : response.getEntity().getContent();
                return new OutboundHttpStreamResponse(
                        response.getCode(),
                        response.getReasonPhrase(),
                        responseHeaders,
                        new ApacheResponseBodyInputStream(responseBody, response));
            } catch (IOException e) {
                closeAfterFailure(response, e);
                throw new OutboundHttpTransportException("Outbound HTTP transport failed", e);
            } catch (RuntimeException | Error e) {
                closeAfterFailure(response, e);
                throw e;
            }
        }

        private static boolean isTransportManagedFramingHeader(String name) {
            return HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)
                    || HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name);
        }

        private static void closeAfterFailure(ClassicHttpResponse response, Throwable failure) {
            if (response == null) {
                return;
            }
            try {
                response.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                httpClient.close();
            } catch (IOException e) {
                throw new OutboundHttpTransportException("Failed to close outbound HTTP client", e);
            }
        }

        private static final class ApacheResponseBodyInputStream extends FilterInputStream {

            private final ClassicHttpResponse response;
            private final AtomicBoolean closed = new AtomicBoolean();

            private ApacheResponseBodyInputStream(
                    InputStream body,
                    ClassicHttpResponse response
            ) {
                super(body);
                this.response = response;
            }

            @Override
            public void close() throws IOException {
                if (closed.compareAndSet(false, true)) {
                    response.close();
                }
            }
        }
    }
}
