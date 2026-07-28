package top.sywyar.pixivdownload.plugin.runtime.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring-Web view of one plugin-owned stable outbound HTTP client.
 */
public final class ManagedPluginRestTemplate extends RestTemplate implements AutoCloseable {

    private final OutboundHttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean();

    ManagedPluginRestTemplate(OutboundHttpClient client) {
        super(new StableClientHttpRequestFactory(client));
        this.client = client;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.close();
        }
    }

    private static final class StableClientHttpRequestFactory implements ClientHttpRequestFactory {

        private final OutboundHttpClient client;

        private StableClientHttpRequestFactory(OutboundHttpClient client) {
            this.client = client;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            return new StableClientHttpRequest(client, uri, httpMethod);
        }
    }

    private static final class StableClientHttpRequest extends AbstractClientHttpRequest {

        private final OutboundHttpClient client;
        private final URI uri;
        private final HttpMethod method;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        private StableClientHttpRequest(OutboundHttpClient client, URI uri, HttpMethod method) {
            this.client = client;
            this.uri = uri;
            this.method = method;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) {
            return body;
        }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
            try {
                OutboundHttpStreamResponse response = client.exchangeStream(new OutboundHttpRequest(
                        uri,
                        method.name(),
                        headers,
                        body.toByteArray()));
                try {
                    return new StableClientHttpResponse(response);
                } catch (RuntimeException | Error e) {
                    closeAfterFailure(response, e);
                    throw e;
                }
            } catch (OutboundHttpTransportException e) {
                throw new IOException("Outbound HTTP transport failed", e);
            }
        }

        private static void closeAfterFailure(
                OutboundHttpStreamResponse response,
                Throwable failure
        ) {
            if (response == null) {
                return;
            }
            try {
                response.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static final class StableClientHttpResponse implements ClientHttpResponse {

        private final OutboundHttpStreamResponse response;
        private final HttpHeaders headers;

        private StableClientHttpResponse(OutboundHttpStreamResponse response) {
            this.response = response;
            this.headers = new HttpHeaders();
            response.headers().forEach(this.headers::addAll);
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatusCode.valueOf(response.statusCode());
        }

        @Override
        public String getStatusText() {
            return response.statusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return response.body();
        }

        @Override
        public void close() {
            response.close();
        }
    }
}
