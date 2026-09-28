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
    @Test @DisplayName("ZIP 只读首帧，不使用归档路径并清理超限临时文件")
    void boundedFirstFrame() throws Exception {
        String before = System.getProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, directory.resolve("state").toString());
        try {
            var runner = mock(FfmpegRunner.class);
            var decoder = new ArtworkMediaDecoder(runner, new ObjectMapper());
            Path archive = directory.resolve("animation.zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
                out.putNextEntry(new ZipEntry("../../escape.png"));
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
                out.closeEntry();
            }
            byte[] bytes = Files.readAllBytes(archive);
            assertEquals(2, decoder.read(archive, 512).getWidth());
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
            if (before == null) System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, before);
        }
    }
}
