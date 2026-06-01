package top.sywyar.pixivdownload.novel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("小说翻译分段拆分")
class NovelTranslationServiceSegmentTest {

    @Test
    @DisplayName("分段字数 <=0：整篇作为单个分段")
    void wholeTextWhenNoSplit() {
        String raw = "第一段\n第二段\n第三段";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 0);
        assertEquals(1, segments.size());
        assertEquals(raw, segments.get(0));
    }

    @Test
    @DisplayName("按段落累积到阈值切分，且以 \\n 拼接可精确还原原文")
    void splitsOnLineBoundariesAndReconstructs() {
        String raw = "para1\npara2\npara3\npara4";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 6);
        assertTrue(segments.size() >= 2, "应被切成多个分段");
        // 仅在换行处切分：原样拼接必须等于原文（恒等翻译下无损）
        assertEquals(raw, String.join("\n", segments));
    }

    @Test
    @DisplayName("无换行的超长单行：不在行内截断，作为一个分段")
    void singleLongLineStaysOneSegment() {
        String raw = "这是一整段没有任何换行的很长的文本用于验证不会被从中间切断";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 5);
        assertEquals(1, segments.size());
        assertEquals(raw, segments.get(0));
    }

    @Test
    @DisplayName("分段边界落在空行后：拼接仍精确还原原文（不丢空行）")
    void preservesBlankLinesAtSegmentBoundaries() {
        // 阈值 1 字让 "a" 立刻成为单独一段；下一行是空行（曾被吞掉），再下一行是 "b"
        String raw = "a\n\nb";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 1);
        assertEquals(raw, String.join("\n", segments));
    }

    @Test
    @DisplayName("尾随换行不丢：a\\n 拼回仍为 a\\n")
    void preservesTrailingNewline() {
        String raw = "a\n";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 1);
        assertEquals(raw, String.join("\n", segments));
    }
}
