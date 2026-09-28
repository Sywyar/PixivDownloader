package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 图片分类预览")
class DesktopImageClassifierSupportTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("图像数据隔离可变数组，按内容比较并保留大小和资源格式校验")
    void ownsBoundedImageBytes() {
        int maximumBytes = 5 * 1024 * 1024;
        byte[] input = {1, 2, 3};
        var image = new DesktopUiNode.ImageData(" IMAGE/PNG ", input);
        var equivalent = new DesktopUiNode.ImageData("image/png", input.clone());
        input[0] = 9;
        image.bytes()[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, image.bytes());
        assertEquals(equivalent, image);
        assertEquals(equivalent.hashCode(), image.hashCode());
        assertNotEquals(image, new DesktopUiNode.ImageData("image/png", input));
        assertEquals(image, DesktopUiNode.ImageData.fromBase64("image/png", "AQID"));
        assertThrows(IllegalArgumentException.class, () -> new DesktopUiNode.ImageData("text/plain", input));
        assertThrows(IllegalArgumentException.class, () -> new DesktopUiNode.ImageData("image/png", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new DesktopUiNode.ImageData("image/png",
                new byte[maximumBytes + 1]));
        assertThrows(IllegalArgumentException.class, () -> DesktopUiNode.ImageData.fromBase64("image/png", "!"));
        assertThrows(IllegalArgumentException.class, () -> DesktopUiNode.ImageData.fromBase64("image/png",
                "A".repeat(((maximumBytes + 2) / 3) * 4 + 5)));
    }

    @Test
    @DisplayName("大图可生成有界预览且 WebP 使用伴随帧")
    void previewsLargeImageAndWebpSidecar() throws Exception {
        Path sidecar = tempDir.resolve("animation_thumb.jpg");
        ImageIO.write(new BufferedImage(6192, 5929, BufferedImage.TYPE_BYTE_GRAY), "jpg", sidecar.toFile());
        for (Path source : new Path[]{sidecar, tempDir.resolve("animation.webp")}) {
            var data = DesktopImageClassifierSupport.materializeImage(source, 1600, 1600).orElseThrow();
            BufferedImage preview = ImageIO.read(new ByteArrayInputStream(data.bytes()));
            assertEquals(1600, preview.getWidth());
            assertEquals(1532, preview.getHeight());
            var small = DesktopImageClassifierSupport.materializeImage(source, 160, 150).orElseThrow();
            BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(small.bytes()));
            assertEquals(157, thumbnail.getWidth());
            assertEquals(150, thumbnail.getHeight());
            var viewer = DesktopImageClassifierSupport.materializeImage(source, 980, 700).orElseThrow();
            BufferedImage largePreview = ImageIO.read(new ByteArrayInputStream(viewer.bytes()));
            assertEquals(731, largePreview.getWidth());
            assertEquals(700, largePreview.getHeight());
        }
    }
}
