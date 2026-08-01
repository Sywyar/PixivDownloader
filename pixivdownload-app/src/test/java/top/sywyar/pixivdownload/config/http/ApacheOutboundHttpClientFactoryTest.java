package top.sywyar.pixivdownload.config.http;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpCookiePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRedirectPolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;
import top.sywyar.pixivdownload.plugin.runtime.http.ManagedPluginRestTemplate;
import top.sywyar.pixivdownload.plugin.runtime.http.PluginRestTemplateAdapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("宿主 Apache 出站 HTTP 实现")
class ApacheOutboundHttpClientFactoryTest {

    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<String> rawQuery = new AtomicReference<>();
    private final AtomicReference<String> requestHeader = new AtomicReference<>();
    private final AtomicReference<byte[]> requestBody = new AtomicReference<>();
    private final AtomicReference<List<String>> requestContentLengths =
            new AtomicReference<>(List.of());
    private final AtomicReference<List<String>> requestTransferEncodings =
            new AtomicReference<>(List.of());
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private final CountDownLatch streamFirstChunkSent = new CountDownLatch(1);
    private final CountDownLatch streamMayFinish = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/exchange", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            requestHeader.set(exchange.getRequestHeaders().getFirst("X-Test"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            captureFramingHeaders(exchange);
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("X-Reply", "yes");
            respond(exchange, 418, new byte[]{5, 6});
        });
        server.createContext("/bridge-post", exchange -> {
            requestBody.set(exchange.getRequestBody().readAllBytes());
            captureFramingHeaders(exchange);
            respond(exchange, 200, new byte[]{9});
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirect-target");
            respond(exchange, 302, new byte[0]);
        });
        server.createContext("/redirect-target", exchange -> {
            redirectTargetHits.incrementAndGet();
            respond(exchange, 200, "followed".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/set-cookie", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=abc; Path=/");
            respond(exchange, 200, new byte[0]);
        });
        server.createContext("/echo-cookie", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            respond(exchange, 200, String.valueOf(cookie).getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/stream", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(7);
                exchange.getResponseBody().flush();
                streamFirstChunkSent.countDown();
                if (streamMayFinish.await(5, TimeUnit.SECONDS)) {
                    exchange.getResponseBody().write(8);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("方法、编码 URI、头、原始字节和非 2xx 响应无损往返")
    void exchangesRawRequestAndResponseWithoutStatusReclassification() {
        OutboundHttpClient client = factory().open(profile(
                OutboundHttpRedirectPolicy.NEVER,
                OutboundHttpCookiePolicy.DISABLED));
        URI uri = URI.create(baseUri + "/exchange?signature=%2B%2F%3D");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Test", List.of("value"));
        headers.put("cOnTeNt-LeNgTh", List.of("999"));
        headers.put("tRaNsFeR-EnCoDiNg", List.of("chunked"));

        OutboundHttpResponse response = client.exchange(new OutboundHttpRequest(
                uri,
                "POST",
                headers,
                new byte[]{1, 2, 3}));

        assertThat(rawQuery).hasValue("signature=%2B%2F%3D");
        assertThat(requestHeader).hasValue("value");
        assertThat(requestBody.get()).containsExactly(1, 2, 3);
        assertThat(requestContentLengths.get()).containsExactly("3");
        assertThat(requestTransferEncodings.get()).isEmpty();
        assertThat(response.statusCode()).isEqualTo(418);
        assertThat(response.headers().entrySet())
                .anySatisfy(entry -> {
                    assertThat(entry.getKey()).isEqualToIgnoringCase("X-Reply");
                    assertThat(entry.getValue()).containsExactly("yes");
                });
        assertThat(response.body()).containsExactly(5, 6);

        client.close();
        client.close();
        assertThatThrownBy(() -> client.exchange(new OutboundHttpRequest(
                uri, "GET", Map.of(), new byte[0])))
                .isInstanceOf(OutboundHttpTransportException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("真实 Spring bridge POST 由 Apache 独占生成 framing headers")
    void restTemplateBridgeLetsApacheOwnFramingHeaders() {
        OutboundHttpClientProfile profile = profile(
                OutboundHttpRedirectPolicy.NEVER,
                OutboundHttpCookiePolicy.DISABLED);
        URI uri = URI.create(baseUri + "/bridge-post");

        try (ManagedPluginRestTemplate restTemplate =
                     PluginRestTemplateAdapter.open(factory(), profile)) {
            byte[] ordinaryBody = {1, 2, 3, 4};
            ResponseEntity<byte[]> ordinaryResponse = restTemplate.exchange(
                    uri,
                    HttpMethod.POST,
                    new HttpEntity<>(ordinaryBody),
                    byte[].class);

            assertThat(ordinaryResponse.getStatusCode().value()).isEqualTo(200);
            assertThat(ordinaryResponse.getBody()).containsExactly(9);
            assertThat(requestBody.get()).containsExactly(ordinaryBody);
            assertThat(requestContentLengths.get()).containsExactly("4");
            assertThat(requestTransferEncodings.get()).isEmpty();

            HttpHeaders explicitHeaders = new HttpHeaders();
            explicitHeaders.put("tRaNsFeR-EnCoDiNg", List.of("chunked"));
            byte[] explicitTransferEncodingBody = {5, 6, 7};
            ResponseEntity<byte[]> explicitResponse = restTemplate.exchange(
                    uri,
                    HttpMethod.POST,
                    new HttpEntity<>(explicitTransferEncodingBody, explicitHeaders),
                    byte[].class);

            assertThat(explicitResponse.getStatusCode().value()).isEqualTo(200);
            assertThat(requestBody.get()).containsExactly(explicitTransferEncodingBody);
            assertThat(requestContentLengths.get()).containsExactly("3");
            assertThat(requestTransferEncodings.get()).isEmpty();
        }
    }

    @Test
    @DisplayName("通用 Apache transport 不依赖 Pixiv 头策略或 Spring HTTP 类型")
    void genericTransportDoesNotDependOnPixivHeaderPolicy() {
        var classes = new ClassFileImporter()
                .importPackagesOf(ApacheOutboundHttpClientFactory.class);
        String factoryTypes = "top\\.sywyar\\.pixivdownload\\.config\\.http\\."
                + "ApacheOutboundHttpClientFactory(\\$.*)?";

        noClasses()
                .that().haveNameMatching(factoryTypes)
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "top.sywyar.pixivdownload.common.PixivRequestHeaders")
                .check(classes);
        noClasses()
                .that().haveNameMatching(factoryTypes)
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.http..")
                .check(classes);
    }

    @Test
    @DisplayName("流式调用在正文发送完成前返回并直接暴露实体输入流")
    void streamingExchangeDoesNotAggregateEntityBeforeReturning() throws Exception {
        OutboundHttpClient client = factory().open(profile(
                OutboundHttpRedirectPolicy.NEVER,
                OutboundHttpCookiePolicy.DISABLED));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OutboundHttpStreamResponse response = null;
        try {
            Future<OutboundHttpStreamResponse> future = executor.submit(() ->
                    client.exchangeStream(new OutboundHttpRequest(
                            URI.create(baseUri + "/stream"),
                            "GET",
                            Map.of(),
                            new byte[0])));

            assertThat(streamFirstChunkSent.await(2, TimeUnit.SECONDS)).isTrue();
            response = future.get(1, TimeUnit.SECONDS);
            assertThat(streamMayFinish.getCount()).isEqualTo(1);
            assertThat(response.body().read()).isEqualTo(7);

            streamMayFinish.countDown();
            assertThat(response.body().read()).isEqualTo(8);
            assertThat(response.body().read()).isEqualTo(-1);
            response.close();
            response.close();
        } finally {
            streamMayFinish.countDown();
            if (response != null) {
                response.close();
            }
            client.close();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("redirect 与 cookie 策略由纯 profile 精确控制")
    void appliesRedirectAndCookiePolicies() {
        OutboundHttpClient managed = factory().open(profile(
                OutboundHttpRedirectPolicy.FOLLOW,
                OutboundHttpCookiePolicy.ENABLED));
        OutboundHttpResponse followed = get(managed, "/redirect");
        get(managed, "/set-cookie");
        OutboundHttpResponse echoedCookie = get(managed, "/echo-cookie");
        managed.close();

        assertThat(followed.statusCode()).isEqualTo(200);
        assertThat(new String(followed.body(), StandardCharsets.UTF_8)).isEqualTo("followed");
        assertThat(redirectTargetHits).hasValue(1);
        assertThat(new String(echoedCookie.body(), StandardCharsets.UTF_8))
                .isEqualTo("session=abc");

        OutboundHttpClient isolated = factory().open(profile(
                OutboundHttpRedirectPolicy.NEVER,
                OutboundHttpCookiePolicy.DISABLED));
        OutboundHttpResponse redirect = get(isolated, "/redirect");
        get(isolated, "/set-cookie");
        OutboundHttpResponse noCookie = get(isolated, "/echo-cookie");
        isolated.close();

        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirectTargetHits).hasValue(1);
        assertThat(new String(noCookie.body(), StandardCharsets.UTF_8)).isEqualTo("null");
    }

    @Test
    @DisplayName("Apache 适配器把中性代理端点转换为真实 HTTP 代理路由")
    void convertsNeutralProxyEndpointIntoApacheRoute() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket(0)) {
            ExecutorService proxyExecutor = Executors.newSingleThreadExecutor();
            AtomicReference<String> requestLine = new AtomicReference<>();
            Future<?> proxyTask = proxyExecutor.submit(() ->
                    serveOneProxyRequest(proxyServer, requestLine));
            ProxyConfig proxyConfig = new ProxyConfig();
            proxyConfig.setEnabled(true);
            proxyConfig.setHost("127.0.0.1");
            proxyConfig.setPort(proxyServer.getLocalPort());
            OutboundHttpClient client =
                    new ApacheOutboundHttpClientFactory(proxyConfig)
                            .open(profile(
                                    OutboundHttpRoute.inherit(),
                                    OutboundHttpRedirectPolicy.NEVER,
                                    OutboundHttpCookiePolicy.DISABLED));

            try {
                OutboundHttpResponse response = client.exchange(
                        new OutboundHttpRequest(
                                URI.create("http://transport-target.invalid/proxied?x=1"),
                                "GET",
                                Map.of(),
                                new byte[0]));

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).containsExactly(
                        "ok".getBytes(StandardCharsets.UTF_8));
                proxyTask.get(5, TimeUnit.SECONDS);
                assertThat(requestLine).hasValue(
                        "GET http://transport-target.invalid/proxied?x=1 HTTP/1.1");
            } finally {
                client.close();
                proxyServer.close();
                proxyExecutor.shutdownNow();
                assertThat(proxyExecutor.awaitTermination(5, TimeUnit.SECONDS))
                        .isTrue();
            }
        }
    }

    private ApacheOutboundHttpClientFactory factory() {
        ProxyConfig proxyConfig = new ProxyConfig();
        proxyConfig.setEnabled(false);
        return new ApacheOutboundHttpClientFactory(proxyConfig);
    }

    private static OutboundHttpClientProfile profile(
            OutboundHttpRedirectPolicy redirectPolicy,
            OutboundHttpCookiePolicy cookiePolicy
    ) {
        return profile(
                OutboundHttpRoute.direct(),
                redirectPolicy,
                cookiePolicy);
    }

    private static OutboundHttpClientProfile profile(
            OutboundHttpRoute route,
            OutboundHttpRedirectPolicy redirectPolicy,
            OutboundHttpCookiePolicy cookiePolicy
    ) {
        return new OutboundHttpClientProfile(
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                route,
                redirectPolicy,
                cookiePolicy,
                4,
                2);
    }

    private OutboundHttpResponse get(OutboundHttpClient client, String path) {
        return client.exchange(new OutboundHttpRequest(
                URI.create(baseUri + path),
                "GET",
                Map.of(),
                new byte[0]));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        try {
            exchange.sendResponseHeaders(status, body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private void captureFramingHeaders(HttpExchange exchange) {
        requestContentLengths.set(headerValues(exchange, HttpHeaders.CONTENT_LENGTH));
        requestTransferEncodings.set(headerValues(exchange, HttpHeaders.TRANSFER_ENCODING));
    }

    private static List<String> headerValues(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void serveOneProxyRequest(
            ServerSocket proxyServer,
            AtomicReference<String> requestLine
    ) {
        try (Socket socket = proxyServer.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.ISO_8859_1));
             OutputStream output = socket.getOutputStream()) {
            requestLine.set(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Drain the request headers before replying.
            }
            output.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Length: 2\r\n"
                    + "Connection: close\r\n"
                    + "\r\n"
                    + "ok").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }
}
