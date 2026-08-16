package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@DisplayName("PixivAjaxProxyClient 稳定端口")
class PixivAjaxProxyClientTest {

    @Test
    @DisplayName("按原 URI 发送统一 AJAX 请求头并显式按 UTF-8 解码")
    void shouldUseExactUriAjaxHeadersAndUtf8() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42?lang=zh");
        byte[] body = "{\"title\":\"日本語\"}".getBytes(StandardCharsets.UTF_8);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(uri))
                .andExpect(header(HttpHeaders.USER_AGENT, PixivRequestHeaders.USER_AGENT))
                .andExpect(header(HttpHeaders.COOKIE, "PHPSESSID=test"))
                .andExpect(header("X-Requested-With", "XMLHttpRequest"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        PixivAjaxClient client = new PixivAjaxProxyClient(restTemplate);

        assertThat(client.get(uri, "PHPSESSID=test"))
                .isEqualTo("{\"title\":\"日本語\"}");
        server.verify();
    }

    @Test
    @DisplayName("空响应体映射为空字符串")
    void shouldMapNullBodyToEmptyString() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(uri)).andRespond(withSuccess());

        assertThat(new PixivAjaxProxyClient(restTemplate).get(uri, null)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("携带 Cookie 前拒绝非 HTTPS Pixiv JSON 目标")
    void shouldRejectUnsafeTargetsBeforeDispatch() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PixivAjaxProxyClient client = new PixivAjaxProxyClient(restTemplate);

        for (URI uri : List.of(
                URI.create("http://www.pixiv.net/ajax/novel/42"),
                URI.create("https://example.com/ajax/novel/42"),
                URI.create("https://www.pixiv.net/novel/show.php?id=42"),
                URI.create("https://www.pixiv.net/ajax/../account"),
                URI.create("https://www.pixiv.net/ajax/%2e%2e/account"),
                URI.create("https://www.pixiv.net/ajax%2f..%2faccount"),
                URI.create("https://www.pixiv.net/ajax/%5c..%5caccount"))) {
            assertThatThrownBy(() -> client.get(uri, "PHPSESSID=secret"))
                    .isInstanceOfSatisfying(PixivAjaxException.class, failure -> {
                        assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.INVALID_TARGET);
                        assertThat(failure.statusCode()).isZero();
                        assertThat(failure.getMessage()).doesNotContain("secret", uri.toString());
                    });
        }

        server.verify();
    }

    @Test
    @DisplayName("搜索词的普通 UTF-8 路径编码仍可发送")
    void shouldAllowEncodedUtf8PathSegment() {
        URI uri = URI.create("https://www.pixiv.net/ajax/search/novels/%E5%B0%8F%E8%AF%B4?lang=zh");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(uri)).andRespond(withSuccess(
                "{}".getBytes(StandardCharsets.UTF_8),
                MediaType.APPLICATION_JSON));

        assertThat(new PixivAjaxProxyClient(restTemplate).get(uri, null)).isEqualTo("{}");
        server.verify();
    }

    @Test
    @DisplayName("上游 HTTP 失败收敛为不携响应体的稳定异常")
    void shouldTranslateHttpFailureWithoutLeakingResponse() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("sensitive-upstream-body"));

        assertThatThrownBy(() -> new PixivAjaxProxyClient(restTemplate)
                .get(uri, "PHPSESSID=secret"))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.HTTP_STATUS);
                    assertThat(failure.statusCode()).isEqualTo(403);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage())
                            .doesNotContain("sensitive-upstream-body", "PHPSESSID", "secret");
                });
        server.verify();
    }

    @Test
    @DisplayName("自定义错误处理器返回的非 2xx 响应仍归类为 HTTP 失败")
    void shouldClassifyReturnedNonSuccessResponse() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42");
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(uri)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("ignored-body"));

        assertThatThrownBy(() -> new PixivAjaxProxyClient(restTemplate).get(uri, null))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.HTTP_STATUS);
                    assertThat(failure.statusCode()).isEqualTo(503);
                    assertThat(failure.getMessage()).doesNotContain("ignored-body");
                });
        server.verify();
    }

    @Test
    @DisplayName("传输失败收敛为不携客户端实现的稳定类别")
    void shouldTranslateTransportFailure() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(uri)).andRespond(request -> {
            throw new IOException("timeout for sensitive target");
        });

        assertThatThrownBy(() -> new PixivAjaxProxyClient(restTemplate).get(uri, null))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.TRANSPORT);
                    assertThat(failure.statusCode()).isZero();
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage()).doesNotContain("sensitive target");
                });
        server.verify();
    }

    @Test
    @DisplayName("Content-Length 已声明超限时拒绝 Pixiv 小说响应")
    void shouldRejectDeclaredOversizedNovelResponse() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42");
        CountingInputStream body = new CountingInputStream(16);
        RestTemplate boundedRestTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(boundedRestTemplate).build();
        server.expect(requestTo(uri)).andRespond(request -> {
            MockClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
                @Override
                public InputStream getBody() {
                    return body;
                }
            };
            response.getHeaders().setContentLength(PixivAjaxProxyClient.MAX_JSON_RESPONSE_BYTES + 1L);
            return response;
        });

        assertThatThrownBy(() -> new PixivAjaxProxyClient(boundedRestTemplate).get(uri, null))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure ->
                        assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.RESPONSE_TOO_LARGE));
        assertThat(body.bytesRead).isZero();
        assertThat(body.closed).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("Content-Length 缺失时流式读取到系列响应上限后一字节即拒绝")
    void shouldRejectUndeclaredOversizedSeriesResponse() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/series_content/42");
        CountingInputStream body = new CountingInputStream(
                PixivAjaxProxyClient.MAX_SERIES_RESPONSE_BYTES + 1024);
        RestTemplate boundedRestTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(boundedRestTemplate).build();
        server.expect(requestTo(uri)).andRespond(request -> new MockClientHttpResponse(
                new byte[0], HttpStatus.OK) {
            @Override
            public InputStream getBody() {
                return body;
            }
        });

        assertThatThrownBy(() -> new PixivAjaxProxyClient(boundedRestTemplate).get(uri, null))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure ->
                        assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.RESPONSE_TOO_LARGE));
        assertThat(body.bytesRead)
                .isEqualTo(PixivAjaxProxyClient.MAX_SERIES_RESPONSE_BYTES + 1);
        assertThat(body.closed).isTrue();
        server.verify();
    }

    private static final class CountingInputStream extends InputStream {

        private int remaining;
        private int bytesRead;
        private boolean closed;

        private CountingInputStream(int remaining) {
            this.remaining = remaining;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            bytesRead++;
            return 0;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (remaining == 0) {
                return -1;
            }
            int count = Math.min(length, remaining);
            remaining -= count;
            bytesRead += count;
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
