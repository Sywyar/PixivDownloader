package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarkdownEscape Markdown 字面转义单元测试")
class MarkdownEscapeTest {

    @Test
    @DisplayName("转义全部内联元字符：反引号 / 星号 / 下划线 / 方括号 / 反斜杠")
    void escapesAllInlineSpecials() {
        assertThat(MarkdownEscape.escape("0 0 * * *")).isEqualTo("0 0 \\* \\* \\*");
        assertThat(MarkdownEscape.escape("novel_series_12")).isEqualTo("novel\\_series\\_12");
        assertThat(MarkdownEscape.escape("`code`")).isEqualTo("\\`code\\`");
        assertThat(MarkdownEscape.escape("[链接](x)")).isEqualTo("\\[链接\\](x)");
        assertThat(MarkdownEscape.escape("a\\b")).isEqualTo("a\\\\b");
    }

    @Test
    @DisplayName("不转义块级 / 行内中性字符：井号 / 大于号 / 连字符 / 括号 / 感叹号原样保留")
    void leavesBlockAndNeutralCharsIntact() {
        // 日期时间与 URL 里大量出现的 - : . ( ) ! 不被转义，避免无谓的可见反斜杠。
        assertThat(MarkdownEscape.escape("2026-05-27 12:00:00")).isEqualTo("2026-05-27 12:00:00");
        assertThat(MarkdownEscape.escape("https://www.pixiv.net/artworks/123"))
                .isEqualTo("https://www.pixiv.net/artworks/123");
        assertThat(MarkdownEscape.escape("# > - 1. ! ~ |")).isEqualTo("# > - 1. ! ~ |");
    }

    @Test
    @DisplayName("null / 空串安全：返回空串 / 原值")
    void handlesNullAndEmpty() {
        assertThat(MarkdownEscape.escape(null)).isEmpty();
        assertThat(MarkdownEscape.escape("")).isEmpty();
        assertThat(MarkdownEscape.escape("纯文本无元字符")).isEqualTo("纯文本无元字符");
    }
}
