package top.sywyar.pixivdownload.ai.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("翻译响应 JSON 解析")
class TranslationResponseTest {

    @Test
    @DisplayName("解析标准 JSON：状态/语言代码/译文")
    void parseStandardJson() {
        TranslationResponse r = TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"zh-CN\",\"text\":\"你好\"}");
        assertEquals("ok", r.status());
        assertEquals("zh-CN", r.lang());
        assertEquals("你好", r.text());
        assertTrue(r.ok());
        assertFalse(r.invalidLanguage());
    }

    @Test
    @DisplayName("容忍代码围栏 / 外层多余文字：截取首个对象解析")
    void parseWrappedJson() {
        TranslationResponse r = TranslationResponse.parse(
                "```json\n{\"status\":\"ok\",\"lang\":\"en-US\",\"text\":\"hi\"}\n```");
        assertEquals("en-US", r.lang());
        assertEquals("hi", r.text());
        assertTrue(r.ok());
    }

    @Test
    @DisplayName("语言不存在：invalid_language 命中、ok 为 false")
    void parseInvalidLanguage() {
        TranslationResponse r = TranslationResponse.parse(
                "{\"status\":\"invalid_language\",\"lang\":\"\",\"text\":\"\"}");
        assertTrue(r.invalidLanguage());
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("空内容或无法解析时抛出 IllegalArgumentException")
    void parseEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> TranslationResponse.parse(""));
        assertThrows(IllegalArgumentException.class, () -> TranslationResponse.parse("not json at all"));
    }
}
