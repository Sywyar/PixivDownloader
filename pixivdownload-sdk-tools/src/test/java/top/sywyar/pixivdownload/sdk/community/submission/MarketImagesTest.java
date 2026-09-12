package top.sywyar.pixivdownload.sdk.community.submission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("社区图片完整解码与原始字节预算")
class MarketImagesTest {
    @TempDir Path directory;

    @Test
    @DisplayName("PNG JPEG WebP 实际解码且保留原摘要，动画截断和伪格式全部拒绝")
    void verifiesAllEnabledFormats() throws Exception {
        for (String extension : List.of("png", "jpg", "webp")) {
            byte[] data = resource("static." + extension);
            var result = MarketImages.inspect(data, true);
            assertThat(result.width()).isEqualTo(3);
            assertThat(result.height()).isEqualTo(2);
            assertThat(result.size()).isEqualTo(data.length);
            assertThat(result.sha256()).isEqualTo(CommunityJson.sha256(data));
            assertThat(result.mediaType()).isEqualTo(extension.equals("jpg") ? "image/jpeg" : "image/" + extension);
            for (int length : new int[]{0, 1, data.length / 2, data.length - 1}) {
                byte[] truncated = Arrays.copyOf(data, length);
                assertThatThrownBy(() -> MarketImages.inspect(truncated, true)).isInstanceOf(ContractException.class);
            }
            assertThatThrownBy(() -> MarketImages.inspect(Arrays.copyOf(data, data.length + 1), true))
                    .isInstanceOf(ContractException.class);
        }
        for (String file : List.of("animated.png", "animated.webp")) {
            assertThatThrownBy(() -> MarketImages.inspect(resource(file), true)).isInstanceOf(ContractException.class);
        }
        assertThatThrownBy(() -> MarketImages.inspect("<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8), true))
                .isInstanceOf(ContractException.class);
        byte[] corrupt = resource("static.png");
        corrupt[corrupt.length - 5] ^= 1;
        assertThatThrownBy(() -> MarketImages.inspect(corrupt, true)).isInstanceOf(ContractException.class);
        byte[] original = resource("static.png");
        int position = 8;
        while (!new String(original, position + 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("IDAT")) {
            position += ByteBuffer.wrap(original, position, 4).getInt() + 12;
        }
        int length = ByteBuffer.wrap(original, position, 4).getInt();
        byte[] missingZlibTrailer = new byte[original.length - 4];
        System.arraycopy(original, 0, missingZlibTrailer, 0, position + 8 + length - 4);
        System.arraycopy(original, position + 8 + length, missingZlibTrailer, position + 8 + length - 4,
                original.length - position - 8 - length);
        ByteBuffer.wrap(missingZlibTrailer, position, 4).putInt(length - 4);
        CRC32 crc = new CRC32();
        crc.update(missingZlibTrailer, position + 4, length);
        ByteBuffer.wrap(missingZlibTrailer, position + length + 4, 4).putInt((int) crc.getValue());
        assertThatThrownBy(() -> MarketImages.inspect(missingZlibTrailer, true)).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("真实图片达到字节和像素上限可用，上限加一在分配前拒绝")
    void boundsBytesAndDimensions() throws Exception {
        for (boolean icon : new boolean[]{true, false}) {
            int maximum = icon ? MarketImages.ICON_BYTES : MarketImages.SCREENSHOT_BYTES;
            byte[] exact = paddedPng(maximum);
            assertThat(MarketImages.inspect(exact, icon).size()).isEqualTo(maximum);
            assertLimit(() -> MarketImages.inspect(Arrays.copyOf(exact, maximum + 1), icon));
            var stream = new ByteArrayInputStream(new byte[maximum * 2]);
            assertLimit(() -> MarketImages.read(stream, icon));
            assertThat(stream.available()).isEqualTo(maximum - 1);
            int dimension = icon ? MarketImages.ICON_DIMENSION : MarketImages.SCREENSHOT_DIMENSION;
            byte[] image = png(dimension, dimension);
            assertThat(MarketImages.inspect(image, icon).width()).isEqualTo(dimension);
            ByteBuffer.wrap(image, 16, 4).putInt(dimension + 1);
            CRC32 crc = new CRC32();
            crc.update(image, 12, 17);
            ByteBuffer.wrap(image, 29, 4).putInt((int) crc.getValue());
            assertLimit(() -> MarketImages.inspect(image, icon));
        }
    }

    @Test
    @DisplayName("累计图片预算与内容寻址的账号插件版本目录同时核对")
    void bindsPathsAndTotalBytes() throws Exception {
        int small = resource("static.png").length;
        var first = save(paddedPng(MarketImages.SCREENSHOT_BYTES));
        var last = save(paddedPng(MarketImages.SCREENSHOT_BYTES - small));
        var tiny = save(resource("static.png"));
        assertThat(MarketImages.validate(directory, "123", "demo", "2.3.4",
                market(List.of(first, first, first, last, tiny)))).hasSize(5);
        var exceeded = save(paddedPng(MarketImages.SCREENSHOT_BYTES - small + 1));
        assertLimit(() -> MarketImages.validate(directory, "123", "demo", "2.3.4",
                market(List.of(first, first, first, exceeded, tiny))));
        assertThatThrownBy(() -> MarketImages.validate(directory, "456", "demo", "2.3.4", market(List.of(tiny))))
                .isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.code()).isEqualTo("PATH_MISMATCH"));
        assertThat(MarketImages.validate(directory, "123", "demo", "2.3.4", market(List.of()))).isEmpty();
    }

    private MarketMetadata.Image save(byte[] bytes) throws Exception {
        String name = "assets/123/demo/2.3.4/" + CommunityJson.sha256(bytes) + ".png";
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return new MarketMetadata.Image(name, Map.of("en", "Screenshot"));
    }

    private static MarketMetadata market(List<MarketMetadata.Image> screenshots) {
        return new MarketMetadata("en", Map.of("en", "Demo"), Map.of("en", "Example"), null,
                "utility", List.of(), null, null, screenshots);
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = MarketImagesTest.class.getResourceAsStream("/community/v1/vectors/images/" + name)) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }

    private static byte[] png(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        var image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        image.flush();
        return output.toByteArray();
    }

    private static byte[] paddedPng(int size) throws Exception {
        byte[] original = resource("static.png");
        byte[] result = new byte[size];
        int position = original.length - 12;
        System.arraycopy(original, 0, result, 0, position);
        int length = size - original.length - 12;
        ByteBuffer.wrap(result, position, 4).putInt(length);
        System.arraycopy(new byte[]{'t', 'e', 'S', 't'}, 0, result, position + 4, 4);
        CRC32 crc = new CRC32();
        crc.update(result, position + 4, length + 4);
        ByteBuffer.wrap(result, position + length + 8, 4).putInt((int) crc.getValue());
        System.arraycopy(original, original.length - 12, result, result.length - 12, 12);
        return result;
    }

    private static void assertLimit(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(ContractException.class,
                error -> assertThat(error.code()).isEqualTo("LIMIT_EXCEEDED"));
    }
}
