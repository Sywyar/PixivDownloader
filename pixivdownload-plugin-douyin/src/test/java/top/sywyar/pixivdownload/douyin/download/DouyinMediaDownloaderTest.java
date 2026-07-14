package top.sywyar.pixivdownload.douyin.download;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.client.DouyinRestTemplateFactory;
import top.sywyar.pixivdownload.douyin.model.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DouyinMediaDownloader 抖音媒体下载")
class DouyinMediaDownloaderTest {

    private static final byte[] VIDEO_BYTES = {
            0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o'
    };
    private static final byte[] SHORT_BYTES = {0, 0, 0, 1, 2};
    private static final byte[] JPEG_BYTES = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
    private static final byte[] OK_BYTES = {0, 0};

    @TempDir
    Path tempDir;

    private HttpServer server;
    private HttpServer redirectServer;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (redirectServer != null) {
            redirectServer.stop(0);
        }
    }

    @Test
    @DisplayName("Content-Length 匹配时写入最终文件并清理 tmp")
    void downloadsWhenContentLengthMatches() throws Exception {
        startServer();
        AtomicReference<String> cookie = new AtomicReference<>();
        server.createContext("/ok.mp4", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            send(exchange, 200, VIDEO_BYTES, -1);
        });
        DouyinMediaDownloader downloader = downloader();

        List<DouyinDownloadedFile> files = downloader.download(List.of(media("/ok.mp4", "video", "mp4")),
                tempDir, () -> false);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.path().getFileName().toString()).isEqualTo("video.mp4");
            assertThat(file.bytes()).isEqualTo(11L);
        });
        assertThat(Files.readAllBytes(tempDir.resolve("video.mp4"))).containsExactly(VIDEO_BYTES);
        assertThat(Files.exists(tempDir.resolve("video.mp4.tmp"))).isFalse();
        assertThat(cookie.get()).isNull();
    }

    @Test
    @DisplayName("Content-Length 不匹配时删除临时文件并返回可分类错误")
    void deletesTempFileWhenContentLengthMismatches() throws Exception {
        startServer();
        serve("/bad.mp4", 200, SHORT_BYTES, 10);
        DouyinMediaDownloader downloader = downloader();

        assertThatThrownBy(() -> downloader.download(List.of(media("/bad.mp4", "bad", "mp4")),
                tempDir, () -> false))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.DOWNLOAD_SIZE_MISMATCH);
        assertThat(Files.exists(tempDir.resolve("bad.mp4"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("bad.mp4.tmp"))).isFalse();
    }

    @Test
    @DisplayName("响应 Content-Type 可修正媒体扩展名")
    void correctsExtensionFromContentType() throws Exception {
        startServer();
        serve("/image.bin", 200, JPEG_BYTES, -1, "image/jpeg");
        DouyinMediaDownloader downloader = downloader();

        List<DouyinDownloadedFile> files = downloader.download(List.of(media("/image.bin", "image", "bin")),
                tempDir, () -> false);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.path().getFileName().toString()).isEqualTo("image.jpg");
            assertThat(file.contentType()).isEqualTo("image/jpeg");
        });
        assertThat(Files.exists(tempDir.resolve("image.bin"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("image.jpg.tmp"))).isFalse();
    }

    @Test
    @DisplayName("无媒体类型的验证页不会被写成成功媒体")
    void rejectsVerificationPayloadWithGenericContentType() throws Exception {
        startServer();
        serve("/verify.mp4", 200,
                "<div id=\"verify\">captcha</div>".getBytes(StandardCharsets.UTF_8),
                -1, "application/octet-stream");

        assertThatThrownBy(() -> downloader().download(
                List.of(media("/verify.mp4", "verify", "mp4")), tempDir, () -> false))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE);
        assertThat(Files.exists(tempDir.resolve("verify.mp4"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("verify.mp4.tmp"))).isFalse();
    }

    @Test
    @DisplayName("无 Content-Length 时使用解析到的媒体大小拒绝截断响应")
    void validatesChunkedResponseAgainstResolvedMediaSize() throws Exception {
        startServer();
        server.createContext("/chunked.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(SHORT_BYTES);
            exchange.close();
        });
        DouyinMedia expectedTenBytes = new DouyinMedia(
                "m", DouyinMediaType.VIDEO, URI.create(baseUrl() + "/chunked.mp4"),
                "chunked", "mp4", 10L, "video/mp4");

        assertThatThrownBy(() -> downloader().download(
                List.of(expectedTenBytes), tempDir, () -> false))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.DOWNLOAD_SIZE_MISMATCH);
        assertThat(Files.exists(tempDir.resolve("chunked.mp4"))).isFalse();
    }

    @Test
    @DisplayName("首个 CDN 候选失败时轮换到同一媒体的备用地址")
    void rotatesToFallbackMediaUrl() throws Exception {
        startServer();
        AtomicInteger primaryHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        server.createContext("/primary.mp4", exchange -> {
            primaryHits.incrementAndGet();
            send(exchange, 500, new byte[0], -1);
        });
        server.createContext("/fallback.mp4", exchange -> {
            fallbackHits.incrementAndGet();
            send(exchange, 200, OK_BYTES, -1, "video/mp4");
        });
        DouyinMedia media = new DouyinMedia(
                "m", DouyinMediaType.VIDEO, URI.create(baseUrl() + "/primary.mp4"),
                "fallback", "mp4", 2L, "video/mp4",
                List.of(URI.create(baseUrl() + "/fallback.mp4")));

        List<DouyinDownloadedFile> files = downloader().download(
                List.of(media), tempDir, () -> false);

        assertThat(files).singleElement().satisfies(file -> assertThat(file.bytes()).isEqualTo(2L));
        assertThat(primaryHits).hasValue(1);
        assertThat(fallbackHits).hasValue(1);
    }

    @Test
    @DisplayName("首个 CDN 候选为 404 时仍轮换到同一媒体的备用地址")
    void rotatesToFallbackMediaUrlAfterNotFound() throws Exception {
        startServer();
        AtomicInteger fallbackHits = new AtomicInteger();
        serve("/missing-primary.mp4", 404, new byte[0], -1);
        server.createContext("/available-fallback.mp4", exchange -> {
            fallbackHits.incrementAndGet();
            send(exchange, 200, OK_BYTES, -1, "video/mp4");
        });
        DouyinMedia media = new DouyinMedia(
                "m", DouyinMediaType.VIDEO, URI.create(baseUrl() + "/missing-primary.mp4"),
                "fallback-404", "mp4", 2L, "video/mp4",
                List.of(URI.create(baseUrl() + "/available-fallback.mp4")));

        List<DouyinDownloadedFile> files = downloader().download(
                List.of(media), tempDir, () -> false);

        assertThat(files).singleElement().satisfies(file -> assertThat(file.bytes()).isEqualTo(2L));
        assertThat(fallbackHits).hasValue(1);
    }

    @Test
    @DisplayName("非凭证媒体原点的 401 不会误报账号 Cookie 失效")
    void classifiesNonCredentialMediaUnauthorizedAsUpstreamClientError() throws Exception {
        startServer();
        serve("/cdn-unauthorized.mp4", 401, new byte[0], -1);

        assertThatThrownBy(() -> downloader().download(
                List.of(media("/cdn-unauthorized.mp4", "unauthorized", "mp4")),
                tempDir, () -> false, "sessionid=test"))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.UPSTREAM_CLIENT_ERROR);
    }

    @Test
    @DisplayName("可重试错误后再次请求成功")
    void retriesAndSucceeds() throws Exception {
        startServer();
        AtomicInteger count = new AtomicInteger();
        server.createContext("/retry.mp4", exchange -> {
            if (count.incrementAndGet() == 1) {
                send(exchange, 500, new byte[0], -1);
                return;
            }
            send(exchange, 200, OK_BYTES, -1);
        });
        DouyinMediaDownloader downloader = downloader();

        List<DouyinDownloadedFile> files = downloader.download(List.of(media("/retry.mp4", "retry", "mp4")),
                tempDir, () -> false);

        assertThat(files).singleElement().satisfies(file -> assertThat(file.bytes()).isEqualTo(2L));
        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("取消时不创建媒体文件或临时文件")
    void cancellationLeavesNoFiles() throws Exception {
        startServer();
        DouyinMediaDownloader downloader = downloader();

        assertThatThrownBy(() -> downloader.download(List.of(media("/ok.mp4", "cancelled", "mp4")),
                tempDir, () -> true))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.CANCELLED);
        try (var files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("媒体 URL 非白名单 host 时返回错误并带安全 host")
    void rejectsNonDouyinMediaHostWithHostMessage() {
        DouyinMedia media = new DouyinMedia("m", DouyinMediaType.VIDEO,
                URI.create("https://cdn.example.test/video.mp4"), "video", "mp4", null, null);

        assertThatThrownBy(() -> downloader().download(List.of(media), tempDir, () -> false))
                .isInstanceOf(DouyinClientException.class)
                .hasMessageContaining("host=cdn.example.test")
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.NON_DOUYIN_TARGET);
    }

    @Test
    @DisplayName("生产下载器拒绝 HTTP 媒体 URL")
    void rejectsHttpMediaUrl() {
        DouyinMedia media = new DouyinMedia("m", DouyinMediaType.VIDEO,
                URI.create("http://www.douyin.com/video.mp4"), "video", "mp4", null, null);

        assertThatThrownBy(() -> new DouyinMediaDownloader(new RestTemplate())
                .download(List.of(media), tempDir, () -> false))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.INVALID_URL);
    }

    @Test
    @DisplayName("凭证只在每一跳均通过精确原点策略时携带")
    void forwardsCredentialAcrossAllowedSameOriginRedirects() throws Exception {
        startServer();
        AtomicReference<String> firstCookie = new AtomicReference<>();
        AtomicReference<String> finalCookie = new AtomicReference<>();
        server.createContext("/credential-start", exchange -> {
            firstCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().set("Location", "/credential-final");
            send(exchange, 302, new byte[0], -1);
        });
        server.createContext("/credential-final", exchange -> {
            finalCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            send(exchange, 200, OK_BYTES, -1);
        });
        int credentialPort = server.getAddress().getPort();
        DouyinMediaDownloader downloader = downloader(
                uri -> uri.getPort() == credentialPort && "127.0.0.1".equals(uri.getHost()));

        downloader.download(List.of(media("/credential-start", "credential", "mp4")),
                tempDir, () -> false, "sessionid=test");

        assertThat(firstCookie.get()).isEqualTo("sessionid=test");
        assertThat(finalCookie.get()).isEqualTo("sessionid=test");
    }

    @Test
    @DisplayName("媒体跨原点重定向时重新校验并剥离凭证")
    void stripsCredentialOnCrossOriginRedirect() throws Exception {
        startServer();
        redirectServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirectServer.start();
        AtomicReference<String> firstCookie = new AtomicReference<>();
        AtomicReference<String> redirectedCookie = new AtomicReference<>();
        server.createContext("/cross-start", exchange -> {
            firstCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().set("Location", baseUrl(redirectServer) + "/cross-final");
            send(exchange, 302, new byte[0], -1);
        });
        redirectServer.createContext("/cross-final", exchange -> {
            redirectedCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            send(exchange, 200, OK_BYTES, -1);
        });
        int credentialPort = server.getAddress().getPort();
        DouyinMediaDownloader downloader = downloader(uri -> uri.getPort() == credentialPort);

        downloader.download(List.of(media("/cross-start", "cross", "mp4")),
                tempDir, () -> false, "sessionid=test");

        assertThat(firstCookie.get()).isEqualTo("sessionid=test");
        assertThat(redirectedCookie.get()).isNull();
    }

    private DouyinMediaDownloader downloader() {
        return downloader(uri -> false);
    }

    private DouyinMediaDownloader downloader(java.util.function.Predicate<URI> credentialOriginAllowed) {
        return new DouyinMediaDownloader(DouyinRestTemplateFactory.directDownloadTemplate(),
                host -> "127.0.0.1".equals(host), credentialOriginAllowed);
    }

    private DouyinMedia media(String path, String stem, String extension) {
        return new DouyinMedia("m", DouyinMediaType.VIDEO, URI.create(baseUrl() + path), stem, extension, null, null);
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    private String baseUrl() {
        return baseUrl(server);
    }

    private static String baseUrl(HttpServer target) {
        return "http://127.0.0.1:" + target.getAddress().getPort();
    }

    private void serve(String path, int status, byte[] body, long declaredLength) {
        serve(path, status, body, declaredLength, null);
    }

    private void serve(String path, int status, byte[] body, long declaredLength, String contentType) {
        server.createContext(path, exchange -> send(exchange, status, body, declaredLength, contentType));
    }

    private static void send(HttpExchange exchange, int status, byte[] body, long declaredLength) throws IOException {
        send(exchange, status, body, declaredLength, null);
    }

    private static void send(HttpExchange exchange,
                             int status,
                             byte[] body,
                             long declaredLength,
                             String contentType) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body;
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        if (declaredLength >= 0) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(declaredLength));
            exchange.sendResponseHeaders(status, declaredLength);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
        }
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
