package top.sywyar.pixivdownload.douyin.client.redirect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Douyin 公共出站 HTTP 重定向适配器")
class OutboundHttpDouyinRedirectClientTest {

    @Test
    @DisplayName("保留状态、响应头、正文与凭据请求头")
    void mapsRedirectResponse() throws Exception {
        FakeHttpClient http = new FakeHttpClient();
        URI request = URI.create("https://v.douyin.com/fixture/");
        URI location = URI.create("/video/7351234567890123456");
        http.enqueue(302, Map.of(
                "Location", List.of(location.toString()),
                "Content-Type", List.of("text/html")), "redirect-body");

        DouyinRedirectResponse response =
                new OutboundHttpDouyinRedirectClient(http).get(request, "sessionid=fixture");

        assertThat(http.lastRequest.uri()).isEqualTo(request);
        assertThat(http.lastRequest.method()).isEqualTo("GET");
        assertThat(http.lastRequest.headers().get("Cookie")).containsExactly("sessionid=fixture");
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.location()).isEqualTo(location);
        assertThat(response.contentType()).isEqualTo("text/html");
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("redirect-body");
    }

    @Test
    @DisplayName("403 与 429 由短链解析器分类")
    void resolverClassifiesErrorStatuses() {
        FakeHttpClient http = new FakeHttpClient();
        http.enqueue(403, Map.of(), "forbidden");
        http.enqueue(429, Map.of(), "limited");
        DefaultDouyinShortLinkResolver resolver = new DefaultDouyinShortLinkResolver(
                new DouyinUrlParser(), new OutboundHttpDouyinRedirectClient(http));

        assertCode(() -> resolver.resolve("https://v.douyin.com/forbidden/", null),
                DouyinClientErrorCode.HTTP_FORBIDDEN);
        assertCode(() -> resolver.resolve("https://v.douyin.com/limited/", null),
                DouyinClientErrorCode.HTTP_RATE_LIMITED);
    }

    private static void assertCode(ThrowingRunnable action, DouyinClientErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(expected);
    }

    private static final class FakeHttpClient implements OutboundHttpClient {
        private final ArrayDeque<QueuedResponse> responses = new ArrayDeque<>();
        private OutboundHttpRequest lastRequest;

        void enqueue(int status, Map<String, List<String>> headers, String body) {
            responses.add(new QueuedResponse(status, headers, body.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            lastRequest = request;
            QueuedResponse response = responses.remove();
            return new OutboundHttpStreamResponse(response.status(), "mock", response.headers(),
                    new ByteArrayInputStream(response.body()));
        }

        @Override public void close() { }

        private record QueuedResponse(int status, Map<String, List<String>> headers, byte[] body) { }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
