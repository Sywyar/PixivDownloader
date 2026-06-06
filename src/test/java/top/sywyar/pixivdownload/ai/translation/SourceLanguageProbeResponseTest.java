package top.sywyar.pixivdownload.ai.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("源语言探测响应 JSON 解析")
class SourceLanguageProbeResponseTest {

    @Test
    @DisplayName("解析标准 JSON：同语言判定为 true")
    void parseSameTrue() {
        SourceLanguageProbeResponse r = SourceLanguageProbeResponse.parse(
                "{\"sourceLang\":\"en-US\",\"same\":true}");
        assertEquals("en-US", r.sourceLang());
        assertTrue(r.isSame());
    }

    @Test
    @DisplayName("解析标准 JSON：不同语言判定为 false")
    void parseSameFalse() {
        SourceLanguageProbeResponse r = SourceLanguageProbeResponse.parse(
                "{\"sourceLang\":\"ja-JP\",\"same\":false}");
        assertEquals("ja-JP", r.sourceLang());
        assertFalse(r.isSame());
    }

    @Test
    @DisplayName("容忍代码围栏 / 外层多余文字：截取首个对象解析")
    void parseWrappedJson() {
        SourceLanguageProbeResponse r = SourceLanguageProbeResponse.parse(
                "```json\n{\"sourceLang\":\"zh-CN\",\"same\":true}\n```");
        assertTrue(r.isSame());
    }

    @Test
    @DisplayName("空内容 / 无法解析 / 缺 same 字段时按无法判定处理（isSame 为 false）")
    void unparseableTreatedAsUndecided() {
        assertFalse(SourceLanguageProbeResponse.parse("").isSame());
        assertFalse(SourceLanguageProbeResponse.parse("not json at all").isSame());
        assertFalse(SourceLanguageProbeResponse.parse("{\"sourceLang\":\"en-US\"}").isSame());
    }
}
