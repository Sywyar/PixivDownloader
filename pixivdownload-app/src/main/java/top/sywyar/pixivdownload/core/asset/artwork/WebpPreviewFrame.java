package top.sywyar.pixivdownload.core.asset.artwork;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/** 只物化动画首帧及其解码头，避免预览大小受整部动画字节数限制。 */
final class WebpPreviewFrame {
    private WebpPreviewFrame() {}

    static void copy(Path source, Path target, long maximumBytes) throws IOException {
        try (var input = new RandomAccessFile(source.toFile(), "r");
             var output = new RandomAccessFile(target.toFile(), "rw")) {
            long length = input.length();
            if (length < 44 || length > 0xffff_fffeL || input.readInt() != 0x52494646
                    || Integer.toUnsignedLong(Integer.reverseBytes(input.readInt())) != length - 8
                    || input.readInt() != 0x57454250) throw new IOException("Invalid WebP RIFF");
            output.setLength(0);
            output.writeInt(0x52494646);
            output.writeInt(0);
            output.writeInt(0x57454250);
            byte[] buffer = new byte[64 * 1024];
            boolean animation = false;
            while (input.getFilePointer() < length) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Image decoding interrupted");
                if (length - input.getFilePointer() < 8) throw new IOException("Truncated WebP chunk");
                int tag = input.readInt();
                int encodedSize = input.readInt();
                long size = Integer.toUnsignedLong(Integer.reverseBytes(encodedSize));
                long padded = size + (size & 1);
                if (padded > length - input.getFilePointer() || padded > maximumBytes - output.length() - 8) {
                    throw new IOException("WebP preview byte limit exceeded");
                }
                if (output.length() == 12) {
                    if (tag != 0x56503858 || size != 10) throw new IOException("Missing WebP animation header");
                    int flags = input.readUnsignedByte();
                    if ((flags & 2) == 0) throw new IOException("Expected animated WebP");
                    input.seek(input.getFilePointer() - 1);
                }
                if (tag == 0x414e494d) {
                    if (animation || size != 6) throw new IOException("Invalid WebP animation control");
                    animation = true;
                }
                boolean frame = tag == 0x414e4d46;
                if (frame && (!animation || size < 24)) throw new IOException("Invalid WebP first frame");
                output.writeInt(tag);
                output.writeInt(encodedSize);
                while (padded > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Image decoding interrupted");
                    int count = (int) Math.min(buffer.length, padded);
                    input.readFully(buffer, 0, count);
                    output.write(buffer, 0, count);
                    padded -= count;
                }
                if (frame) {
                    output.seek(4);
                    output.writeInt(Integer.reverseBytes((int) (output.length() - 8)));
                    return;
                }
            }
            throw new IOException("Missing WebP first frame");
        }
    }
}
