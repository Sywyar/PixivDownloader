package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.config.RestTemplateConfig;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

@DisplayName("PixivMediaStreamService 媒体出口")
class PixivMediaStreamServiceTest {

    private static final URI SOURCE = URI.create("https://i.pximg.net/img-original/img/1_p0.png");
    private static final URI REFERER = URI.create("https://www.pixiv.net/artworks/1");
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private PixivMediaStreamService service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplateConfig(new ProxyConfig()).pixivMediaRestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new PixivMediaStreamService(restTemplate);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            server.verify();
        } finally {
            ((DisposableBean) restTemplate).destroy();
        }
    }

    @Test
    @DisplayName("使用统一浏览器图片请求头与来源页，不发送 Cookie，并以流交出正文")
    void streamsWithBrowserImageHeadersAndWithoutCookie() throws IOException {
        Body body = new Body(new byte[]{1, 2, 3});
        server.expect(requestTo(SOURCE)).andExpect(method(HttpMethod.GET))
                .andExpect(request -> {
                    HttpHeaders headers = request.getHeaders();
                    assertThat(headers.getFirst(HttpHeaders.USER_AGENT)).isEqualTo(PixivRequestHeaders.USER_AGENT);
                    assertThat(headers.getFirst(HttpHeaders.REFERER)).isEqualTo(REFERER.toString());
                    assertThat(headers.asMultiValueMap())
                            .containsEntry("Sec-Fetch-Dest", List.of("image"))
                            .doesNotContainKeys(HttpHeaders.COOKIE, "X-Requested-With");
                }).andRespond(request -> response(HttpStatus.OK, body, 3));

        try (PixivMediaStream stream = service.open(SOURCE, REFERER, 1024)) {
            assertThat(body.read).isZero();
            assertThat(stream.declaredLength()).isEqualTo(3);
            assertThat(stream.readAllBytes()).containsExactly(1, 2, 3);
        }
        assertThat(body.closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 429, 503})
    @DisplayName("非 200 响应不读取正文、释放连接并保留上游状态")
    void rejectsNonOkWithoutReadingTheBody(int status) {
        Body body = new Body(new byte[64]);
        server.expect(requestTo(SOURCE)).andRespond(request -> {
            var response = response(HttpStatusCode.valueOf(status), body, -1);
            if (status == 429) response.getHeaders().add("Retry-After", "7");
            return response;
        });

        assertThatThrownBy(() -> service.open(SOURCE, REFERER, 1024))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.HTTP_STATUS);
                    assertThat(failure.statusCode()).isEqualTo(status);
                    assertThat(failure.retryAfterMillis()).isEqualTo(status == 429 ? 7_000 : 0);
                });
        assertThat(body.read).isZero();
        assertThat(body.closed).isTrue();
    }

    @Test
    @DisplayName("声明长度超过本次上限时在读取正文前拒绝")
    void rejectsDeclaredLengthAboveTheLimitBeforeReading() {
        Body body = new Body(new byte[16]);
        server.expect(requestTo(SOURCE)).andRespond(request -> response(HttpStatus.OK, body, 16));

        assertThatThrownBy(() -> service.open(SOURCE, REFERER, 15))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure ->
                        assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.RESPONSE_TOO_LARGE));
        assertThat(body.read).isZero();
        assertThat(body.closed).isTrue();
    }

    @Test
    @DisplayName("未声明长度的正文按本次上限计量")
    void boundsUndeclaredBodiesByTheRequestedLimit() throws IOException {
        Body body = new Body(new byte[16]);
        server.expect(requestTo(SOURCE)).andRespond(request -> response(HttpStatus.OK, body, -1));

        try (PixivMediaStream stream = service.open(SOURCE, REFERER, 8)) {
            assertThatThrownBy(stream::readAllBytes).isInstanceOf(IOException.class);
            assertThat(stream.exceededLimit()).isTrue();
        }
    }

    @Test
    @DisplayName("非 CDN 地址或非 Pixiv 来源页在发出请求前拒绝")
    void rejectsUntrustedTargetsBeforeDispatch() {
        for (var target : List.of(
                List.of("http://i.pximg.net/a.png", REFERER.toString()),
                List.of("https://i.pximg.net.example.com/a.png", REFERER.toString()),
                List.of("https://user@i.pximg.net/a.png", REFERER.toString()),
                List.of("https://i.pximg.net:8443/a.png", REFERER.toString()),
                List.of(SOURCE.toString(), "https://example.com/artworks/1"),
                List.of(SOURCE.toString(), "http://www.pixiv.net/artworks/1"))) {
            assertThatThrownBy(() -> service.open(URI.create(target.get(0)), URI.create(target.get(1)), 1024))
                    .isInstanceOfSatisfying(PixivAjaxException.class, failure ->
                            assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.INVALID_TARGET));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> service.open(SOURCE, REFERER, 0));
    }

    @Test
    @DisplayName("连接失败收敛为不携带地址的传输失败")
    void translatesTransportFailures() {
        server.expect(requestTo(SOURCE)).andRespond(withException(new IOException("timeout for " + SOURCE)));

        assertThatThrownBy(() -> service.open(SOURCE, REFERER, 1024))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.TRANSPORT);
                    assertThat(failure.getMessage()).doesNotContain(SOURCE.getHost());
                });
    }

    private static MockClientHttpResponse response(HttpStatusCode status, Body body, long contentLength) {
        var response = new MockClientHttpResponse(new byte[0], status) {
            @Override
            public InputStream getBody() {
                return body;
            }

            @Override
            public void close() {
                body.close();
            }
        };
        if (contentLength >= 0) response.getHeaders().setContentLength(contentLength);
        return response;
    }

    private static final class Body extends InputStream {
        private final byte[] bytes;
        private int read;
        private boolean closed;

        private Body(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            if (read >= bytes.length) return -1;
            return bytes[read++] & 0xff;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
