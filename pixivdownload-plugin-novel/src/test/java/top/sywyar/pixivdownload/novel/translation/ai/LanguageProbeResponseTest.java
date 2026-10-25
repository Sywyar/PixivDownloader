package top.sywyar.pixivdownload.novel.translation.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("语言探测响应解析")
class LanguageProbeResponseTest {

    @ParameterizedTest
    @ValueSource(strings = {"%s", "```json\n%s\n```", "说明：%s 完毕", "\u0000\t%s\r\n\u0000", "[%s]"})
    @DisplayName("解析标准、围栏和包裹对象，保留各自字段并忽略未知字段")
    void parsesWrappedObject(String wrapper) {
        String content = wrapper.formatted(
                "{\"code\":\"en-US\",\"valid\":true,\"sourceLang\":\"zh-CN\",\"same\":true,\"extra\":{\"x\":1}}");
        LangProbeResponse language = LangProbeResponse.parse(content);
        SourceLanguageProbeResponse source = SourceLanguageProbeResponse.parse(content);
        assertEquals(new LangProbeResponse("en-US", true), language);
        assertTrue(language.ok());
        assertEquals(new SourceLanguageProbeResponse("zh-CN", true), source);
        assertTrue(source.isSame());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\r\n", "\u0000", "not json at all", "[]", "42", "true", "\"text\"",
            "{", "prefix {broken} suffix", "{\"valid\":[],\"same\":[]}", "{\"valid\":\"yes\",\"same\":\"yes\"}"})
    @DisplayName("空内容和解析失败分别返回无效语言与无法判定")
    void preservesFailureSentinels(String content) {
        assertEquals(new LangProbeResponse("", false), LangProbeResponse.parse(content));
        assertEquals(new SourceLanguageProbeResponse("", null), SourceLanguageProbeResponse.parse(content));
    }

    @Test
    @DisplayName("缺少判定字段保留已解析的语言代码")
    void preservesMissingDecision() {
        LangProbeResponse language = LangProbeResponse.parse("{\"code\":\"en-US\"}");
        SourceLanguageProbeResponse source = SourceLanguageProbeResponse.parse("{\"sourceLang\":\"en-US\"}");
        assertEquals(new LangProbeResponse("en-US", false), language);
        assertFalse(language.ok());
        assertEquals(new SourceLanguageProbeResponse("en-US", null), source);
        assertFalse(source.isSame());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"valid\":true}", "{\"code\":null,\"valid\":true}",
            "{\"code\":\" \\t\",\"valid\":true}", "{\"code\":\"en-US\",\"valid\":false}"})
    @DisplayName("语言代码缺失、空白或被判无效时不可用")
    void rejectsUnusableLanguage(String content) {
        assertFalse(LangProbeResponse.parse(content).ok());
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "null", "\"false\""})
    @DisplayName("源语言否定和空判定不跳过翻译")
    void preservesNegativeDecision(String decision) {
        SourceLanguageProbeResponse source = SourceLanguageProbeResponse.parse(
                "{\"sourceLang\":\"ja-JP\",\"same\":%s}".formatted(decision));
        assertEquals("ja-JP", source.sourceLang());
        assertEquals("null".equals(decision) ? null : Boolean.FALSE, source.same());
        assertFalse(source.isSame());
    }

    @Test
    @DisplayName("保留布尔字符串转换")
    void acceptsBooleanStrings() {
        assertTrue(LangProbeResponse.parse("{\"code\":\"en-US\",\"valid\":\"true\"}").ok());
        assertTrue(SourceLanguageProbeResponse.parse("{\"same\":\"true\"}").isSame());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", " \t null\r\n", "\u0000null\u0000"})
    @DisplayName("JSON 字面空值按探测失败回退")
    void jsonNullUsesFailureSentinels(String content) {
        assertEquals(new LangProbeResponse("", false), LangProbeResponse.parse(content));
        assertEquals(new SourceLanguageProbeResponse("", null), SourceLanguageProbeResponse.parse(content));
    }
}
