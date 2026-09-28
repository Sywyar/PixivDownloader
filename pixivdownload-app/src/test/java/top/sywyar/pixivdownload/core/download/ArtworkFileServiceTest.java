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

    // ========== findFileByName ==========

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
        var service = new ArtworkFileService(database, locator);
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
