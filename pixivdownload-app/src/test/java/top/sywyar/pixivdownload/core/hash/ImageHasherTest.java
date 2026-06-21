package top.sywyar.pixivdownload.core.hash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ImageHasher 单元测试")
class ImageHasherTest {

    @Test
    @DisplayName("相同图片应生成相同的 dHash 与 aHash")
    void shouldGenerateStableHashesForSameImage() {
        BufferedImage image = horizontalGradient(false);

        OptionalLong firstDHash = ImageHasher.dHash(image);
        OptionalLong secondDHash = ImageHasher.dHash(image);
        OptionalLong firstAHash = ImageHasher.aHash(image);
        OptionalLong secondAHash = ImageHasher.aHash(image);

        assertThat(firstDHash).isPresent();
        assertThat(secondDHash).isEqualTo(firstDHash);
        assertThat(firstAHash).isPresent();
        assertThat(secondAHash).isEqualTo(firstAHash);
    }

    @Test
    @DisplayName("方向相反的灰度梯度应产生最大的 dHash 汉明距离")
    void shouldSeparateOppositeGradientsByDHash() {
        long ascending = ImageHasher.dHash(horizontalGradient(false)).orElseThrow();
        long descending = ImageHasher.dHash(horizontalGradient(true)).orElseThrow();

        assertThat(ImageHasher.hamming(ascending, descending)).isEqualTo(64);
    }

    @Test
    @DisplayName("hash(Path) 应读取可解码图片并忽略不可解码文件")
    void shouldHashImagePathAndIgnoreUndecodableFile() throws Exception {
        Path tempDir = testTempDir();
        Path image = tempDir.resolve("image.png");
        Path text = tempDir.resolve("not-image.txt");
        ImageIO.write(horizontalGradient(false), "png", image.toFile());
        Files.writeString(text, "not an image");

        Optional<ImageHasher.Hashes> hashes = ImageHasher.hash(image);
        Optional<ImageHasher.Hashes> invalid = ImageHasher.hash(text);

        assertThat(hashes).isPresent();
        assertThat(hashes.orElseThrow().aHash()).isNotNull();
        assertThat(invalid).isEmpty();
    }

    private static BufferedImage horizontalGradient(boolean descending) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            for (int x = 0; x < image.getWidth(); x++) {
                int gray = descending
                        ? 255 - x * 255 / (image.getWidth() - 1)
                        : x * 255 / (image.getWidth() - 1);
                graphics.setColor(new Color(gray, gray, gray));
                graphics.drawLine(x, 0, x, image.getHeight() - 1);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static Path testTempDir() throws Exception {
        Path dir = Path.of("target", "test-tmp", "image-hasher-" + System.nanoTime());
        Files.createDirectories(dir);
        return dir;
    }
}
