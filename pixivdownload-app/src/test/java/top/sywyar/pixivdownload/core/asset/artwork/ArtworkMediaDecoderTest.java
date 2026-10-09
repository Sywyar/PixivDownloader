package top.sywyar.pixivdownload.core.asset.artwork;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.zip.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ResourceLock("runtime-paths")
class ArtworkMediaDecoderTest {
    @TempDir Path directory;
    @Test @DisplayName("大 WebP 只读取首帧，保持预览颜色并拒绝超限首帧与容器")
    void previewsLargeWebpWithinFrameBudget() throws Exception {
        String before = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, directory.resolve("data").toString());
        try {
            Path fixture = Path.of(getClass().getResource("/images/gradient-animated.webp").toURI());
            Path large = directory.resolve("large.webp");
            Files.copy(fixture, large);
            long size = 400L * 1024 * 1024;
            try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) {
                long original = file.length();
                file.seek(original);
                file.writeInt(0x4a554e4b);
                file.writeInt(Integer.reverseBytes((int) (size - original - 8)));
                file.setLength(size);
                file.seek(4);
                file.writeInt(Integer.reverseBytes((int) (size - 8)));
            }
            var runner = mock(FfmpegRunner.class);
            var decoder = new ArtworkMediaDecoder(runner, new ObjectMapper());
            for (boolean cover : new boolean[]{false, true}) {
                var expected = cover ? decoder.readCover(fixture, 64) : decoder.read(fixture, 64);
                var actual = cover ? decoder.readCover(large, 64) : decoder.read(large, 64);
                assertEquals(expected.getWidth(), actual.getWidth());
                assertEquals(expected.getHeight(), actual.getHeight());
                assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                        actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
            }
            Path extracted = directory.resolve("first.webp");
            WebpPreviewFrame.copy(large, extracted, 100L * 1024 * 1024);
            long firstFrameBytes = Files.size(extracted);
            assertTrue(firstFrameBytes < Files.size(fixture));
            WebpPreviewFrame.copy(large, extracted, firstFrameBytes);
            assertThrows(java.io.IOException.class, () -> WebpPreviewFrame.copy(large, extracted, firstFrameBytes - 1));
            Thread.currentThread().interrupt();
            try { assertThrows(java.io.InterruptedIOException.class, () -> decoder.read(large, 64)); }
            finally { Thread.interrupted(); }
            try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) {
                file.seek(4);
                file.writeInt(0);
            }
            assertThrows(java.io.IOException.class, () -> decoder.read(large, 64));
            assertEquals(size, Files.size(large));
            try (var files = Files.list(RuntimeFiles.galleryThumbnailDirectory())) { assertEquals(0, files.count()); }
            verifyNoInteractions(runner);
        } finally {
            if (before == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, before);
        }
    }

    @Test @DisplayName("ZIP 只读首帧，不使用归档路径并清理超限临时文件")
    void boundedFirstFrame() throws Exception {
        String before = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, directory.resolve("data").toString());
        try {
            var runner = mock(FfmpegRunner.class);
            var decoder = new ArtworkMediaDecoder(runner, new ObjectMapper());
            Path archive = directory.resolve("animation.zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
                out.putNextEntry(new ZipEntry("../../escape.png"));
                ImageIO.write(new BufferedImage(2, 4, BufferedImage.TYPE_INT_RGB), "png", out);
                out.closeEntry();
            }
            byte[] bytes = Files.readAllBytes(archive);
            assertEquals(2, decoder.read(archive, 512).getWidth());
            assertEquals(4, decoder.read(archive, 512).getHeight());
            assertEquals(2, decoder.readCover(archive, 512).getHeight());
            assertArrayEquals(bytes, Files.readAllBytes(archive));
            try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
                out.putNextEntry(new ZipEntry("frame.png"));
                byte[] buffer = new byte[1024 * 1024];
                for (int i = 0; i < 33; i++) out.write(buffer);
            }
            assertThrows(java.io.IOException.class, () -> decoder.read(archive, 512));
            try (var files = Files.list(RuntimeFiles.galleryThumbnailDirectory())) { assertEquals(0, files.count()); }
            verifyNoInteractions(runner);
        } finally {
            if (before == null) System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, before);
        }
    }

    @Test @DisplayName("缩略图取消后不启动 FFmpeg 兜底")
    void doesNotFallbackAfterCancellation() throws Exception {
        Path source = directory.resolve("source.png");
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        var runner = mock(FfmpegRunner.class);
        var decoder = new ArtworkMediaDecoder(runner, new ObjectMapper());
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> decoder.read(source, 16));
            verifyNoInteractions(runner);
        } finally {
            Thread.interrupted();
        }
    }
}
