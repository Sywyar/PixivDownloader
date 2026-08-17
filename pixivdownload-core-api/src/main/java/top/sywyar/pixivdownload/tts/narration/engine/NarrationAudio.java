package top.sywyar.pixivdownload.tts.narration.engine;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一次朗读合成的音频结果。
 *
 * @param data        音频字节
 * @param contentType 音频 MIME 类型
 */
public record NarrationAudio(byte[] data, String contentType) {

    /**
     * 创建 {@code NarrationAudio} 实例。
     *
     * @param data 数据
     * @param contentType 内容类型
     */
    public NarrationAudio {
        data = data == null ? null : data.clone();
    }

    /**
     * 返回数据。
     *
     * @return 返回的字节数据
     */
    @Override
    public byte[] data() {
        return data == null ? null : data.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof NarrationAudio that
                && Arrays.equals(data, that.data)
                && Objects.equals(contentType, that.contentType);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(data) + Objects.hashCode(contentType);
    }
}
