package top.sywyar.pixivdownload.core.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushLevel;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PushFormatConverter 协商与格式转换单元测试")
class PushFormatConverterTest {

    private final PushFormatConverter converter = new PushFormatConverter();

    // ---- 协商 negotiate ------------------------------------------------------------------

    @Test
    @DisplayName("协商：按通道优先级取第一个可达格式（Markdown 源 → 优先 CARD）")
    void negotiatePicksFirstReachableByPreference() {
        assertThat(converter.negotiate(List.of(PushFormat.CARD, PushFormat.PLAIN_TEXT), PushFormat.MARKDOWN))
                .isEqualTo(PushFormat.CARD);
        assertThat(converter.negotiate(List.of(PushFormat.HTML, PushFormat.PLAIN_TEXT), PushFormat.MARKDOWN))
                .isEqualTo(PushFormat.HTML);
    }

    @Test
    @DisplayName("协商：CARD 可达 ⟺ MARKDOWN 可达，HTML 源无法到 CARD 则降级到下一支持格式")
    void negotiateCardReachabilityFollowsMarkdown() {
        assertThat(converter.negotiate(List.of(PushFormat.CARD, PushFormat.PLAIN_TEXT), PushFormat.HTML))
                .isEqualTo(PushFormat.PLAIN_TEXT);
    }

    @Test
    @DisplayName("协商：HTML 源 → 仅支持 MARKDOWN 的位置不可达，回退到列表中的 PLAIN_TEXT")
    void negotiateHtmlToMarkdownUnreachable() {
        assertThat(converter.negotiate(List.of(PushFormat.MARKDOWN, PushFormat.PLAIN_TEXT), PushFormat.HTML))
                .isEqualTo(PushFormat.PLAIN_TEXT);
    }

    @Test
    @DisplayName("协商：空 / null 支持列表兜底为 PLAIN_TEXT")
    void negotiateEmptyFallsBackToPlainText() {
        assertThat(converter.negotiate(List.of(), PushFormat.MARKDOWN)).isEqualTo(PushFormat.PLAIN_TEXT);
        assertThat(converter.negotiate(null, PushFormat.MARKDOWN)).isEqualTo(PushFormat.PLAIN_TEXT);
    }

    // ---- 转换 render ---------------------------------------------------------------------

    @Test
    @DisplayName("转换：Markdown → 纯文本，剥离粗体与链接标记")
    void renderMarkdownToPlainText() {
        RenderedMessage rm = converter.render(
                PushMessage.markdown("", "**粗** [链接](http://x)", PushLevel.INFO), PushFormat.PLAIN_TEXT);
        assertThat(rm.format()).isEqualTo(PushFormat.PLAIN_TEXT);
        assertThat(rm.body()).isEqualTo("粗 链接");
    }

    @Test
    @DisplayName("转换：Markdown → HTML，粗体转 <b>")
    void renderMarkdownToHtml() {
        RenderedMessage rm = converter.render(
                PushMessage.markdown("", "**粗**", PushLevel.INFO), PushFormat.HTML);
        assertThat(rm.format()).isEqualTo(PushFormat.HTML);
        assertThat(rm.body()).isEqualTo("<b>粗</b>");
    }

    @Test
    @DisplayName("转换：Markdown → HTML，链接 URL 中的下划线不会被二次解析为斜体")
    void renderMarkdownLinkToHtmlKeepsHrefIntact() {
        RenderedMessage rm = converter.render(
                PushMessage.markdown("", "[作品](https://example.com/foo_bar_baz)", PushLevel.INFO),
                PushFormat.HTML);
        assertThat(rm.body()).isEqualTo("<a href=\"https://example.com/foo_bar_baz\">作品</a>");
    }

    @Test
    @DisplayName("转换：Cron 表达式里空格分隔的裸星号不被当作强调吞掉（→纯文本 / HTML / 透传 Markdown）")
    void renderCronAsterisksSurviveAcrossFormats() {
        String body = "触发方式：Cron：0 0 * * *";
        assertThat(converter.render(PushMessage.markdown("", body, PushLevel.INFO), PushFormat.PLAIN_TEXT).body())
                .isEqualTo("触发方式：Cron：0 0 * * *");
        assertThat(converter.render(PushMessage.markdown("", body, PushLevel.INFO), PushFormat.HTML).body())
                .isEqualTo("触发方式：Cron：0 0 * * *");
        // MARKDOWN identity 透传：原样交给厂商渲染器（钉钉 / 企微等），不被本框架改写。
        assertThat(converter.render(PushMessage.markdown("", body, PushLevel.INFO), PushFormat.MARKDOWN).body())
                .isEqualTo("触发方式：Cron：0 0 * * *");
    }

    @Test
    @DisplayName("转换：反斜杠转义的字面元字符在转纯文本 / HTML 时脱去反斜杠按字面输出，透传 Markdown 时保留供厂商解析")
    void renderBackslashEscapedLiteralsAreUnescaped() {
        String body = "Cron：0 0 \\* \\* \\* 路径 a\\_b\\_c";
        assertThat(converter.render(PushMessage.markdown("", body, PushLevel.INFO), PushFormat.PLAIN_TEXT).body())
                .isEqualTo("Cron：0 0 * * * 路径 a_b_c");
        assertThat(converter.render(PushMessage.markdown("", body, PushLevel.INFO), PushFormat.HTML).body())
                .isEqualTo("Cron：0 0 * * * 路径 a_b_c");
        assertThat(converter.render(PushMessage.markdown("", body, PushLevel.INFO), PushFormat.MARKDOWN).body())
                .isEqualTo(body);
    }

    @Test
    @DisplayName("转换：转义元字符嵌套在行内代码 / 链接内时正确还原，不泄漏哨兵占位符")
    void renderEscapedLiteralNestedInCodeOrLinkRestoresCleanly() {
        // `a\*b`：行内代码内的星号按字面输出，<code> 标签保留，且不残留 PUSHHTML 哨兵。
        RenderedMessage code = converter.render(
                PushMessage.markdown("", "`a\\*b`", PushLevel.INFO), PushFormat.HTML);
        assertThat(code.body()).isEqualTo("<code>a*b</code>");
        assertThat(code.body()).doesNotContain("PUSHHTML");
        // [a\_b](https://x)：链接文字内的下划线按字面输出，<a href> 保留，且不残留哨兵。
        RenderedMessage link = converter.render(
                PushMessage.markdown("", "[a\\_b](https://x)", PushLevel.INFO), PushFormat.HTML);
        assertThat(link.body()).isEqualTo("<a href=\"https://x\">a_b</a>");
        assertThat(link.body()).doesNotContain("PUSHHTML");
    }

    @Test
    @DisplayName("转换：转义不误伤真实的 **粗** / *斜*，仍正确剥离 / 转标签")
    void renderRealEmphasisStillConverts() {
        assertThat(converter.render(PushMessage.markdown("", "**粗** 与 *斜*", PushLevel.INFO),
                PushFormat.PLAIN_TEXT).body()).isEqualTo("粗 与 斜");
        assertThat(converter.render(PushMessage.markdown("", "**粗** 与 *斜*", PushLevel.INFO),
                PushFormat.HTML).body()).isEqualTo("<b>粗</b> 与 <i>斜</i>");
    }

    @Test
    @DisplayName("转换：纯文本 → HTML，转义特殊字符并保留换行")
    void renderPlainTextToHtml() {
        RenderedMessage rm = converter.render(
                PushMessage.text("", "a<b>\nc", PushLevel.INFO), PushFormat.HTML);
        assertThat(rm.body()).isEqualTo("a&lt;b&gt;\nc");
    }

    @Test
    @DisplayName("转换：HTML → 纯文本，去标签、<br> 转换行、反转义实体")
    void renderHtmlToPlainText() {
        RenderedMessage rm = converter.render(
                PushMessage.html("", "<b>x</b><br>y&amp;z", PushLevel.INFO), PushFormat.PLAIN_TEXT);
        assertThat(rm.body()).isEqualTo("x\ny&z");
    }

    @Test
    @DisplayName("转换：目标 CARD 时正文以 Markdown 内联承载（Markdown 源原样）")
    void renderCardCarriesMarkdownBody() {
        RenderedMessage rm = converter.render(
                PushMessage.markdown("标题", "**粗**", PushLevel.WARNING), PushFormat.CARD);
        assertThat(rm.format()).isEqualTo(PushFormat.CARD);
        assertThat(rm.title()).isEqualTo("标题");
        assertThat(rm.body()).isEqualTo("**粗**");
        assertThat(rm.level()).isEqualTo(PushLevel.WARNING);
    }

    @Test
    @DisplayName("转换：HTML 源 → CARD 不可达 Markdown，卡片正文降级为纯文本")
    void renderCardFromHtmlDegradesBody() {
        RenderedMessage rm = converter.render(
                PushMessage.html("", "<b>x</b>", PushLevel.INFO), PushFormat.CARD);
        assertThat(rm.format()).isEqualTo(PushFormat.CARD);
        assertThat(rm.body()).isEqualTo("x");
    }

    @Test
    @DisplayName("降级：目标不可达（HTML 源 → MARKDOWN）时尽力降级为纯文本仍产出")
    void renderUnreachableTargetDegradesToPlainText() {
        RenderedMessage rm = converter.render(
                PushMessage.html("", "<b>x</b>", PushLevel.INFO), PushFormat.MARKDOWN);
        assertThat(rm.format()).isEqualTo(PushFormat.PLAIN_TEXT);
        assertThat(rm.body()).isEqualTo("x");
    }
}
