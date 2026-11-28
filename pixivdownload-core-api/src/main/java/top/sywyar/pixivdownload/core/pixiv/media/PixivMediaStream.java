package top.sywyar.pixivdownload.core.pixiv.media;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 按声明长度与字节上限计量的 Pixiv 媒体响应流。
 *
 * <p>声明了长度时，正文必须恰好等于该长度：提前结束或多出字节都抛出 {@link IOException}。
 * 未声明长度时最多读取上限字节；之后仍有数据则抛出 {@link IOException}，并使
 * {@link #exceededLimit()} 返回 {@code true}，调用方据此区分超限与普通传输失败。
 * 关闭本流会关闭底层响应。
 */
public final class PixivMediaStream extends InputStream {

    private final InputStream source;
    private final long declaredLength;
    private long remaining;
    private long received;
    private boolean exceededLimit;

    /**
     * 创建计量流。
     *
     * @param source 上游响应正文
     * @param declaredLength 上游声明的正文长度；{@code -1} 表示未声明
     * @param maximumBytes 允许读取的最大字节数，必须大于零且不小于声明长度
     * @throws IllegalArgumentException 长度或上限不合法时抛出
     */
    public PixivMediaStream(InputStream source, long declaredLength, long maximumBytes) {
        this.source = Objects.requireNonNull(source, "source");
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        if (declaredLength < -1 || declaredLength > maximumBytes) {
            throw new IllegalArgumentException("declaredLength must be -1 or between 0 and maximumBytes");
        }
        this.declaredLength = declaredLength;
        this.remaining = declaredLength >= 0 ? declaredLength : maximumBytes;
    }

    /**
     * 返回上游声明的正文长度。
     *
     * @return 声明长度；未声明时返回 {@code -1}
     */
    public long declaredLength() {
        return declaredLength;
    }

    /**
     * 返回读取是否因超过字节上限而中止。
     *
     * @return 未声明长度的正文超过上限时返回 {@code true}
     */
    public boolean exceededLimit() {
        return exceededLimit;
    }

    @Override
    public int read(byte[] bytes, int offset, int count) throws IOException {
        Objects.checkFromIndexSize(offset, count, bytes.length);
        if (count == 0) {
            return 0;
        }
        if (remaining == 0) {
            if (source.read() < 0) {
                return -1;
            }
            if (declaredLength < 0) {
                exceededLimit = true;
                throw new IOException("Pixiv media exceeded its byte limit");
            }
            throw new IOException("Pixiv media exceeded its declared length");
        }
        int read = source.read(bytes, offset, (int) Math.min(remaining, count));
        if (read < 0) {
            if (declaredLength >= 0 && received < declaredLength) {
                throw new IOException("Pixiv media ended before its declared length");
            }
            return -1;
        }
        received += read;
        remaining -= read;
        return read;
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        return read < 0 ? -1 : Byte.toUnsignedInt(single[0]);
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
