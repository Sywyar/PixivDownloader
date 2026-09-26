package top.sywyar.pixivdownload.core.hash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.TimeUnit;

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
        assertThat(hashes.orElseThrow().aHash()).isEqualTo(ImageHasher.aHash(horizontalGradient(false)).orElseThrow());
        assertThat(hashes.orElseThrow().dHash()).isEqualTo(ImageHasher.dHash(horizontalGradient(false)).orElseThrow());
        assertThat(invalid).isEmpty();
    }

    @Test
    @DisplayName("超过旧像素上限的图片仍生成哈希")
    void hashesLargeImage() throws Exception {
        Path image = testTempDir().resolve("large.png");
        BufferedImage original = new BufferedImage(5001, 5000, BufferedImage.TYPE_BYTE_GRAY);
        ImageIO.write(original, "png", image.toFile());

        assertThat(ImageHasher.hash(image)).isPresent();
    }

    @ParameterizedTest
    @CsvSource({"257, 193, 8264785180201684410, 6600080756491151536",
            "1025, 769, -5957018370959910250, 426246640375712890"})
    @DisplayName("预览降采样和分条带合成不改变既有透明图片哈希")
    void preservesExistingTransparentImageHashes(int width, int height, long dHash, long aHash) throws Exception {
        BufferedImage original = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(81723);
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                original.setRGB(x, y, random.nextInt());
            }
        }
        Path image = testTempDir().resolve("hash-compat.png");
        ImageIO.write(original, "png", image.toFile());

        // 固定值来自既有全尺寸解码、白底合成和双线性采样，避免预览策略污染已落库哈希。
        ImageHasher.Hashes hashes = ImageHasher.hash(image).orElseThrow();
        assertThat(hashes.dHash()).isEqualTo(dHash);
        assertThat(hashes.aHash()).isEqualTo(aHash);
    }

    @Test
    @DisplayName("2500 万像素透明图片可在 256 MiB 独立堆内计算哈希")
    void hashesTransparentImageWithinHeapBudget() throws Exception {
        Path directory = testTempDir();
        Path output = directory.resolve("probe.log");
        Path image = directory.resolve("transparent.png");
        ImageIO.write(new BufferedImage(5000, 5000, BufferedImage.TYPE_INT_ARGB), "png", image.toFile());
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx256m", "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                MemoryProbe.class.getName(), image.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class MemoryProbe {
        public static void main(String[] args) throws Exception {
            top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
            ImageHasher.Hashes hashes = ImageHasher.hash(Path.of(args[0])).orElseThrow();
            if (hashes.dHash() != 0L || hashes.aHash() != -1L) {
                throw new AssertionError("Transparent image must hash as an opaque white image");
            }
        }
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
