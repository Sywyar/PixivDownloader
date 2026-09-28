package top.sywyar.pixivdownload.core.hash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
    @DisplayName("2500 万像素透明图片可在 128 MiB 独立堆内计算哈希")
    void hashesTransparentImageWithinHeapBudget() throws Exception {
        Path directory = testTempDir();
        Path output = directory.resolve("probe.log");
        Path image = directory.resolve("transparent.png");
        ImageIO.write(new BufferedImage(5000, 5000, BufferedImage.TYPE_INT_ARGB), "png", image.toFile());
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx128m", "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                MemoryProbe.class.getName(), image.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @ParameterizedTest
    @MethodSource("imageTypes")
    @DisplayName("各像素格式保持既有哈希且公开采样入口不修改源图")
    void preservesHashesAndBorrowedPixels(int type) {
        for (int[] size : new int[][]{{1, 1}, {7, 13}, {9, 9}, {257, 193}, {1025, 769}}) {
            assertCompatible(new BufferedImage(size[0], size[1], type));
        }
    }

    static IntStream imageTypes() {
        return IntStream.rangeClosed(BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_BYTE_INDEXED);
    }

    @Test
    @DisplayName("高位深、透明调色板和非 sRGB 图片保持既有哈希")
    void preservesCustomImageHashes() {
        var rgba16 = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),
                true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT);
        assertCompatible(new BufferedImage(rgba16,
                Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, 257, 193, 4, null), false, null));
        var linearRgb = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB),
                false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        assertCompatible(new BufferedImage(linearRgb,
                Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 257, 193, 3, null), false, null));
        var palette = new IndexColorModel(2, 4,
                new byte[]{0, 127, (byte) 255, 50}, new byte[]{50, 0, 127, (byte) 255},
                new byte[]{(byte) 255, 50, 0, 127}, new byte[]{0, 80, (byte) 160, (byte) 255});
        assertCompatible(new BufferedImage(257, 193, BufferedImage.TYPE_BYTE_INDEXED, palette));
    }

    @Test
    @DisplayName("共享父图像素的子图采样不修改父图或透明通道")
    void preservesSubimagePixels() {
        for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_4BYTE_ABGR}) {
            BufferedImage parent = new BufferedImage(300, 200, type);
            BufferedImage child = parent.getSubimage(11, 7, 257, 193);
            fillNoise(child);
            int[] before = parent.getRaster().getPixels(0, 0, 300, 200, (int[]) null);
            ImageHasher.Hashes expected = legacyHashes(child);
            assertThat(ImageHasher.dHash(child)).hasValue(expected.dHash());
            assertThat(ImageHasher.aHash(child)).hasValue(expected.aHash());
            assertArrayEquals(before, parent.getRaster().getPixels(0, 0, 300, 200, (int[]) null));
        }
    }

    private static void assertCompatible(BufferedImage image) {
        fillNoise(image);
        ImageHasher.Hashes expected = legacyHashes(image);
        int[] before = image.getRaster().getPixels(0, 0, image.getWidth(), image.getHeight(), (int[]) null);
        assertThat(ImageHasher.dHash(image)).hasValue(expected.dHash());
        assertThat(ImageHasher.aHash(image)).hasValue(expected.aHash());
        assertArrayEquals(before,
                image.getRaster().getPixels(0, 0, image.getWidth(), image.getHeight(), (int[]) null));
        assertThat(ImageHasher.hashDecodedImage(image)).contains(expected);
    }

    private static void fillNoise(BufferedImage image) {
        Random random = new Random(81723);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRaster().getTransferType() == DataBuffer.TYPE_USHORT) {
                    for (int band = 0; band < image.getRaster().getNumBands(); band++) {
                        int bits = image.getSampleModel().getSampleSize(band);
                        image.getRaster().setSample(x, y, band, random.nextInt(1 << bits));
                    }
                } else {
                    image.setRGB(x, y, random.nextInt());
                }
            }
        }
    }

    /** 已落库哈希的参考流程：整图 RGB 白底合成后，分别双线性缩放到两种灰度尺寸。 */
    private static ImageHasher.Hashes legacyHashes(BufferedImage source) {
        BufferedImage opaque = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D base = opaque.createGraphics();
        try {
            base.setColor(Color.WHITE);
            base.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
            base.drawImage(source, 0, 0, null);
        } finally {
            base.dispose();
        }
        long[] hashes = new long[2];
        for (int index = 0; index < 2; index++) {
            int width = index == 0 ? 9 : 8;
            BufferedImage gray = new BufferedImage(width, 8, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D graphics = gray.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(opaque, 0, 0, width, 8, null);
            } finally {
                graphics.dispose();
            }
            int[] samples = gray.getRaster().getPixels(0, 0, width, 8, (int[]) null);
            double average = java.util.Arrays.stream(samples).average().orElseThrow();
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    boolean set = index == 0 ? samples[y * width + x] > samples[y * width + x + 1]
                            : samples[y * width + x] >= average;
                    hashes[index] = (hashes[index] << 1) | (set ? 1 : 0);
                }
            }
        }
        return new ImageHasher.Hashes(hashes[0], hashes[1]);
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
