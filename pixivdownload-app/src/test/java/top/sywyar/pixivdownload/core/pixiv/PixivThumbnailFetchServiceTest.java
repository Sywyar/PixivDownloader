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
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetchException;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFailure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("PixivThumbnailFetchService 稳定端口")
class PixivThumbnailFetchServiceTest {

    private static final URI SOURCE = URI.create("https://i.pximg.net/img-original/example.jpg");
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private PixivThumbnailFetchService service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplateConfig(new ProxyConfig()).pixivImageRestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new PixivThumbnailFetchService(restTemplate);
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
    @DisplayName("受信 CDN 目标应使用统一图片请求头且绝不发送 Cookie")
    void allowedTargetUsesImageHeadersWithoutCookie() {
        byte[] body = {1, 2, 3};
        server.expect(requestTo(SOURCE)).andExpect(method(HttpMethod.GET))
                .andExpect(request -> {
                    HttpHeaders headers = request.getHeaders();
                    assertThat(headers.getFirst(HttpHeaders.USER_AGENT)).isEqualTo(PixivRequestHeaders.USER_AGENT);
                    assertThat(headers.getFirst(HttpHeaders.REFERER)).isEqualTo(PixivRequestHeaders.PIXIV_HOME);
                    assertThat(headers)
                            .containsEntry("Sec-Fetch-Dest", List.of("image"))
                            .containsEntry("Sec-Fetch-Mode", List.of("no-cors"))
                            .containsEntry("Sec-Fetch-Site", List.of("cross-site"))
                            .doesNotContainKeys(HttpHeaders.COOKIE, HttpHeaders.ORIGIN, "X-Requested-With");
                    PixivRequestHeaders.image(null).forEach((name, values) ->
                            assertThat(headers.get(name)).isEqualTo(values));
                }).andRespond(withSuccess(body, null));

        assertThat(service.fetch(SOURCE)).isEqualTo(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://i.pximg.net/example.jpg", "https://I.PXIMG.NET/example.jpg",
            "https://embed.pixiv.net/decorate.php?illust_id=42"})
    @DisplayName("pximg 子域与 Pixiv 珍藏集封面域均允许")
    void supportedThumbnailHostsAreAllowed(String source) {
        server.expect(requestTo(source)).andRespond(withSuccess(new byte[]{1}, null));

        assertThat(service.fetch(URI.create(source))).containsExactly(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 404, 500})
    @DisplayName("非成功状态保留分类且不读取或保留错误正文")
    void httpFailureDoesNotReadBody(int status) {
        TrackingBody body = new TrackingBody(1024);
        MockClientHttpResponse response = new MockClientHttpResponse(body, HttpStatusCode.valueOf(status));
        response.getHeaders().set(HttpHeaders.LOCATION, "https://example.org/sensitive");
        server.expect(requestTo(SOURCE)).andRespond(request -> response);

        assertThatThrownBy(() -> service.fetch(SOURCE))
                .isInstanceOfSatisfying(PixivThumbnailFetchException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivThumbnailFailure.HTTP_STATUS);
                    assertThat(failure.statusCode()).isEqualTo(status);
                    assertThat(failure.getMessage()).doesNotContain("example.org", "sensitive");
                    assertThat(failure.getCause()).isNull();
                });
        assertThat(body.readBytes).isZero();
        assertThat(body.closed).isTrue();
    }

    @Test
    @DisplayName("非受信目标应在发起请求前拒绝")
    void unsafeTargetsAreRejectedBeforeTransport() {
        for (URI source : Arrays.asList(null,
                URI.create("/example.jpg"),
                URI.create("http://i.pximg.net/example.jpg"),
                URI.create("https://pximg.net/example.jpg"),
                URI.create("https://www.pixiv.net/example.jpg"),
                URI.create("https://user@i.pximg.net/example.jpg"),
                URI.create("https://i.pximg.net:8443/example.jpg"),
                URI.create("https://i.pximg.net/example.jpg#fragment"))) {
            assertThatThrownBy(() -> service.fetch(source))
                    .isInstanceOfSatisfying(PixivThumbnailFetchException.class, failure -> {
                        assertThat(failure.failure()).isEqualTo(PixivThumbnailFailure.INVALID_TARGET);
                        assertThat(failure.statusCode()).isZero();
                    });
        }
    }

    @Test
    @DisplayName("传输失败应收敛为不携实现细节的稳定类别")
    void transportFailuresAreTranslated() {
        server.expect(requestTo(SOURCE)).andRespond(withException(new IOException("sensitive target")));

        assertTransportFailure();
    }

    @Test
    @DisplayName("读取中断时关闭响应并丢弃不完整内容")
    void readFailureClosesBody() {
        TrackingBody body = new TrackingBody(8) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("sensitive target");
            }
        };
        expectBody(body, -1);

        assertTransportFailure();
        assertThat(body.closed).isTrue();
    }

    @Test
    @DisplayName("空响应体应保持为空字节数组")
    void emptyResponseBodyRemainsEmpty() {
        server.expect(requestTo(SOURCE)).andRespond(withSuccess());

        assertThat(service.fetch(SOURCE)).isEmpty();
    }

    @Test
    @DisplayName("恰好达到图片传输预算时完整返回并关闭响应")
    void exactLimitIsAccepted() {
        TrackingBody body = new TrackingBody(PixivImageTransferObserver.MAX_IMAGE_BYTES);
        expectBody(body, PixivImageTransferObserver.MAX_IMAGE_BYTES);

        byte[] result = service.fetch(SOURCE);

        assertThat(result.length).isEqualTo(PixivImageTransferObserver.MAX_IMAGE_BYTES);
        assertThat(result[0]).isEqualTo((byte) 42);
        assertThat(result[result.length - 1]).isEqualTo((byte) 42);
        assertThat(body.readBytes).isEqualTo(result.length);
        assertThat(body.closed).isTrue();
    }

    @Test
    @DisplayName("声明长度超限时在读取正文前拒绝并关闭响应")
    void excessiveDeclaredLengthIsRejectedBeforeReading() {
        TrackingBody body = new TrackingBody(1);
        expectBody(body, PixivImageTransferObserver.MAX_IMAGE_BYTES + 1);

        assertTransportFailure();
        assertThat(body.readBytes).isZero();
        assertThat(body.closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 1})
    @DisplayName("未知或不实长度仍最多读取预算加一字节并拒绝超限")
    void actualBodyLimitIsEnforced(long declaredLength) {
        TrackingBody body = new TrackingBody(PixivImageTransferObserver.MAX_IMAGE_BYTES + 1024);
        expectBody(body, declaredLength);

        assertTransportFailure();
        assertThat(body.readBytes).isEqualTo(PixivImageTransferObserver.MAX_IMAGE_BYTES + 1);
        assertThat(body.closed).isTrue();
    }

    private void expectBody(TrackingBody body, long declaredLength) {
        MockClientHttpResponse response = new MockClientHttpResponse(body, HttpStatus.OK);
        if (declaredLength >= 0) response.getHeaders().setContentLength(declaredLength);
        server.expect(requestTo(SOURCE)).andRespond(request -> response);
    }

    private void assertTransportFailure() {
        assertThatThrownBy(() -> service.fetch(SOURCE))
                .isInstanceOfSatisfying(PixivThumbnailFetchException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivThumbnailFailure.TRANSPORT);
                    assertThat(failure.statusCode()).isZero();
                    assertThat(failure.getMessage()).doesNotContain("sensitive", "example.jpg");
                    assertThat(failure.getCause()).isNull();
                });
    }

    private static class TrackingBody extends InputStream {
        private final long size;
        private long readBytes;
        private boolean closed;

        private TrackingBody(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            if (readBytes == size) return -1;
            readBytes++;
            return 42;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (readBytes == size) return -1;
            int count = (int) Math.min(length, size - readBytes);
            Arrays.fill(bytes, offset, offset + count, (byte) 42);
            readBytes += count;
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
