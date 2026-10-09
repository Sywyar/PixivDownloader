package top.sywyar.pixivdownload.download.media.webp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class WebpAnimationMuxerTest {
    @TempDir Path directory;

    @Test
    @DisplayName("分片按输入顺序合并、恢复全部延时并合并透明标记")
    void joinsFramesAndRestoresTimeline() throws Exception {
        Path first = Files.write(directory.resolve("first.webp"), animation(0, 11, 12));
        Path second = Files.write(directory.resolve("second.webp"), animation(16, 21, 22));
        Path output = directory.resolve("animation.webp");
        WebpAnimationMuxer.merge(List.of(new WebpAnimationMuxer.Part(first, List.of(34, 83)),
                new WebpAnimationMuxer.Part(second, List.of(200))), output, 4096, () -> {});
        byte[] bytes = Files.readAllBytes(output);
        assertEquals(bytes.length - 8, ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertEquals(18, bytes[20]);
        int[] delays = {34, 83, 200};
        int[] pixels = {11, 12, 21};
        int offset = 44;
        for (int index = 0; index < delays.length; index++) {
            assertEquals("ANMF", new String(bytes, offset, 4, StandardCharsets.US_ASCII));
            assertEquals(delays[index], (bytes[offset + 20] & 255)
                    | (bytes[offset + 21] & 255) << 8 | (bytes[offset + 22] & 255) << 16);
            assertEquals(2, bytes[offset + 23]);
            assertEquals(pixels[index], bytes[offset + 32]);
            offset += 36;
        }
        assertEquals(bytes.length, offset);
    }

    @Test
    @DisplayName("拒绝损坏容器、额外帧、尺寸不一致和累计输出超限")
    void rejectsMalformedAndOversizedParts() throws Exception {
        Path input = directory.resolve("part.webp");
        Path output = directory.resolve("output.webp");
        byte[] valid = animation(0, 1, 2);
        for (int position : new int[]{0, 4, 12, 16, 20, 30, 42, 44, 48, 52, 58, 68, 72}) {
            byte[] broken = valid.clone();
            broken[position] = (byte) 255;
            Files.write(input, broken);
            assertThrows(IOException.class, () -> WebpAnimationMuxer.merge(
                    List.of(new WebpAnimationMuxer.Part(input, List.of(10, 20))), output, 4096, () -> {}),
                    "offset " + position);
        }
        Files.write(input, valid);
        assertThrows(IOException.class, () -> WebpAnimationMuxer.merge(
                List.of(new WebpAnimationMuxer.Part(input, List.of(10, 20, 30))), output, 4096, () -> {}));
        assertThrows(IOException.class, () -> WebpAnimationMuxer.merge(
                List.of(new WebpAnimationMuxer.Part(input, List.of(10, 20)),
                        new WebpAnimationMuxer.Part(input, List.of(10, 20))), output, valid.length, () -> {}));
        assertTrue(Files.size(output) <= valid.length);
        byte[] different = valid.clone();
        different[24] = 1;
        Path other = Files.write(directory.resolve("other.webp"), different);
        assertThrows(IOException.class, () -> WebpAnimationMuxer.merge(
                List.of(new WebpAnimationMuxer.Part(input, List.of(10, 20)),
                        new WebpAnimationMuxer.Part(other, List.of(10, 20))), output, 4096, () -> {}));
        assertThrows(CancellationException.class, () -> WebpAnimationMuxer.merge(
                List.of(new WebpAnimationMuxer.Part(input, List.of(10, 20))), output, 4096,
                () -> { throw new CancellationException(); }));
    }

    // 仅用于容器边界测试的编码载荷，真实像素解码另由 FFmpeg 回归验证。
    static byte[] animation(int flags, int... frames) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] header = new byte[10];
        header[0] = (byte) (2 | flags);
        chunk(body, "VP8X", header);
        chunk(body, "ANIM", new byte[6]);
        for (int value : frames) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            byte[] timing = new byte[16];
            timing[12] = 1;
            frame.write(timing);
            chunk(frame, "VP8L", new byte[]{(byte) value, 0, 0, 0});
            chunk(body, "ANMF", frame.toByteArray());
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        result.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size() + 4).array());
        result.write("WEBP".getBytes(StandardCharsets.US_ASCII));
        result.write(body.toByteArray());
        return result.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream target, String tag, byte[] payload) throws IOException {
        target.write(tag.getBytes(StandardCharsets.US_ASCII));
        target.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array());
        target.write(payload);
        if ((payload.length & 1) != 0) target.write(0);
    }
}
