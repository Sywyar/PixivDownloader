package top.sywyar.pixivdownload.tts.narration.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("朗读二进制值对象")
class NarrationBinaryValueTest {

    @Test
    @DisplayName("音频结果在构造和读取时都防御性复制字节")
    void narrationAudioDefensivelyCopiesBytes() {
        byte[] source = {1, 2, 3};
        NarrationAudio audio = new NarrationAudio(source, "audio/wav");

        source[0] = 9;
        byte[] returned = audio.data();
        returned[1] = 9;

        assertThat(audio.data()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("参考音在构造和读取时都防御性复制字节")
    void referenceVoiceDefensivelyCopiesBytes() {
        byte[] source = {4, 5, 6};
        NarrationReferenceVoice voice = new NarrationReferenceVoice(source, "audio/wav", "text");

        source[0] = 9;
        byte[] returned = voice.audio();
        returned[1] = 9;

        assertThat(voice.audio()).containsExactly(4, 5, 6);
    }

    @Test
    @DisplayName("音频结果按字节内容参与相等性和哈希")
    void narrationAudioUsesContentEquality() {
        NarrationAudio left = new NarrationAudio(new byte[]{1, 2}, "audio/wav");
        NarrationAudio right = new NarrationAudio(new byte[]{1, 2}, "audio/wav");

        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
        assertThat(left).isNotEqualTo(new NarrationAudio(new byte[]{1, 3}, "audio/wav"));
        assertThat(new NarrationAudio(null, null)).isEqualTo(new NarrationAudio(null, null));
    }

    @Test
    @DisplayName("参考音按字节内容参与相等性和哈希")
    void referenceVoiceUsesContentEquality() {
        NarrationReferenceVoice left = new NarrationReferenceVoice(new byte[]{1, 2}, "audio/wav", "text");
        NarrationReferenceVoice right = new NarrationReferenceVoice(new byte[]{1, 2}, "audio/wav", "text");

        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
        assertThat(left).isNotEqualTo(
                new NarrationReferenceVoice(new byte[]{1, 3}, "audio/wav", "text"));
        assertThat(new NarrationReferenceVoice(null, null, null))
                .isEqualTo(new NarrationReferenceVoice(null, null, null));
    }
}
