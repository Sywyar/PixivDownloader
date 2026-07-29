package top.sywyar.pixivdownload.plugin.api.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("出站 HTTP 稳定契约")
class OutboundHttpContractTest {

    @Test
    @DisplayName("请求与响应必须防御性复制头和原始字节")
    void requestAndResponseDefensivelyCopyMutableInputs() {
        byte[] requestBody = {1, 2};
        List<String> requestValues = new ArrayList<>(List.of("one"));
        Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("X-Test", requestValues);

        OutboundHttpRequest request = new OutboundHttpRequest(
                URI.create("https://example.test/path?signature=%2B"),
                "POST",
                requestHeaders,
                requestBody);

        requestBody[0] = 9;
        requestValues.add("two");
        requestHeaders.put("X-Late", List.of("ignored"));
        byte[] exposedRequestBody = request.body();
        exposedRequestBody[1] = 8;

        assertThat(request.uri().toASCIIString())
                .isEqualTo("https://example.test/path?signature=%2B");
        assertThat(request.headers()).containsOnlyKeys("X-Test");
        assertThat(request.headers().get("X-Test")).containsExactly("one");
        assertThat(request.body()).containsExactly(1, 2);
        assertThatThrownBy(() -> request.headers().put("X", List.of("value")))
                .isInstanceOf(UnsupportedOperationException.class);

        byte[] responseBody = {3, 4};
        List<String> responseValues = new ArrayList<>(List.of("application/octet-stream"));
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put("Content-Type", responseValues);
        OutboundHttpResponse response =
                new OutboundHttpResponse(418, null, responseHeaders, responseBody);

        responseBody[0] = 7;
        responseValues.clear();
        responseHeaders.clear();
        byte[] exposedResponseBody = response.body();
        exposedResponseBody[1] = 6;

        assertThat(response.statusText()).isEmpty();
        assertThat(response.headers().get("Content-Type"))
                .containsExactly("application/octet-stream");
        assertThat(response.body()).containsExactly(3, 4);
    }

    @Test
    @DisplayName("请求响应与代理路由的字符串表示不泄露传输材料")
    void transportValuesHaveRedactedStringRepresentations() {
        String uriSecret = "uri-secret-canary";
        String headerSecret = "header-secret-canary";
        String bodySecret = "body-secret-canary";
        OutboundHttpRequest request = new OutboundHttpRequest(
                URI.create("https://example.test/path?token=" + uriSecret),
                "POST",
                Map.of(
                        "Authorization", List.of("Bearer " + headerSecret),
                        "Cookie", List.of("session=" + headerSecret)),
                bodySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(request.toString())
                .contains("method=POST", "headerCount=2",
                        "bodyLength=" + bodySecret.length())
                .doesNotContain(
                        "example.test", uriSecret, headerSecret, bodySecret,
                        "Authorization", "Cookie");

        String statusSecret = "status-secret-canary";
        String responseBodySecret = "response-body-secret-canary";
        OutboundHttpResponse response = new OutboundHttpResponse(
                401,
                statusSecret,
                Map.of("Set-Cookie", List.of("session=" + headerSecret)),
                responseBodySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(response.toString())
                .contains("statusCode=401", "headerCount=1",
                        "bodyLength=" + responseBodySecret.length())
                .doesNotContain(
                        statusSecret, headerSecret, responseBodySecret, "Set-Cookie");

        AtomicInteger providerToStringCalls = new AtomicInteger();
        OutboundHttpProxyProvider provider = new OutboundHttpProxyProvider() {
            @Override
            public URI resolveProxyUri() {
                return URI.create("http://127.0.0.1:7890");
            }

            @Override
            public String toString() {
                providerToStringCalls.incrementAndGet();
                return "provider-secret-canary";
            }
        };
        OutboundHttpRoute route = OutboundHttpRoute.requiredExplicitProxy(provider);
        OutboundHttpClientProfile profile = OutboundHttpClientProfile.standard(
                Duration.ofSeconds(1), Duration.ofSeconds(2), route);

        assertThat(route.toString())
                .contains("SCOPED_OR_EXPLICIT_REQUIRED", "explicitProxyProviderPresent=true")
                .doesNotContain("provider-secret-canary");
        assertThat(profile.toString()).doesNotContain("provider-secret-canary");
        assertThat(providerToStringCalls).hasValue(0);
    }

    @Test
    @DisplayName("请求与两类响应按大小写不敏感语义合并同名头并保留全部值")
    void requestAndResponsesMergeHeaderCasingsWithoutLosingValues() {
        Map<String, List<String>> requestHeaders = headerCasings();
        OutboundHttpRequest request = new OutboundHttpRequest(
                URI.create("https://example.test/request"),
                "GET",
                requestHeaders,
                new byte[0]);

        assertCaseInsensitiveMergedHeaders(request.headers());

        Map<String, List<String>> responseHeaders = headerCasings();
        OutboundHttpResponse response =
                new OutboundHttpResponse(200, "OK", responseHeaders, new byte[0]);

        assertCaseInsensitiveMergedHeaders(response.headers());

        Map<String, List<String>> streamHeaders = headerCasings();
        OutboundHttpStreamResponse streamResponse = new OutboundHttpStreamResponse(
                200,
                "OK",
                streamHeaders,
                InputStream.nullInputStream());
        try (streamResponse) {
            assertCaseInsensitiveMergedHeaders(streamResponse.headers());

            requestHeaders.get("X-Trace").add("late");
            responseHeaders.clear();
            streamHeaders.get("x-trace").clear();

            assertCaseInsensitiveMergedHeaders(request.headers());
            assertCaseInsensitiveMergedHeaders(response.headers());
            assertCaseInsensitiveMergedHeaders(streamResponse.headers());
        }
    }

    @Test
    @DisplayName("流式响应保持正文惰性并让响应与正文共享幂等关闭")
    void streamingResponseKeepsLiveBodyAndClosesDelegateExactlyOnce() throws IOException {
        AtomicInteger closeCount = new AtomicInteger();
        InputStream delegate = new ByteArrayInputStream(new byte[]{7, 8}) {
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };
        OutboundHttpStreamResponse response = new OutboundHttpStreamResponse(
                206,
                "Partial Content",
                Map.of("Content-Type", List.of("application/octet-stream")),
                delegate);

        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(response.body().read()).isEqualTo(7);
        response.body().close();
        response.close();
        response.close();

        assertThat(closeCount).hasValue(1);
    }

    @Test
    @DisplayName("缓冲便捷方法在完整读取和读取失败时都精确关闭流式响应")
    void bufferedExchangeClosesStreamingResponseOnSuccessAndReadFailure() {
        AtomicInteger successCloseCount = new AtomicInteger();
        OutboundHttpClient successful = streamingClient(new ByteArrayInputStream(new byte[]{3, 4}) {
            @Override
            public void close() throws IOException {
                successCloseCount.incrementAndGet();
                super.close();
            }
        });

        OutboundHttpResponse response = successful.exchange(
                request(URI.create("https://example.test/success"), "GET"));

        assertThat(response.body()).containsExactly(3, 4);
        assertThat(successCloseCount).hasValue(1);

        AtomicInteger failureCloseCount = new AtomicInteger();
        OutboundHttpClient failing = streamingClient(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("short read");
            }

            @Override
            public void close() {
                failureCloseCount.incrementAndGet();
            }
        });

        assertThatThrownBy(() -> failing.exchange(
                request(URI.create("https://example.test/failure"), "GET")))
                .isInstanceOf(OutboundHttpTransportException.class)
                .hasMessageContaining("read")
                .hasRootCauseInstanceOf(IOException.class);
        assertThat(failureCloseCount).hasValue(1);
    }

    @Test
    @DisplayName("profile 必须拒绝无界资源和无效超时")
    void profileRejectsInvalidTimeoutAndPoolLimits() {
        assertThatThrownBy(() -> OutboundHttpClientProfile.standard(
                Duration.ZERO, Duration.ofSeconds(1), OutboundHttpRoute.direct()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeout");
        assertThatThrownBy(() -> new OutboundHttpClientProfile(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                OutboundHttpRoute.direct(),
                OutboundHttpRedirectPolicy.FOLLOW,
                OutboundHttpCookiePolicy.ENABLED,
                2,
                3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConnectionsPerRoute");

        OutboundHttpClientProfile profile = OutboundHttpClientProfile.standard(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                OutboundHttpRoute.inherit());

        assertThat(profile.maxConnections())
                .isEqualTo(OutboundHttpClientProfile.DEFAULT_MAX_CONNECTIONS);
        assertThat(profile.maxConnectionsPerRoute())
                .isEqualTo(OutboundHttpClientProfile.DEFAULT_MAX_CONNECTIONS_PER_ROUTE);
        assertThat(profile.route().policy())
                .isEqualTo(OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_IF_ENABLED);
    }

    @Test
    @DisplayName("显式代理 provider 只能用于 fail-closed 显式路由")
    void explicitProxyProviderHasExactRouteOwnership() {
        OutboundHttpProxyProvider provider = () -> URI.create("http://127.0.0.1:7890");
        OutboundHttpRoute route = OutboundHttpRoute.requiredExplicitProxy(provider);

        assertThat(route.policy())
                .isEqualTo(OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED);
        assertThat(route.explicitProxyProvider().resolveProxyUri())
                .isEqualTo(URI.create("http://127.0.0.1:7890"));
        assertThatThrownBy(() -> new OutboundHttpRoute(
                OutboundHttpRoutePolicy.DIRECT, provider))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboundHttpRoute(
                OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("请求拒绝非 HTTP URI、userinfo 与非法方法")
    void requestRejectsInvalidTargetsAndMethods() {
        assertThatThrownBy(() -> request(URI.create("file:///tmp/data"), "GET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> request(URI.create("https://user@example.test/path"), "GET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user info");
        assertThatThrownBy(() -> request(URI.create("https://example.test/path"), "GET\r\nX: y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP token");
    }

    @Test
    @DisplayName("请求与两类响应都拒绝非法 header token 和 CR LF 注入")
    void requestAndResponsesRejectInvalidHeadersAndLineBreakInjection() {
        URI uri = URI.create("https://example.test/path");

        assertThatThrownBy(() -> new OutboundHttpRequest(
                uri,
                "GET",
                Map.of("X-Test\r\nInjected", List.of("value")),
                new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CR or LF");
        assertThatThrownBy(() -> new OutboundHttpRequest(
                uri,
                "GET",
                Map.of("X-Test", List.of("value\nInjected: yes")),
                new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CR or LF");
        for (String invalidName : List.of(
                "X Test",
                "X:Test",
                "X\tTest",
                "X-测试")) {
            assertThatThrownBy(() -> new OutboundHttpRequest(
                    uri,
                    "GET",
                    Map.of(invalidName, List.of("value")),
                    new byte[0]))
                    .as("非法 header name: %s", invalidName)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTP token");
        }
        assertThatThrownBy(() -> new OutboundHttpResponse(
                200,
                "OK",
                Map.of("X-Test", List.of("value\rInjected: yes")),
                new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CR or LF");
        assertThatThrownBy(() -> new OutboundHttpStreamResponse(
                200,
                "OK",
                Map.of("X-Test\nInjected", List.of("value")),
                InputStream.nullInputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CR or LF");
        assertThatThrownBy(() -> new OutboundHttpResponse(
                200,
                "OK\r\nInjected",
                Map.of(),
                new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statusText");
        assertThatThrownBy(() -> new OutboundHttpStreamResponse(
                200,
                "OK\nInjected",
                Map.of(),
                InputStream.nullInputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statusText");
    }

    private static OutboundHttpClient streamingClient(InputStream body) {
        return new OutboundHttpClient() {
            @Override
            public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
                return new OutboundHttpStreamResponse(
                        200,
                        "OK",
                        Map.of("Content-Type", List.of("application/octet-stream")),
                        body);
            }

            @Override
            public void close() {
            }
        };
    }

    private static OutboundHttpRequest request(URI uri, String method) {
        return new OutboundHttpRequest(uri, method, Map.of(), new byte[0]);
    }

    private static Map<String, List<String>> headerCasings() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Trace", new ArrayList<>(List.of("one")));
        headers.put("x-trace", new ArrayList<>(List.of("two", "three")));
        return headers;
    }

    private static void assertCaseInsensitiveMergedHeaders(
            Map<String, List<String>> headers
    ) {
        assertThat(headers).hasSize(1);
        assertThat(headers).containsKey("x-TrAcE");
        assertThat(headers.get("X-TRACE")).containsExactly("one", "two", "three");
        assertThatThrownBy(() -> headers.get("x-trace").add("forbidden"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
