package top.sywyar.pixivdownload.core.asset.artwork;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.core.asset.BoundedImageDecoder;
import top.sywyar.pixivdownload.core.asset.ImageThumbnailScaler;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebP 解码内存与颜色准确性")
class WebpDecodingTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"large-lossy, 512, 512, 358", "large-lossy, 1600, 1600, 1120",
            "large-lossy, 0, 4000, 2800", "gradient-animated, 512, 51, 512",
            "gradient-animated, 1600, 160, 1600", "gradient-animated, 0, 500, 5000"})
    @DisplayName("千万像素有损 WebP 在有限堆内解码并符合独立颜色参考")
    void decodesWithinHeapBudget(String fixture, int edge, int width, int height) throws Exception {
        // 合成图由 libwebp 独立解码并按区域平均得到参考，不能以旧读取器的像素作为正确性依据。
        // 动画源为 500×5000 渐变：R=255x/499，G=255y/4999，B=255(x/499+y/4999)/2。
        // libwebp 以 quality=80、method=6 编码，再将同一 VP8 数据封装成两个 100 ms 帧。
        Path image = Path.of(getClass().getResource("/images/" + fixture + ".webp").toURI());
        Path reference = Path.of(getClass().getResource("/images/" + fixture + "-reference.png").toURI());
        Path output = directory.resolve("decode.log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx128m", "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                DecodeProbe.class.getName(), image.toString(), Integer.toString(edge),
                Integer.toString(width), Integer.toString(height), reference.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue())
                    .withFailMessage(Files.readString(output, StandardCharsets.UTF_8)).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    public static final class DecodeProbe {
        public static void main(String[] args) throws Exception {
            Utf8ConsoleStreams.install();
            Path path = Path.of(args[0]);
            int edge = Integer.parseInt(args[1]);
            BufferedImage image = edge == 0 ? BoundedImageDecoder.read(path)
                    : ImageThumbnailScaler.scale(path, edge, edge);
            if (image.getWidth() != Integer.parseInt(args[2]) || image.getHeight() != Integer.parseInt(args[3])) {
                throw new AssertionError("Unexpected decoded dimensions");
            }
            BufferedImage reference = ImageIO.read(Path.of(args[4]).toFile());
            double[] error = new double[3];
            for (int y = 0; y < reference.getHeight(); y++) {
                for (int x = 0; x < reference.getWidth(); x++) {
                    int x0 = x * image.getWidth() / reference.getWidth();
                    int x1 = (x + 1) * image.getWidth() / reference.getWidth();
                    int y0 = y * image.getHeight() / reference.getHeight();
                    int y1 = (y + 1) * image.getHeight() / reference.getHeight();
                    long[] sum = new long[3];
                    for (int iy = y0; iy < y1; iy++) {
                        for (int ix = x0; ix < x1; ix++) {
                            int pixel = image.getRGB(ix, iy);
                            for (int c = 0; c < 3; c++) sum[c] += pixel >>> (c * 8) & 255;
                        }
                    }
                    int expected = reference.getRGB(x, y);
                    for (int c = 0; c < 3; c++) {
                        error[c] += Math.abs(sum[c] / (double) ((x1 - x0) * (y1 - y0))
                                - (expected >>> (c * 8) & 255));
                    }
                }
            }
            for (double channel : error) {
                double mean = channel / (reference.getWidth() * reference.getHeight());
                if (mean > 4) throw new AssertionError("WebP color error: " + mean);
            }
        }
    }
}
