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
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetchException;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFailure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixivThumbnailFetchService 稳定端口")
class PixivThumbnailFetchServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Test
    @DisplayName("受信 CDN 目标应使用统一图片请求头且绝不发送 Cookie")
    void allowedTargetUsesImageHeadersWithoutCookie() {
        URI source = URI.create("https://i.pximg.net/img-original/example.jpg");
        byte[] body = "thumbnail".getBytes(StandardCharsets.UTF_8);
        when(restTemplate.exchange(
                eq(source), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(body));

        byte[] fetched = new PixivThumbnailFetchService(restTemplate).fetch(source);

        assertThat(fetched).isEqualTo(body);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(source), eq(HttpMethod.GET), entityCaptor.capture(), eq(byte[].class));
        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers).containsExactlyEntriesOf(PixivRequestHeaders.image(null));
        assertThat(headers.getFirst(HttpHeaders.USER_AGENT)).isEqualTo(PixivRequestHeaders.USER_AGENT);
        assertThat(headers.getFirst(HttpHeaders.REFERER)).isEqualTo(PixivRequestHeaders.PIXIV_HOME);
        assertThat(headers)
                .containsEntry("Sec-Fetch-Dest", List.of("image"))
                .containsEntry("Sec-Fetch-Mode", List.of("no-cors"))
                .containsEntry("Sec-Fetch-Site", List.of("cross-site"))
                .doesNotContainKeys(HttpHeaders.COOKIE, HttpHeaders.ORIGIN, "X-Requested-With");
    }

    @Test
    @DisplayName("pximg 子域与 Pixiv 珍藏集封面域均允许")
    void supportedThumbnailHostsAreAllowed() {
        for (URI source : List.of(
                URI.create("https://i.pximg.net/example.jpg"),
                URI.create("https://I.PXIMG.NET/example.jpg"),
                URI.create("https://embed.pixiv.net/decorate.php?illust_id=42")
        )) {
            when(restTemplate.exchange(
                    eq(source), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(new byte[]{1}));

            assertThat(new PixivThumbnailFetchService(restTemplate).fetch(source))
                    .containsExactly(1);
        }
    }

    @Test
    @DisplayName("重定向响应应作为受控 HTTP 失败返回而不投影为成功")
    void redirectResponseIsRejected() {
        URI source = URI.create("https://i.pximg.net/redirect.jpg");
        when(restTemplate.exchange(
                eq(source), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "https://example.org/escaped.jpg")
                        .build());

        assertThatThrownBy(() -> new PixivThumbnailFetchService(restTemplate).fetch(source))
                .isInstanceOfSatisfying(PixivThumbnailFetchException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivThumbnailFailure.HTTP_STATUS);
                    assertThat(failure.statusCode()).isEqualTo(302);
                    assertThat(failure.getMessage()).doesNotContain("example.org");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("非受信目标应在发起请求前拒绝")
    void unsafeTargetsAreRejectedBeforeTransport() {
        PixivThumbnailFetchService service = new PixivThumbnailFetchService(restTemplate);

        for (URI source : List.of(
                URI.create("http://i.pximg.net/example.jpg"),
                URI.create("https://pximg.net/example.jpg"),
                URI.create("https://www.pixiv.net/example.jpg"),
                URI.create("https://user@i.pximg.net/example.jpg"),
                URI.create("https://i.pximg.net:8443/example.jpg"),
                URI.create("https://i.pximg.net/example.jpg#fragment")
        )) {
            assertThatThrownBy(() -> service.fetch(source))
                    .isInstanceOfSatisfying(PixivThumbnailFetchException.class, failure -> {
                        assertThat(failure.failure()).isEqualTo(PixivThumbnailFailure.INVALID_TARGET);
                        assertThat(failure.statusCode()).isZero();
                    });
        }

        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("HTTP 与传输失败应收敛为不携实现细节的稳定类别")
    void transportFailuresAreTranslated() {
        URI notFound = URI.create("https://i.pximg.net/not-found.jpg");
        when(restTemplate.exchange(
                eq(notFound), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "sensitive upstream", HttpHeaders.EMPTY, null, null));

        assertThatThrownBy(() -> new PixivThumbnailFetchService(restTemplate).fetch(notFound))
                .isInstanceOfSatisfying(PixivThumbnailFetchException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivThumbnailFailure.HTTP_STATUS);
                    assertThat(failure.statusCode()).isEqualTo(404);
                    assertThat(failure.getCause()).isNull();
                });

        URI timeout = URI.create("https://i.pximg.net/timeout.jpg");
        when(restTemplate.exchange(
                eq(timeout), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("timeout for sensitive target"));

        assertThatThrownBy(() -> new PixivThumbnailFetchService(restTemplate).fetch(timeout))
                .isInstanceOfSatisfying(PixivThumbnailFetchException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(PixivThumbnailFailure.TRANSPORT);
                    assertThat(failure.statusCode()).isZero();
                    assertThat(failure.getMessage()).doesNotContain("sensitive", "timeout.jpg");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("空响应体应保持为空字节数组")
    void nullResponseBodyBecomesEmptyBytes() {
        URI source = URI.create("https://i.pximg.net/empty.jpg");
        when(restTemplate.exchange(
                eq(source), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        assertThat(new PixivThumbnailFetchService(restTemplate).fetch(source)).isEmpty();
    }
}
