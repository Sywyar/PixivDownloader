package top.sywyar.pixivdownload.core.asset.artwork;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.core.asset.BoundedImageDecoder;
import top.sywyar.pixivdownload.core.asset.ImageThumbnailScaler;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebP 解码内存与像素兼容性")
class WebpDecodingTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"512, 512, 358, 555d03a1", "1600, 1600, 1120, ac9b8779", "0, 4000, 2800, ec05a72f"})
    @DisplayName("千万像素有损 WebP 在有限堆内解码并保持既有预览和完整像素")
    void decodesWithinHeapBudget(int edge, int width, int height, String checksum) throws Exception {
        // 合成的交叉线和矩形图；固定校验值保护既有完整解码与缩略图采样行为。
        Path image = Path.of(getClass().getResource("/images/large-lossy.webp").toURI());
        Path output = directory.resolve("decode.log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx128m", "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                DecodeProbe.class.getName(), image.toString(), Integer.toString(edge),
                Integer.toString(width), Integer.toString(height), checksum)
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
            CRC32 crc = new CRC32();
            int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, row.length, 1, row, 0, row.length);
                for (int pixel : row) {
                    crc.update(pixel >>> 24);
                    crc.update(pixel >>> 16);
                    crc.update(pixel >>> 8);
                    crc.update(pixel);
                }
            }
            if (crc.getValue() != Long.parseUnsignedLong(args[4], 16)) {
                throw new AssertionError("Decoded pixels changed: " + Long.toHexString(crc.getValue()));
            }
        }
    }
}
