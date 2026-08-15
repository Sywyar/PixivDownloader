package top.sywyar.pixivdownload.core.asset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    @DisplayName("压缩体积很小但总像素超限的图片在像素分配前被拒绝")
    void rejectsTinyEncodedImageWithExcessivePixels() throws Exception {
        Path image = writePngHeader("pixel-bomb.png", 6_000, 5_000);

        assertThat(Files.size(image)).isLessThan(100);
        assertThatThrownBy(() -> BoundedImageDecoder.read(image))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pixel count");
    }

    @Test
    @DisplayName("宽度和高度分别受独立上限保护")
    void rejectsExcessiveWidthAndHeight() throws Exception {
        Path wide = writePngHeader("wide.png", 25_001, 1);
        Path tall = writePngHeader("tall.png", 1, 25_001);

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
            file.setLength(100L * 1024L * 1024L + 1L);
        }

        assertThatThrownBy(() -> BoundedImageDecoder.read(image))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("source byte");
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
