package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Compose 图片分类预览")
class DesktopImageClassifierSupportTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("大图可生成有界预览且 WebP 使用伴随帧")
    void previewsLargeImageAndWebpSidecar() throws Exception {
        Path sidecar = tempDir.resolve("animation_thumb.jpg");
        ImageIO.write(new BufferedImage(6192, 5929, BufferedImage.TYPE_BYTE_GRAY), "jpg", sidecar.toFile());
        DesktopImageClassifierSupport support = new DesktopImageClassifierSupport(null, tempDir.toString());

        for (Path source : new Path[]{sidecar, tempDir.resolve("animation.webp")}) {
            var data = support.materializeImage(source).orElseThrow();
            BufferedImage preview = ImageIO.read(new ByteArrayInputStream(data.bytes()));
            assertEquals(1600, preview.getWidth());
            assertEquals(1532, preview.getHeight());
        }
    }
}
