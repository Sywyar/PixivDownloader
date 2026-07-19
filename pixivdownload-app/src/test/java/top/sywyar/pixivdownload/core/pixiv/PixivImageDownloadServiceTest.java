package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PixivImageDownloadService 稳定端口")
class PixivImageDownloadServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private RestTemplate restTemplate;

    @Test
    @DisplayName("校验 pximg 来源后使用统一图片请求头流式写入并报告进度")
    void shouldDownloadAllowedImageWithHeadersAndProgress() throws Exception {
        URI source = URI.create("https://i.pximg.net/img-original/example.jpg");
        URI referer = URI.create("https://www.pixiv.net/novel/show.php?id=42");
        Path target = tempDir.resolve("nested/example.jpg");
        byte[] body = "image-bytes".getBytes(StandardCharsets.UTF_8);
        HttpHeaders requestHeaders = new HttpHeaders();
        ClientHttpRequest request = mock(ClientHttpRequest.class);
        when(request.getHeaders()).thenReturn(requestHeaders);
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentLength(body.length);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(response.getBody()).thenReturn(new ByteArrayInputStream(body));
        when(restTemplate.execute(eq(source), eq(HttpMethod.GET),
                any(RequestCallback.class), any(ResponseExtractor.class)))
                .thenAnswer(invocation -> {
                    RequestCallback callback = invocation.getArgument(2);
                    ResponseExtractor<?> extractor = invocation.getArgument(3);
                    callback.doWithRequest(request);
                    return extractor.extractData(response);
                });
        AtomicLong contentLength = new AtomicLong(-1);
        AtomicLong transferred = new AtomicLong(-1);
        PixivImageTransferObserver observer = new PixivImageTransferObserver() {
            @Override
            public void onContentLength(long value) {
                contentLength.set(value);
            }

            @Override
            public void onBytesTransferred(long value) {
                transferred.set(value);
            }
        };

        boolean downloaded = new PixivImageDownloadService(restTemplate)
                .download(source, referer, target, "PHPSESSID=test", observer);

        assertThat(downloaded).isTrue();
        assertThat(Files.readAllBytes(target)).isEqualTo(body);
        assertThat(contentLength).hasValue(body.length);
        assertThat(transferred).hasValue(body.length);
        assertThat(requestHeaders.getFirst(HttpHeaders.USER_AGENT)).isEqualTo(PixivRequestHeaders.USER_AGENT);
        assertThat(requestHeaders.getFirst(HttpHeaders.REFERER)).isEqualTo(referer.toString());
        assertThat(requestHeaders.getFirst(HttpHeaders.COOKIE)).isEqualTo("PHPSESSID=test");
    }

    @Test
    @DisplayName("非 pximg 子域名在发起请求前拒绝")
    void shouldRejectDisallowedImageHost() throws Exception {
        Path target = tempDir.resolve("example.jpg");

        boolean downloaded = new PixivImageDownloadService(restTemplate).download(
                URI.create("https://example.com/example.jpg"),
                URI.create("https://www.pixiv.net/novel/show.php?id=42"),
                target,
                null,
                new PixivImageTransferObserver() {
                });

        assertThat(downloaded).isFalse();
        assertThat(target).doesNotExist();
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("非 HTTPS CDN 或非 Pixiv Referer 在发起请求前拒绝")
    void shouldRejectUnsafeSourceOrReferer() throws Exception {
        PixivImageDownloadService service = new PixivImageDownloadService(restTemplate);
        PixivImageTransferObserver observer = new PixivImageTransferObserver() {
        };

        assertThat(service.download(
                URI.create("http://i.pximg.net/example.jpg"),
                URI.create("https://www.pixiv.net/novel/show.php?id=42"),
                tempDir.resolve("http-source.jpg"),
                null,
                observer)).isFalse();
        assertThat(service.download(
                URI.create("https://i.pximg.net/example.jpg"),
                URI.create("https://example.com/novel/42"),
                tempDir.resolve("foreign-referer.jpg"),
                null,
                observer)).isFalse();

        assertThat(tempDir.resolve("http-source.jpg")).doesNotExist();
        assertThat(tempDir.resolve("foreign-referer.jpg")).doesNotExist();
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("宿主 HTTP 传输失败收敛为不携实现类型的 IO 异常")
    void shouldTranslateTransportFailureToIoException() {
        URI source = URI.create("https://i.pximg.net/example.jpg");
        URI referer = URI.create("https://www.pixiv.net/novel/show.php?id=42");
        when(restTemplate.execute(eq(source), eq(HttpMethod.GET),
                any(RequestCallback.class), any(ResponseExtractor.class)))
                .thenThrow(new ResourceAccessException("timeout for sensitive target"));

        assertThatThrownBy(() -> new PixivImageDownloadService(restTemplate).download(
                source,
                referer,
                tempDir.resolve("failed.jpg"),
                "PHPSESSID=secret",
                new PixivImageTransferObserver() {
                }))
                .isInstanceOfSatisfying(java.io.IOException.class, failure -> {
                    assertThat(failure.getMessage()).isEqualTo("Pixiv image transfer failed");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("HTTP 失败状态保持为未下载且不落盘")
    void shouldReturnFalseForHttpFailure() throws Exception {
        URI source = URI.create("https://i.pximg.net/example.jpg");
        URI referer = URI.create("https://www.pixiv.net/novel/show.php?id=42");
        Path target = tempDir.resolve("not-found.jpg");
        when(restTemplate.execute(eq(source), eq(HttpMethod.GET),
                any(RequestCallback.class), any(ResponseExtractor.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "not found", HttpHeaders.EMPTY, null, null));

        boolean downloaded = new PixivImageDownloadService(restTemplate).download(
                source,
                referer,
                target,
                "PHPSESSID=secret",
                new PixivImageTransferObserver() {
                });

        assertThat(downloaded).isFalse();
        assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("禁重定向客户端返回 302 时保持为未下载且不落盘")
    void shouldReturnFalseForRedirectResponse() throws Exception {
        URI source = URI.create("https://i.pximg.net/example.jpg");
        URI referer = URI.create("https://www.pixiv.net/novel/show.php?id=42");
        Path target = tempDir.resolve("redirected.jpg");
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(HttpStatus.FOUND);
        when(restTemplate.execute(eq(source), eq(HttpMethod.GET),
                any(RequestCallback.class), any(ResponseExtractor.class)))
                .thenAnswer(invocation -> {
                    ResponseExtractor<?> extractor = invocation.getArgument(3);
                    return extractor.extractData(response);
                });

        boolean downloaded = new PixivImageDownloadService(restTemplate).download(
                source,
                referer,
                target,
                "PHPSESSID=secret",
                new PixivImageTransferObserver() {
                });

        assertThat(downloaded).isFalse();
        assertThat(target).doesNotExist();
    }
}
