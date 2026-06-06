package top.sywyar.pixivdownload.novel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("源语言探测样本提取")
class NovelTranslationServiceSampleTest {

    @Test
    @DisplayName("跳过空行与纯 Pixiv 标记行，仅累计自然语言文本")
    void skipsBlankAndMarkupLines() {
        String raw = "[newpage]\n\n[pixivimage:12345]\nこんにちは世界\n本文の続き";
        String sample = NovelTranslationService.firstTextSample(raw, 200);
        assertTrue(sample.contains("こんにちは世界"), "应保留正文文本");
        assertTrue(sample.contains("本文の続き"), "应跨行继续累计");
        assertFalse(sample.contains("["), "不应保留 Pixiv 标记");
        assertFalse(sample.contains("pixivimage"), "不应保留标记内容");
    }

    @Test
    @DisplayName("去掉行内 [[...]] / [...] 标记后保留自然语言")
    void stripsInlineMarkup() {
        String raw = "前文[[rb:漢字 > かんじ]]後文";
        String sample = NovelTranslationService.firstTextSample(raw, 200);
        assertTrue(sample.contains("前文"));
        assertTrue(sample.contains("後文"));
        assertFalse(sample.contains("rb:"));
    }

    @Test
    @DisplayName("累计到上限后截断")
    void truncatesAtMaxChars() {
        String raw = "あ".repeat(500);
        String sample = NovelTranslationService.firstTextSample(raw, 200);
        assertEquals(200, sample.length());
    }

    @Test
    @DisplayName("长正文语言探测样本覆盖开头中段结尾")
    void languageProbeSampleCoversBeginningMiddleAndEnd() {
        String raw = "English opening. ".repeat(5)
                + "\n本文の中段。".repeat(20)
                + "\n終章の本文。".repeat(20);

        String sample = NovelTranslationService.languageProbeSample(raw, 180);

        assertTrue(sample.contains("English"), "应包含开头样本");
        assertTrue(sample.contains("本文の中段"), "应包含中段样本");
        assertTrue(sample.contains("終章の本文"), "应包含结尾样本");
        assertTrue(sample.length() <= 180, "不应超过 token 控制上限");
    }

    @Test
    @DisplayName("空正文 / 仅标记行返回空样本")
    void emptyWhenNoText() {
        assertEquals("", NovelTranslationService.firstTextSample(null, 200));
        assertEquals("", NovelTranslationService.firstTextSample("   \n\n  ", 200));
        assertEquals("", NovelTranslationService.firstTextSample("[newpage]\n[chapter:序章]", 200));
    }
}
