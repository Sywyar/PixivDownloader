package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.asset.ImageThumbnailScaler;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;

import java.io.File;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ArtworkFileService 单元测试")
class ArtworkFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("大图生成画廊缓存且保留原文件")
    void cachesLargeThumbnailWithoutChangingSource() throws Exception {
        Path source = tempDir.resolve("large.png");
        ImageIO.write(new BufferedImage(6192, 5929, BufferedImage.TYPE_BYTE_GRAY), "png", source.toFile());
        byte[] original = Files.readAllBytes(source);
        var database = mock(PixivDatabase.class);
        var locator = mock(ArtworkFileLocator.class);
        var artwork = mock(ArtworkRecord.class);
        when(database.getArtwork(119L)).thenReturn(artwork);
        when(artwork.count()).thenReturn(1);
        when(locator.resolveImageFile(artwork, 0)).thenReturn(source.toFile());

        try (var runtime = mockStatic(RuntimeFiles.class)) {
            runtime.when(RuntimeFiles::galleryThumbnailDirectory).thenReturn(tempDir.resolve("thumbnails"));
            var service = new ArtworkFileService(database, locator,
                    new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder(
                            mock(top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner.class), new com.fasterxml.jackson.databind.ObjectMapper()));
            assertThat(service.existingThumbnail(119L, 0)).isNull();
            assertThat(Files.exists(tempDir.resolve("thumbnails"))).isFalse();
            var result = service.getThumbnailFile(119L, 0);
            assertThat(service.existingThumbnail(119L, 0)).isEqualTo(result);
            BufferedImage thumbnail = ImageIO.read(result.path().toFile());
            assertThat(thumbnail.getWidth()).isEqualTo(512);
            assertThat(thumbnail.getHeight()).isEqualTo(490);
            assertThat(service.getThumbnailFile(119L, 0)).isEqualTo(result);
            assertThat(Files.getLastModifiedTime(result.path())).isEqualTo(Files.getLastModifiedTime(source));
            var small = service.getThumbnailFile(119L, 0, 150);
            assertThat(ImageIO.read(small.path().toFile()).getWidth()).isEqualTo(256);
            assertThat(service.getThumbnailFile(119L, 0, 200)).isEqualTo(small);
            var large = service.getThumbnailFile(119L, 0, Integer.MAX_VALUE);
            assertThat(ImageIO.read(large.path().toFile()).getWidth()).isEqualTo(1600);
            assertThat(small.path()).isNotEqualTo(large.path());
            Files.setLastModifiedTime(result.path(), java.nio.file.attribute.FileTime.fromMillis(0));
            assertThat(service.existingThumbnail(119L, 0)).isNull();
        }
        assertThat(Files.readAllBytes(source)).containsExactly(original);
    }

    @Test
    @DisplayName("四张透明大图并发生成预览时仍能在有界堆中完成")
    void boundsConcurrentLargePreviewMemory() throws Exception {
        Path source = tempDir.resolve("large-alpha.png");
        BufferedImage original = new BufferedImage(6192, 5929, BufferedImage.TYPE_4BYTE_ABGR);
        ImageIO.write(original, "png", source.toFile());
        original.flush();
        Path log = tempDir.resolve("preview-memory.log");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(javaExecutable.toString(), "-Xmx256m", "-cp",
                System.getProperty("java.class.path"), PreviewMemoryProbe.class.getName(), source.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(log, java.nio.charset.StandardCharsets.UTF_8)).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    public static class PreviewMemoryProbe {
        public static void main(String[] args) throws Exception {
            top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
            var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int index = 0; index < 4; index++) jobs.add(pool.submit(() -> {
                    try {
                        start.await();
                        BufferedImage image = ImageThumbnailScaler.scale(Path.of(args[0]), 1600, 1600);
                        if (image.getWidth() != 1600 || image.getHeight() != 1532
                                || image.getRGB(0, 0) != 0xffffffff) throw new AssertionError("Invalid preview");
                        image.flush();
                    } catch (Exception e) { throw new RuntimeException(e); }
                }));
                start.countDown();
                for (var job : jobs) job.get();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ========== findFileByName ==========

    @Test
    @DisplayName("旧缓存不复用，JPEG 源不重复有损压缩，方形封面与完整预览各自缓存")
    void refreshesLegacyCacheWithoutRecompressingJpeg() throws Exception {
        Path source = tempDir.resolve("source.jpg");
        BufferedImage original = new BufferedImage(80, 240, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 80; x++) original.setRGB(x, y, (x * 3 << 16) | y << 8 | (x + y) % 256);
        }
        ImageIO.write(original, "jpg", source.toFile());
        var database = mock(PixivDatabase.class);
        var locator = mock(ArtworkFileLocator.class);
        var artwork = mock(ArtworkRecord.class);
        when(database.getArtwork(42L)).thenReturn(artwork);
        when(artwork.count()).thenReturn(1);
        when(locator.resolveImageFile(artwork, 0)).thenReturn(source.toFile());
        Path directory = tempDir.resolve("thumbnails");
        Path legacy = directory.resolve("42/p0-512.jpg");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "old thumbnail", java.nio.charset.StandardCharsets.UTF_8);
        try (var runtime = mockStatic(RuntimeFiles.class)) {
            runtime.when(RuntimeFiles::galleryThumbnailDirectory).thenReturn(directory);
            var service = new ArtworkFileService(database, locator,
                    new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder(
                            mock(top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner.class), new com.fasterxml.jackson.databind.ObjectMapper()));
            assertThat(service.existingThumbnail(42L, 0)).isNull();
            var fit = service.getThumbnailFile(42L, 0);
            assertThat(fit.path()).isNotEqualTo(legacy);
            assertThat(fit.extension()).isEqualTo("png");
            BufferedImage decoded = ImageIO.read(source.toFile());
            BufferedImage preview = ImageIO.read(fit.path().toFile());
            assertThat(preview.getRGB(0, 0, 80, 240, null, 0, 80))
                    .containsExactly(decoded.getRGB(0, 0, 80, 240, null, 0, 80));
            var cover = service.getThumbnailFile(42L, 0, 512, true);
            assertThat(cover.path()).isNotEqualTo(fit.path());
            assertThat(ImageIO.read(cover.path().toFile()).getHeight()).isEqualTo(80);
            assertThat(service.getThumbnailFile(42L, 0, 512, true)).isEqualTo(cover);
            assertThat(service.existingThumbnail(42L, 0)).isEqualTo(fit);
        }
        assertThat(Files.readString(legacy, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("old thumbnail");
    }

    @Test
    @DisplayName("WebP 封面读取原媒体首帧，不复用旧 JPEG 伴随图")
    void decodesOriginalWebpInsteadOfLegacyCompanion() throws Exception {
        Path source = tempDir.resolve("animation.webp");
        try (var input = getClass().getResourceAsStream("/images/gradient-animated.webp")) {
            Files.copy(input, source);
        }
        Path companion = tempDir.resolve("animation_thumb.jpg");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg", companion.toFile());
        var database = mock(PixivDatabase.class);
        var locator = mock(ArtworkFileLocator.class);
        var artwork = mock(ArtworkRecord.class);
        when(database.getArtwork(42L)).thenReturn(artwork);
        when(artwork.count()).thenReturn(1);
        when(locator.resolveImageFile(artwork, 0)).thenReturn(source.toFile());
        when(locator.resolveArtworkDirectory(artwork)).thenReturn(tempDir.toString());
        when(locator.resolveStoredFileBaseName(artwork, 0)).thenReturn("animation");
        try (var runtime = mockStatic(RuntimeFiles.class)) {
            runtime.when(RuntimeFiles::galleryThumbnailDirectory).thenReturn(tempDir.resolve("thumbs"));
            var service = new ArtworkFileService(database, locator,
                    new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder(
                            mock(top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner.class), new com.fasterxml.jackson.databind.ObjectMapper()));
            var file = service.getThumbnailFile(42L, 0, 256, true);
            BufferedImage cover = ImageIO.read(file.path().toFile());
            assertThat(cover.getWidth()).isEqualTo(256);
            assertThat(cover.getHeight()).isEqualTo(256);
            assertThat(cover.getRGB(128, 128) & 255).isBetween(115, 140);
        }
    }

    @Test
    @DisplayName("冷缓存生成共用并发预算，命中不排队，中断与失败释放等待资源")
    void boundsColdGenerationWithoutBlockingCacheHits() throws Exception {
        Path source = tempDir.resolve("source.png");
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        var database = mock(PixivDatabase.class);
        var locator = mock(ArtworkFileLocator.class);
        var artwork = mock(ArtworkRecord.class);
        when(database.getArtwork(anyLong())).thenReturn(artwork);
        when(artwork.count()).thenReturn(1);
        when(locator.resolveImageFile(artwork, 0)).thenReturn(source.toFile());
        var runner = mock(top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner.class);
        org.mockito.Mockito.doThrow(new IOException("fixture fallback")).when(runner).run(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var service = new ArtworkFileService(database, locator,
                new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder(runner, new com.fasterxml.jackson.databind.ObjectMapper()));
        Path cache = tempDir.resolve("thumbnails");
        int capacity = ArtworkFileService.MAX_CONCURRENT_THUMBNAILS;
        var started = new CountDownLatch(capacity);
        var release = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var entered = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(capacity + 2);
        try (var runtime = mockStatic(RuntimeFiles.class)) {
            runtime.when(RuntimeFiles::galleryThumbnailDirectory).thenReturn(cache);
            var warm = service.getThumbnailFile(99L, 0);
            var jobs = new ArrayList<Future<?>>();
            for (long id = 0; id < capacity; id++) {
                long artworkId = id;
                jobs.add(pool.submit(() -> {
                    try (var files = mockStatic(RuntimeFiles.class);
                         var decoder = mockStatic(ImageThumbnailScaler.class, CALLS_REAL_METHODS)) {
                        files.when(RuntimeFiles::galleryThumbnailDirectory).thenReturn(cache);
                        decoder.when(() -> ImageThumbnailScaler.scale(source, 512, 512)).thenAnswer(call -> {
                            entered.incrementAndGet();
                            started.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return call.callRealMethod();
                        });
                        return service.getThumbnailFile(artworkId, 0);
                    }
                }));
            }
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            jobs.add(pool.submit(() -> {
                try (var files = mockStatic(RuntimeFiles.class);
                     var decoder = mockStatic(ImageThumbnailScaler.class)) {
                    files.when(RuntimeFiles::galleryThumbnailDirectory).thenReturn(cache);
                    var result = service.getThumbnailFile(0L, 0);
                    decoder.verifyNoInteractions();
                    return result;
                }
            }));
            Future<?> cancelled = pool.submit(() -> {
                try (var files = mockStatic(RuntimeFiles.class)) {
                    files.when(RuntimeFiles::galleryThumbnailDirectory).thenReturn(cache);
                    waiting.countDown();
                    try {
                        service.getThumbnailFile(50L, 0);
                        throw new AssertionError("Cancelled request generated a thumbnail");
                    } catch (InterruptedIOException expected) {
                        assertThat(Thread.currentThread().isInterrupted()).isTrue();
                        interrupted.countDown();
                    }
                }
                return null;
            });
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.getThumbnailFile(99L, 0)).isEqualTo(warm);
            cancelled.cancel(true);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(entered.get()).isEqualTo(capacity);
            release.countDown();
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            try (var decoder = mockStatic(ImageThumbnailScaler.class)) {
                decoder.when(() -> ImageThumbnailScaler.scale(source, 512, 512)).thenThrow(new IOException("fixture"));
                for (long id = 100; id < 100 + capacity; id++) {
                    long artworkId = id;
                    assertThatThrownBy(() -> service.getThumbnailFile(artworkId, 0)).isInstanceOf(IOException.class);
                }
            }
            Future<?> recovered = pool.submit(() -> {
                try (var files = mockStatic(RuntimeFiles.class)) {
                    files.when(RuntimeFiles::galleryThumbnailDirectory).thenReturn(cache);
                    return service.getThumbnailFile(50L, 0);
                }
            });
            assertThat(recovered.get(5, TimeUnit.SECONDS)).isNotNull();
            try (var paths = Files.walk(cache)) {
                assertThat(paths.noneMatch(file -> file.getFileName().toString().startsWith("thumb-"))).isTrue();
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    @DisplayName("findFileByName")
    class FindFileByNameTests {

        @Test
        @DisplayName("目录不存在时应返回 null")
        void shouldReturnNullWhenDirectoryNotExists() {
            File result = ArtworkFileService.findFileByName("/non/existent/path", "test");
            assertThat(result).isNull();
        }
    }
}
