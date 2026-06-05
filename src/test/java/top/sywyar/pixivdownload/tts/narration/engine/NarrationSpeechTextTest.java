package top.sywyar.pixivdownload.tts.narration.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("朗读文本工具（归一 / 可发音统计 / 空白归 null / 短输入判定）")
class NarrationSpeechTextTest {

    @Test
    @DisplayName("normalize：省略号 / 悬挂标点结尾替换为句号，纯标点 / 空白 → 空串，正常句原样")
    void normalizeHandlesEllipsisAndPunctuationOnly() {
        assertThat(NarrationSpeechText.normalize("不……")).isEqualTo("不。");
        assertThat(NarrationSpeechText.normalize("「啊……」")).isEqualTo("「啊。」");
        assertThat(NarrationSpeechText.normalize("Wait...")).isEqualTo("Wait.");
        assertThat(NarrationSpeechText.normalize("他走了——")).isEqualTo("他走了。");
        assertThat(NarrationSpeechText.normalize("……")).isEmpty();
        assertThat(NarrationSpeechText.normalize("。。。")).isEmpty();
        assertThat(NarrationSpeechText.normalize("「」")).isEmpty();
        assertThat(NarrationSpeechText.normalize("   ")).isEmpty();
        assertThat(NarrationSpeechText.normalize(null)).isEmpty();
        assertThat(NarrationSpeechText.normalize("你好世界")).isEqualTo("你好世界");
        assertThat(NarrationSpeechText.normalize("啊？")).isEqualTo("啊？");
    }

    @Test
    @DisplayName("speakableCount / hasSpeakable：只数字母 / 数字 / 表意文字等可发音字符")
    void speakableCountIgnoresPunctuation() {
        assertThat(NarrationSpeechText.speakableCount("吗？")).isEqualTo(1);
        assertThat(NarrationSpeechText.speakableCount("你好，世界！")).isEqualTo(4);
        assertThat(NarrationSpeechText.speakableCount("……")).isZero();
        assertThat(NarrationSpeechText.speakableCount(null)).isZero();
        assertThat(NarrationSpeechText.hasSpeakable("！？")).isFalse();
        assertThat(NarrationSpeechText.hasSpeakable("a")).isTrue();
        assertThat(NarrationSpeechText.hasSpeakable(null)).isFalse();
    }

    @Test
    @DisplayName("blankToNull：空 / 空白 → null，否则返回 trim 后的值")
    void blankToNullTrimsOrNullifies() {
        assertThat(NarrationSpeechText.blankToNull(null)).isNull();
        assertThat(NarrationSpeechText.blankToNull("")).isNull();
        assertThat(NarrationSpeechText.blankToNull("   ")).isNull();
        assertThat(NarrationSpeechText.blankToNull("  alloy  ")).isEqualTo("alloy");
    }

    @Test
    @DisplayName("isShortInput：按可发音字数判定超短输入")
    void isShortInputCountsSpeakable() {
        assertThat(NarrationSpeechText.isShortInput("吗？", 1)).isTrue();
        assertThat(NarrationSpeechText.isShortInput("你好", 1)).isFalse();
        assertThat(NarrationSpeechText.isShortInput("……", 1)).isTrue();
        assertThat(NarrationSpeechText.isShortInput(null, 1)).isTrue();
    }
}
