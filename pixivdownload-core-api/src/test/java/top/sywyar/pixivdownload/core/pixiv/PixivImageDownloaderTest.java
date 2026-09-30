package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PixivImageDownloaderTest {

    private static final byte[] JPEG_BYTES = {
            (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0, 0, 0, 0, 0, 0, 0
    };
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0
    };

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("同目标并发下载使用独立暂存，失败任务不清理成功任务的文件")
    void concurrentTransfersDoNotShareStaging() throws Exception {
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var paths = java.util.concurrent.ConcurrentHashMap.<Path>newKeySet();
        PixivImageDownloader downloader = (source, referer, target, cookie, observer) -> {
            paths.add(target);
            Files.write(target, PNG_BYTES);
            observer.onContentType("image/png");
            ready.countDown();
            try { if (!ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Transfer did not start"); }
            catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            return !source.getPath().contains("fail");
        };
        try {
            var successful = workers.submit(() -> downloader.downloadImage(URI.create("https://i.pximg.net/image.png"),
                    URI.create("https://www.pixiv.net/"), tempDir.resolve("shared"), null, new PixivImageTransferObserver() {}));
            var failed = workers.submit(() -> downloader.downloadImage(URI.create("https://i.pximg.net/fail.png"),
                    URI.create("https://www.pixiv.net/"), tempDir.resolve("shared"), null, new PixivImageTransferObserver() {}));
            assertThat(successful.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("png");
            assertThat(failed.get(10, java.util.concurrent.TimeUnit.SECONDS)).isNull();
            assertThat(paths).hasSize(2);
            assertThat(tempDir.resolve("shared.png")).hasBinaryContent(PNG_BYTES);
            for (Path path : paths) assertThat(path).doesNotExist();
        } finally { workers.shutdownNow(); }
    }

    @Test
    @DisplayName("查询参数中的点和非法文件名字符不会影响图片扩展名")
    void queryDoesNotAffectImageExtension() throws Exception {
        PixivImageDownloader downloader = downloader(JPEG_BYTES, "image/jpeg");
        Path stem = tempDir.resolve("page");

        String extension = downloader.downloadImage(
                URI.create("https://i.pximg.net/img-original/page.jpg?name=evil.png&bad=%3C%3E%3A%22%7C%3F*"),
                URI.create("https://www.pixiv.net/artworks/1"),
                stem,
                null,
                new PixivImageTransferObserver() {
                });

        assertThat(extension).isEqualTo("jpg");
        assertThat(tempDir.resolve("page.jpg")).hasBinaryContent(JPEG_BYTES);
    }

    @Test
    @DisplayName("响应类型和文件头可以修正无扩展名图片地址")
    void responseTypeAndMagicCanSelectExtension() throws Exception {
        PixivImageDownloader downloader = downloader(PNG_BYTES, "image/png; charset=binary");

        String extension = downloader.downloadImage(
                URI.create("https://i.pximg.net/image?download=cover.jpg"),
                URI.create("https://www.pixiv.net/novel/show.php?id=1"),
                tempDir.resolve("cover"),
                null,
                new PixivImageTransferObserver() {
                });

        assertThat(extension).isEqualTo("png");
        assertThat(tempDir.resolve("cover.png")).hasBinaryContent(PNG_BYTES);
    }

    @Test
    @DisplayName("响应类型与文件头不一致时拒绝替换既有图片")
    void contentTypeMismatchPreservesExistingImage() throws Exception {
        PixivImageDownloader downloader = downloader(JPEG_BYTES, "image/png");
        Path existing = tempDir.resolve("cover.jpg");
        Files.writeString(existing, "existing");

        assertThatThrownBy(() -> downloader.downloadImage(
                URI.create("https://i.pximg.net/cover.jpg"),
                URI.create("https://www.pixiv.net/novel/show.php?id=1"),
                tempDir.resolve("cover"),
                null,
                new PixivImageTransferObserver() {
                }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("content type");

        assertThat(existing).hasContent("existing");
        assertThat(tempDir.resolve("cover.image-download")).doesNotExist();
    }

    @Test
    @DisplayName("非白名单文件头不会作为图片落盘")
    void unsupportedMagicIsRejected() {
        PixivImageDownloader downloader = downloader(
                "<html>error".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        assertThatThrownBy(() -> downloader.downloadImage(
                URI.create("https://i.pximg.net/cover.jpg"),
                URI.create("https://www.pixiv.net/novel/show.php?id=1"),
                tempDir.resolve("cover"),
                null,
                new PixivImageTransferObserver() {
                }))
                .isInstanceOf(IOException.class);

        assertThat(tempDir.resolve("cover.jpg")).doesNotExist();
        assertThat(tempDir.resolve("cover.image-download")).doesNotExist();
    }

    private static PixivImageDownloader downloader(byte[] bytes, String contentType) {
        return (source, referer, target, cookie, observer) -> {
            observer.onContentType(contentType);
            Files.write(target, bytes);
            return true;
        };
    }
}
