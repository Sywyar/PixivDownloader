package top.sywyar.pixivdownload.pixivcontent;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** A pull-based, full-canvas APNG encoder. Only the current raster, scanline and chunk are retained. */
final class ApngInputStream extends InputStream {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final int OUTPUT_BYTES = 100 * 1024 * 1024;
    private final byte[] compressed = new byte[32 * 1024];
    private final int width;
    private final int height;
    private final byte[] scanline;
    private final int[] rowPixels;
    private final List<UgoiraAnimation.Frame> frames;
    private final Runnable release;
    private final Deflater deflater;
    private BufferedImage image;
    private ByteBuffer chunk = ByteBuffer.wrap(SIGNATURE);
    private int sequence;
    private int frameIndex;
    private int row;
    private long produced = SIGNATURE.length;
    private boolean closed;
    private String failureReason = "animation_encode_failed";
    private Stage stage = Stage.HEADER;

    private enum Stage { HEADER, ANIMATION, FRAME, DATA, END, DONE }

    ApngInputStream(List<UgoiraAnimation.Frame> frames, Dimension size, Runnable release) {
        this.frames = frames;
        this.release = release;
        width = size.width;
        height = size.height;
        scanline = new byte[1 + 4 * width];
        rowPixels = new int[width];
        image = frames.getFirst().source().decode();
        deflater = new Deflater(Deflater.BEST_SPEED);
    }

    @Override public int read() throws IOException {
        return nextChunk() ? Byte.toUnsignedInt(chunk.get()) : -1;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (!nextChunk()) return -1;
        int count = Math.min(chunk.remaining(), length);
        chunk.get(bytes, offset, count);
        return count;
    }

    private boolean nextChunk() throws IOException {
        if (closed) return false;
        try {
            while (!chunk.hasRemaining()) {
                switch (stage) {
                    case HEADER -> {
                        emit("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height)
                                .put((byte) 8).put((byte) 6).put(new byte[3]).array());
                        stage = Stage.ANIMATION;
                    }
                    case ANIMATION -> {
                        emit("acTL", ByteBuffer.allocate(8).putInt(frames.size()).putInt(0).array());
                        stage = Stage.FRAME;
                    }
                    case FRAME -> startFrame();
                    case DATA -> readPixels();
                    case END -> {
                        emit("IEND", new byte[0]);
                        stage = Stage.DONE;
                    }
                    case DONE -> {
                        close();
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            close();
            if (failure instanceof IOException io) throw io;
            failureReason = "animation_frame_invalid";
            throw new IOException("Invalid Pixiv animation frame", failure);
        }
    }

    /** {@return 生成中断时的固定英文原因码，供中止日志使用} */
    String failureReason() {
        return failureReason;
    }

    private void startFrame() throws IOException {
        var frame = frames.get(frameIndex);
        if (frameIndex > 0) image = frame.source().decode();
        row = 0;
        deflater.reset();
        emit("fcTL", ByteBuffer.allocate(26).putInt(sequence++).putInt(width).putInt(height)
                .putInt(0).putInt(0).putShort((short) frame.numerator()).putShort((short) frame.denominator())
                .put((byte) 0).put((byte) 0).array());
        stage = Stage.DATA;
    }

    private void readPixels() throws IOException {
        while (!deflater.finished()) {
            if (deflater.needsInput()) {
                if (row < height) {
                    encodeScanline();
                    deflater.setInput(scanline);
                } else {
                    deflater.finish();
                }
            }
            int count = deflater.deflate(compressed);
            if (count != 0) {
                if (frameIndex == 0) {
                    emit("IDAT", null, compressed, count);
                } else {
                    emit("fdAT", sequence++, compressed, count);
                }
                return;
            }
        }
        image.flush();
        image = null;
        frames.set(frameIndex, null);
        stage = ++frameIndex < frames.size() ? Stage.FRAME : Stage.END;
    }

    private void encodeScanline() {
        image.getRGB(0, row++, width, 1, rowPixels, 0, width);
        scanline[0] = 1; // PNG Sub filter, applied independently to each RGBA channel.
        int offset = 1;
        int left = 0;
        for (int pixel : rowPixels) {
            scanline[offset++] = (byte) ((pixel >>> 16) - (left >>> 16));
            scanline[offset++] = (byte) ((pixel >>> 8) - (left >>> 8));
            scanline[offset++] = (byte) (pixel - left);
            scanline[offset++] = (byte) ((pixel >>> 24) - (left >>> 24));
            left = pixel;
        }
    }

    private void emit(String type, byte[] data) throws IOException {
        emit(type, null, data, data.length);
    }

    private void emit(String type, Integer frameSequence, byte[] data, int count) throws IOException {
        int size = count + (frameSequence == null ? 0 : 4);
        produced += size + 12L;
        if (produced > OUTPUT_BYTES) {
            failureReason = "animation_output_limit";
            throw new IOException("Pixiv animation exceeded its output limit");
        }
        var block = ByteBuffer.allocate(size + 12).putInt(size).put(type.getBytes(StandardCharsets.US_ASCII));
        if (frameSequence != null) block.putInt(frameSequence);
        block.put(data, 0, count);
        var crc = new CRC32();
        crc.update(block.array(), 4, size + 4);
        chunk = block.putInt((int) crc.getValue()).flip();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        frames.clear();
        if (image != null) image.flush();
        image = null;
        deflater.end();
        release.run();
    }
}
