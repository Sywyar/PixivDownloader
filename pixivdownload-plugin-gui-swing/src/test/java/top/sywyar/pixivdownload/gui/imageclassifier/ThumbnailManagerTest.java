package top.sywyar.pixivdownload.gui.imageclassifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@DisplayName("Swing 图片分类缩略图")
class ThumbnailManagerTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("小图在较大预览区域中保持原尺寸并正常结束缩放")
    void keepsSmallImageSizeForBothThumbnailEntrypoints() throws Exception {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        Path file = tempDir.resolve("small.png");
        ImageIO.write(source, "png", file.toFile());

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (BufferedImage thumbnail : new BufferedImage[]{
                    ThumbnailManager.getThumbnail(source, 1600, 1600),
                    ThumbnailManager.getThumbnail(file.toFile(), 1600, 1600)}) {
                assertEquals(2, thumbnail.getWidth());
                assertEquals(1, thumbnail.getHeight());
                assertEquals(0xffffffff, thumbnail.getRGB(0, 0));
            }
        });
    }
}
