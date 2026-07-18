package top.sywyar.pixivdownload.tts.narration.engine;

import java.util.Arrays;
import java.util.Objects;

/**
 * 引擎中性的参考音素材。调用方在进入引擎边界前准备好音频及其可选转录文本。
 *
 * @param audio 参考音频字节
 * @param mime  音频 MIME 类型
 * @param text  参考音对应的转录文本，可为空
 */
public record NarrationReferenceVoice(byte[] audio, String mime, String text) {

    public NarrationReferenceVoice {
        audio = audio == null ? null : audio.clone();
    }

    @Override
    public byte[] audio() {
        return audio == null ? null : audio.clone();
    }

    /** 是否携带有效音频字节。 */
    public boolean hasAudio() {
        return audio != null && audio.length > 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof NarrationReferenceVoice that
                && Arrays.equals(audio, that.audio)
                && Objects.equals(mime, that.mime)
                && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(audio);
        result = 31 * result + Objects.hashCode(mime);
        return 31 * result + Objects.hashCode(text);
    }
}
