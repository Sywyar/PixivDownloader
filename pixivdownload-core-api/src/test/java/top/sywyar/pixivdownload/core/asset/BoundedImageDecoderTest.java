package top.sywyar.pixivdownload.core.asset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("有界图片解码")
class BoundedImageDecoderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("正常图片在读取元数据后完成解码")
    void decodesImageWithinLimits() throws Exception {
        Path image = tempDir.resolve("small.png");
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", image.toFile());

        BufferedImage decoded = BoundedImageDecoder.read(image);

        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(4);
        assertThat(decoded.getHeight()).isEqualTo(3);
    }

    @Test
    @DisplayName("大尺寸但缺少像素数据的截断图片仍然被拒绝")
    void rejectsTruncatedLargeImage() throws Exception {
        Path image = writePngHeader("pixel-bomb.png", 6_000, 5_000);

        assertThat(Files.size(image)).isLessThan(100);
        assertThatThrownBy(() -> BoundedImageDecoder.read(image))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("可能分配完整帧的 GIF 解码器仍受源像素预算保护")
    void rejectsOversizedGifBeforeReadingFrame() throws Exception {
        Path image = tempDir.resolve("large.gif");
        ImageIO.write(new BufferedImage(6000, 5000, BufferedImage.TYPE_BYTE_BINARY), "gif", image.toFile());

        assertThatThrownBy(() -> BoundedImageDecoder.read(image))
                .isInstanceOf(IOException.class).hasMessageContaining("pixel count");
    }

    @Test
    @DisplayName("宽度和高度分别受独立上限保护")
    void rejectsExcessiveWidthAndHeight() throws Exception {
        Path wide = writePngHeader("wide.png", BoundedImageDecoder.MAX_WIDTH + 1, 1);
        Path tall = writePngHeader("tall.png", 1, BoundedImageDecoder.MAX_HEIGHT + 1);

        assertThatThrownBy(() -> BoundedImageDecoder.read(wide))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("width");
        assertThatThrownBy(() -> BoundedImageDecoder.read(tall))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("height");
    }

    @Test
    @DisplayName("源文件字节数在解码器打开图片流前受限")
    void rejectsExcessiveSourceBytes() throws Exception {
        Path image = tempDir.resolve("oversized.png");
        try (RandomAccessFile file = new RandomAccessFile(image.toFile(), "rw")) {
            file.setLength(PixivImageTransferObserver.MAX_IMAGE_BYTES + 1L);
        }

        assertThatThrownBy(() -> BoundedImageDecoder.read(image))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("source byte");
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg"})
    @DisplayName("36712368 像素原图在解码预算内读取并按原始尺寸生成缩略图")
    void subsamplesLargeImageAndPreservesThumbnailSize(String format) throws Exception {
        Path image = tempDir.resolve("large." + format);
        BufferedImage original = new BufferedImage(6192, 5929, BufferedImage.TYPE_BYTE_GRAY);
        original.getRaster().setSample(0, 0, 0, 255);
        assertThat(ImageIO.write(original, format, image.toFile())).isTrue();

        BufferedImage decoded = BoundedImageDecoder.read(image);
        assertThat(decoded).isNotNull();
        assertThat((long) decoded.getWidth() * decoded.getHeight()).isLessThanOrEqualTo(BoundedImageDecoder.MAX_PIXELS);
        assertThat(decoded.getWidth()).isLessThan(original.getWidth());

        BufferedImage thumbnail = ImageThumbnailScaler.scale(image, -1, -1);
        assertThat(thumbnail.getWidth()).isEqualTo(2064);
        assertThat(thumbnail.getHeight()).isEqualTo(1976);
        BufferedImage preview = ImageThumbnailScaler.scale(image, 1600, 1600);
        assertThat(preview.getWidth()).isEqualTo(1600);
        assertThat(preview.getHeight()).isEqualTo(1532);
    }

    @Test
    @DisplayName("小图不放大且透明像素在缩略图中合成为白色")
    void keepsSmallImageSizeAndFlattensAlpha() throws Exception {
        Path image = tempDir.resolve("transparent.png");
        ImageIO.write(new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB), "png", image.toFile());

        BufferedImage decoded = BoundedImageDecoder.read(image);
        assertThat(decoded.getRGB(0, 0)).isZero();
        BufferedImage thumbnail = ImageThumbnailScaler.scale(image, 1600, 1600);
        assertThat(thumbnail.getWidth()).isEqualTo(2);
        assertThat(thumbnail.getHeight()).isEqualTo(1);
        assertThat(thumbnail.getRGB(0, 0)).isEqualTo(0xffffffff);
        assertThat(ImageThumbnailScaler.scale(image, -1, -1).getHeight()).isEqualTo(1);
    }

    private Path writePngHeader(String fileName, int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            try (DataOutputStream header = new DataOutputStream(headerBytes)) {
                header.writeInt(width);
                header.writeInt(height);
                header.writeByte(8);
                header.writeByte(2);
                header.writeByte(0);
                header.writeByte(0);
                header.writeByte(0);
            }
            writeChunk(output, "IHDR", headerBytes.toByteArray());
            writeChunk(output, "IEND", new byte[0]);
        }
        return Files.write(tempDir.resolve(fileName), bytes.toByteArray());
    }

    private static void writeChunk(DataOutputStream output, String type, byte[] data) throws IOException {
        output.writeInt(data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        output.write(typeBytes);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeInt((int) crc.getValue());
    }
}
