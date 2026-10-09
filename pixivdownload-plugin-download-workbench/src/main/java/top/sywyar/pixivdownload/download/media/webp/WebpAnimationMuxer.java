package top.sywyar.pixivdownload.download.media.webp;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** 流式合并 libwebp 的完整帧；时序以源帧为准，不重新编码像素。 */
final class WebpAnimationMuxer {
    static final long MAX_FILE_BYTES = 0xffff_fffeL;
    private WebpAnimationMuxer() {}

    record Part(Path path, List<Integer> delays) {
        Part { delays = List.copyOf(delays); }
    }

    static void merge(List<Part> parts, Path target, long maximumBytes, Runnable check) throws IOException {
        maximumBytes = Math.min(maximumBytes, MAX_FILE_BYTES);
        byte[] canvas = null;
        byte[] animation = null;
        int flags = 2;
        byte[] buffer = new byte[64 * 1024];
        try (RandomAccessFile output = new RandomAccessFile(target.toFile(), "rw")) {
            output.setLength(0);
            output.writeBytes("RIFF");
            write32(output, 0);
            output.writeBytes("WEBP");
            for (Part part : parts) {
                check.run();
                try (RandomAccessFile input = new RandomAccessFile(part.path().toFile(), "r")) {
                    require(input.length() >= 44 && input.length() <= MAX_FILE_BYTES, "Invalid WebP size");
                    require(fourcc(input).equals("RIFF") && read32(input) == input.length() - 8
                            && fourcc(input).equals("WEBP"), "Invalid WebP RIFF");
                    require(fourcc(input).equals("VP8X") && read32(input) == 10, "Missing animated WebP header");
                    byte[] header = new byte[10];
                    input.readFully(header);
                    require((header[0] & ~0x12) == 0 && (header[0] & 2) != 0
                            && header[1] == 0 && header[2] == 0 && header[3] == 0, "Unexpected WebP features");
                    if (canvas == null) canvas = header;
                    require(Arrays.equals(canvas, 4, 10, header, 4, 10), "WebP canvas mismatch");
                    flags |= header[0];
                    require(fourcc(input).equals("ANIM") && read32(input) == 6, "Missing WebP animation");
                    byte[] control = new byte[6];
                    input.readFully(control);
                    require(control[4] == 0 && control[5] == 0, "Invalid WebP loop");
                    if (animation == null) {
                        animation = control;
                        output.writeBytes("VP8X");
                        write32(output, 10);
                        output.write(canvas);
                        output.writeBytes("ANIM");
                        write32(output, 6);
                        output.write(animation);
                    }
                    require(Arrays.equals(animation, control), "WebP animation mismatch");
                    int frames = 0;
                    int expected = Math.max(2, part.delays().size());
                    while (input.getFilePointer() < input.length()) {
                        check.run();
                        require(frames < expected && input.length() - input.getFilePointer() >= 8
                                && fourcc(input).equals("ANMF"), "Unexpected WebP frame");
                        long size = read32(input);
                        long end = input.getFilePointer() + size;
                        require(size >= 24 && end + (size & 1) <= input.length(), "Truncated WebP frame");
                        byte[] frame = new byte[16];
                        input.readFully(frame);
                        require(read24(frame, 0) == 0 && read24(frame, 3) == 0
                                && read24(frame, 6) == read24(canvas, 4)
                                && read24(frame, 9) == read24(canvas, 7)
                                && (frame[15] & ~3) == 0, "Expected complete WebP frame");
                        boolean retain = frames < part.delays().size();
                        if (retain) {
                            int delay = part.delays().get(frames);
                            require(delay > 0 && delay <= 0xffffff, "Invalid WebP frame delay");
                            require(output.getFilePointer() + 8 + size + (size & 1) <= maximumBytes,
                                    "Media output byte limit exceeded");
                            frame[12] = (byte) delay;
                            frame[13] = (byte) (delay >>> 8);
                            frame[14] = (byte) (delay >>> 16);
                            // 每帧覆盖完整画布，透明像素也必须替换上一帧。
                            frame[15] = 2;
                            output.writeBytes("ANMF");
                            write32(output, size);
                            output.write(frame);
                        }
                        boolean alpha = false;
                        boolean image = false;
                        while (input.getFilePointer() < end) {
                            check.run();
                            require(end - input.getFilePointer() >= 8, "Truncated WebP subchunk");
                            String tag = fourcc(input);
                            long length = read32(input);
                            long padded = length + (length & 1);
                            require(length > 0 && padded <= end - input.getFilePointer(), "Invalid WebP subchunk size");
                            if (tag.equals("ALPH")) {
                                require(!alpha && !image, "Invalid WebP alpha order");
                                alpha = true;
                                flags |= 0x10;
                            } else {
                                require(!image && (tag.equals("VP8 ") || tag.equals("VP8L") && !alpha),
                                        "Invalid WebP image data");
                                image = true;
                            }
                            if (retain) {
                                output.write(tag.getBytes(StandardCharsets.US_ASCII));
                                write32(output, length);
                            }
                            long remaining = padded;
                            while (remaining > 0) {
                                check.run();
                                int count = (int) Math.min(buffer.length, remaining);
                                input.readFully(buffer, 0, count);
                                if (remaining == count && (length & 1) != 0) {
                                    require(buffer[count - 1] == 0, "Invalid WebP padding");
                                }
                                if (retain) output.write(buffer, 0, count);
                                remaining -= count;
                            }
                        }
                        require(image && input.getFilePointer() == end, "Missing WebP image data");
                        if ((size & 1) != 0) {
                            require(input.readUnsignedByte() == 0, "Invalid WebP frame padding");
                            if (retain) output.write(0);
                        }
                        frames++;
                    }
                    require(frames == expected, "WebP frame count mismatch");
                }
            }
            require(canvas != null && output.length() <= maximumBytes, "Empty WebP animation");
            check.run();
            output.seek(4);
            write32(output, output.length() - 8);
            output.seek(20);
            output.write(flags);
        }
    }

    private static int read24(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8 | (bytes[offset + 2] & 255) << 16;
    }

    private static long read32(RandomAccessFile file) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
    }

    private static void write32(RandomAccessFile file, long value) throws IOException {
        file.writeInt(Integer.reverseBytes((int) value));
    }

    private static String fourcc(RandomAccessFile file) throws IOException {
        byte[] bytes = new byte[4];
        file.readFully(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
