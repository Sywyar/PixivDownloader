package top.sywyar.pixivdownload.plugin.runtime.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("plugin-runtime HTTP Spring bridge")
class PluginRestTemplateAdapterTest {

    @Test
    @DisplayName("URI、方法、头、请求体和原始响应必须无损映射")
    void mapsRequestAndResponseWithoutReEncoding() {
        AtomicInteger responseCloseCount = new AtomicInteger();
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put("Content-Type", List.of("application/octet-stream"));
        responseHeaders.put("X-Reply", List.of("one"));
        responseHeaders.put("x-reply", List.of("two", "three"));
        CapturingClient client = new CapturingClient(streamResponse(
                201,
                "Created",
                responseHeaders,
                new byte[]{5, 6},
                responseCloseCount));
        OutboundHttpClientProfile profile = profile();
        ManagedPluginRestTemplate restTemplate =
                PluginRestTemplateAdapter.open(openedProfile -> {
                    assertThat(openedProfile).isSameAs(profile);
                    return client;
                }, profile);
        HttpHeaders headers = new HttpHeaders();
        headers.put("X-Test", List.of("one", "two"));
        URI uri = URI.create("https://example.test/hook?signature=%2B%2F%3D");

        ResponseEntity<byte[]> response = restTemplate.exchange(
                uri,
                HttpMethod.POST,
                new HttpEntity<>(new byte[]{1, 2, 3}, headers),
                byte[].class);

        assertThat(client.request.uri()).isEqualTo(uri);
        assertThat(client.request.uri().toASCIIString()).contains("signature=%2B%2F%3D");
        assertThat(client.request.method()).isEqualTo("POST");
        assertThat(client.request.headers().get("X-Test")).containsExactly("one", "two");
        assertThat(client.request.body()).containsExactly(1, 2, 3);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().get("X-REPLY"))
                .containsExactly("one", "two", "three");
        assertThat(response.getBody()).containsExactly(5, 6);
        assertThat(responseCloseCount).hasValue(1);
    }

    @Test
    @DisplayName("非 2xx 交给 RestTemplate 默认错误语义并保留正文与 Content-Type")
    void preservesRestTemplateHttpErrorSemantics() {
        AtomicInteger responseCloseCount = new AtomicInteger();
        CapturingClient client = new CapturingClient(streamResponse(
                400,
                "Bad Request",
                Map.of("Content-Type", List.of("application/json;charset=UTF-8")),
                "{\"error\":\"bad\"}".getBytes(StandardCharsets.UTF_8),
                responseCloseCount));
        ManagedPluginRestTemplate restTemplate =
                PluginRestTemplateAdapter.open(ignored -> client, profile());

        assertThatThrownBy(() -> restTemplate.getForEntity(
                URI.create("https://example.test/error"), byte[].class))
                .isInstanceOfSatisfying(HttpClientErrorException.BadRequest.class, error -> {
                    assertThat(error.getResponseBodyAsString(StandardCharsets.UTF_8))
                            .isEqualTo("{\"error\":\"bad\"}");
                    assertThat(error.getResponseHeaders().getContentType().toString())
                            .isEqualTo("application/json;charset=UTF-8");
                });
        assertThat(responseCloseCount).hasValue(1);
    }

    @Test
    @DisplayName("响应头桥接构造失败时立即精确关闭 live response")
    void closesLiveResponseWhenHeaderBridgeConstructionFails() {
        OutboundHttpStreamResponse response = mock(OutboundHttpStreamResponse.class);
        when(response.headers()).thenThrow(new IllegalStateException("invalid headers"));
        ManagedPluginRestTemplate restTemplate =
                PluginRestTemplateAdapter.open(
                        ignored -> new CapturingClient(response),
                        profile());

        assertThatThrownBy(() -> restTemplate.getForEntity(
                URI.create("https://example.test/invalid-headers"), byte[].class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid headers");
        verify(response, times(1)).close();
    }

    @Test
    @DisplayName("响应提取器只读前缀返回时仍精确关闭 live response")
    void closesLiveResponseWhenExtractorReturnsAfterPrefix() {
        AtomicInteger responseCloseCount = new AtomicInteger();
        ManagedPluginRestTemplate restTemplate = PluginRestTemplateAdapter.open(
                ignored -> new CapturingClient(streamResponse(
                        200,
                        "OK",
                        Map.of(),
                        new byte[]{9, 8, 7},
                        responseCloseCount)),
                profile());

        Integer firstByte = restTemplate.execute(
                URI.create("https://example.test/prefix"),
                HttpMethod.GET,
                null,
                response -> response.getBody().read());

        assertThat(firstByte).isEqualTo(9);
        assertThat(responseCloseCount).hasValue(1);
    }

    @Test
    @DisplayName("converter 读取正文 IOException 时仍精确关闭 live response")
    void closesLiveResponseWhenConverterReadFails() {
        AtomicInteger responseCloseCount = new AtomicInteger();
        InputStream failingBody = new InputStream() {
            private boolean prefixReturned;

            @Override
            public int read() throws IOException {
                if (!prefixReturned) {
                    prefixReturned = true;
                    return 1;
                }
                throw new IOException("response body failed");
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (!prefixReturned) {
                    prefixReturned = true;
                    target[offset] = 1;
                    return 1;
                }
                throw new IOException("response body failed");
            }

            @Override
            public void close() {
                responseCloseCount.incrementAndGet();
            }
        };
        ManagedPluginRestTemplate restTemplate = PluginRestTemplateAdapter.open(
                ignored -> new CapturingClient(new OutboundHttpStreamResponse(
                        200,
                        "OK",
                        Map.of(),
                        failingBody)),
                profile());

        assertThatThrownBy(() -> restTemplate.getForEntity(
                URI.create("https://example.test/read-failure"), byte[].class))
                .isInstanceOf(RestClientException.class)
                .hasRootCauseInstanceOf(IOException.class);
        assertThat(responseCloseCount).hasValue(1);
    }

    @Test
    @DisplayName("传输异常映射为 ResourceAccessException")
    void mapsTransportFailureToResourceAccessException() {
        ManagedPluginRestTemplate restTemplate =
                PluginRestTemplateAdapter.open(ignored -> new OutboundHttpClient() {
                    @Override
                    public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
                        throw new OutboundHttpTransportException("network unavailable");
                    }

                    @Override
                    public void close() {
                    }
                }, profile());

        assertThatThrownBy(() -> restTemplate.getForEntity(
                URI.create("https://example.test/unavailable"), byte[].class))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(OutboundHttpTransportException.class);
    }

    @Test
    @DisplayName("managed client 重复关闭时底层只关闭一次")
    void closesUnderlyingClientExactlyOnce() {
        CapturingClient client = new CapturingClient(streamResponse(
                204,
                "No Content",
                Map.of(),
                new byte[0],
                new AtomicInteger()));
        ManagedPluginRestTemplate restTemplate =
                PluginRestTemplateAdapter.open(ignored -> client, profile());

        restTemplate.close();
        restTemplate.close();

        assertThat(client.closeCount).hasValue(1);
    }

    private static OutboundHttpClientProfile profile() {
        return OutboundHttpClientProfile.standard(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                OutboundHttpRoute.direct());
    }

    private static OutboundHttpStreamResponse streamResponse(
            int statusCode,
            String statusText,
            Map<String, List<String>> headers,
            byte[] body,
            AtomicInteger closeCount
    ) {
        return new OutboundHttpStreamResponse(
                statusCode,
                statusText,
                headers,
                new ByteArrayInputStream(body) {
                    @Override
                    public void close() throws IOException {
                        closeCount.incrementAndGet();
                        super.close();
                    }
                });
    }

    private static final class CapturingClient implements OutboundHttpClient {

        private final OutboundHttpStreamResponse response;
        private final AtomicInteger closeCount = new AtomicInteger();
        private OutboundHttpRequest request;

        private CapturingClient(OutboundHttpStreamResponse response) {
            this.response = response;
        }

        @Override
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            this.request = request;
            return response;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
