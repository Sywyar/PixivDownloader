package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;

import java.io.File;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

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
            var service = new ArtworkFileService(database, locator);
            var result = service.getThumbnailFile(119L, 0);
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
        }
        assertThat(Files.readAllBytes(source)).containsExactly(original);
    }

    // ========== findFileByName ==========

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
