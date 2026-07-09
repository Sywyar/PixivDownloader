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

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Content-Length 匹配时写入最终文件并清理 tmp")
    void downloadsWhenContentLengthMatches() throws Exception {
        startServer();
        AtomicReference<String> cookie = new AtomicReference<>();
        server.createContext("/ok.mp4", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            send(exchange, 200, "video-bytes".getBytes(StandardCharsets.UTF_8), -1);
        });
        DouyinMediaDownloader downloader = downloader();

        List<DouyinDownloadedFile> files = downloader.download(List.of(media("/ok.mp4", "video", "mp4")),
                tempDir, () -> false);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.path().getFileName().toString()).isEqualTo("video.mp4");
            assertThat(file.bytes()).isEqualTo(11L);
        });
        assertThat(Files.readString(tempDir.resolve("video.mp4"), StandardCharsets.UTF_8)).isEqualTo("video-bytes");
        assertThat(Files.exists(tempDir.resolve("video.mp4.tmp"))).isFalse();
        assertThat(cookie.get()).isNull();
    }

    @Test
    @DisplayName("Content-Length 不匹配时删除临时文件并返回可分类错误")
    void deletesTempFileWhenContentLengthMismatches() throws Exception {
        startServer();
        serve("/bad.mp4", 200, "short".getBytes(StandardCharsets.UTF_8), 10);
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
        serve("/image.bin", 200, "jpeg".getBytes(StandardCharsets.UTF_8), -1, "image/jpeg");
        DouyinMediaDownloader downloader = downloader();

        List<DouyinDownloadedFile> files = downloader.download(List.of(media("/image.bin", "image", "bin")),
                tempDir, () -> false);

        assertThat(files).singleElement()
                .satisfies(file -> assertThat(file.path().getFileName().toString()).isEqualTo("image.jpg"));
        assertThat(Files.exists(tempDir.resolve("image.bin"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("image.jpg.tmp"))).isFalse();
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
            send(exchange, 200, "ok".getBytes(StandardCharsets.UTF_8), -1);
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

    private DouyinMediaDownloader downloader() {
        return new DouyinMediaDownloader(new RestTemplate(), host -> "127.0.0.1".equals(host));
    }

    private DouyinMedia media(String path, String stem, String extension) {
        return new DouyinMedia("m", DouyinMediaType.VIDEO, URI.create(baseUrl() + path), stem, extension, null, null);
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
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
