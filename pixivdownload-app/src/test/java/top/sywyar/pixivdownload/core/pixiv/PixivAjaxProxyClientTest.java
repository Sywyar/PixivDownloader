package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixivAjaxProxyClient 稳定端口")
class PixivAjaxProxyClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Test
    @DisplayName("按原 URI 发送统一 AJAX 请求头并显式按 UTF-8 解码")
    void shouldUseExactUriAjaxHeadersAndUtf8() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42?lang=zh");
        byte[] body = "{\"title\":\"日本語\"}".getBytes(StandardCharsets.UTF_8);
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<Void>>any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(body));

        PixivAjaxClient client = new PixivAjaxProxyClient(restTemplate);

        assertThat(client.get(uri, "PHPSESSID=test"))
                .isEqualTo("{\"title\":\"日本語\"}");

        ArgumentCaptor<HttpEntity<Void>> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(uri), eq(HttpMethod.GET), entity.capture(), eq(byte[].class));
        HttpHeaders headers = entity.getValue().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.USER_AGENT)).isEqualTo(PixivRequestHeaders.USER_AGENT);
        assertThat(headers.getFirst(HttpHeaders.COOKIE)).isEqualTo("PHPSESSID=test");
        assertThat(headers.getFirst("X-Requested-With")).isEqualTo("XMLHttpRequest");
    }

    @Test
    @DisplayName("空响应体映射为空字符串")
    void shouldMapNullBodyToEmptyString() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<Void>>any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThat(new PixivAjaxProxyClient(restTemplate).get(uri, null)).isEmpty();
    }

    @Test
    @DisplayName("携带 Cookie 前拒绝非 HTTPS Pixiv JSON 目标")
    void shouldRejectUnsafeTargetsBeforeDispatch() {
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

        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("搜索词的普通 UTF-8 路径编码仍可发送")
    void shouldAllowEncodedUtf8PathSegment() {
        URI uri = URI.create("https://www.pixiv.net/ajax/search/novels/%E5%B0%8F%E8%AF%B4?lang=zh");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<Void>>any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok("{}".getBytes(StandardCharsets.UTF_8)));

        assertThat(new PixivAjaxProxyClient(restTemplate).get(uri, null)).isEqualTo("{}");
    }

    @Test
    @DisplayName("上游 HTTP 失败收敛为不携响应体的稳定异常")
    void shouldTranslateHttpFailureWithoutLeakingResponse() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<Void>>any(), eq(byte[].class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN,
                        "forbidden",
                        HttpHeaders.EMPTY,
                        "sensitive-upstream-body".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new PixivAjaxProxyClient(restTemplate)
                .get(uri, "PHPSESSID=secret"))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.HTTP_STATUS);
                    assertThat(failure.statusCode()).isEqualTo(403);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage())
                            .doesNotContain("sensitive-upstream-body", "PHPSESSID", "secret");
                });
    }

    @Test
    @DisplayName("自定义错误处理器返回的非 2xx 响应仍归类为 HTTP 失败")
    void shouldClassifyReturnedNonSuccessResponse() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<Void>>any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("ignored-body".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> new PixivAjaxProxyClient(restTemplate).get(uri, null))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.HTTP_STATUS);
                    assertThat(failure.statusCode()).isEqualTo(503);
                    assertThat(failure.getMessage()).doesNotContain("ignored-body");
                });
    }

    @Test
    @DisplayName("传输失败收敛为不携客户端实现的稳定类别")
    void shouldTranslateTransportFailure() {
        URI uri = URI.create("https://www.pixiv.net/ajax/novel/42");
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<Void>>any(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("timeout for sensitive target"));

        assertThatThrownBy(() -> new PixivAjaxProxyClient(restTemplate).get(uri, null))
                .isInstanceOfSatisfying(PixivAjaxException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.TRANSPORT);
                    assertThat(failure.statusCode()).isZero();
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage()).doesNotContain("sensitive target");
                });
    }
}
