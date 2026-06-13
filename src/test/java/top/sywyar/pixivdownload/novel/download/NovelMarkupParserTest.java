package top.sywyar.pixivdownload.novel.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NovelMarkupParser tests")
class NovelMarkupParserTest {

    @Test
    @DisplayName("splitChapters：按 [chapter:] 切片，保留前置无题段，无标记时整体一段")
    void splitChapters() {
        var segs = NovelMarkupParser.splitChapters(
                "序章内容\n[chapter:第一章]\n正文一\n[chapter:第二章]\n正文二");
        assertThat(segs).hasSize(3);
        assertThat(segs.get(0).title()).isNull();
        assertThat(segs.get(0).raw()).contains("序章内容");
        assertThat(segs.get(1).title()).isEqualTo("第一章");
        assertThat(segs.get(1).raw()).contains("正文一").doesNotContain("[chapter:");
        assertThat(segs.get(2).title()).isEqualTo("第二章");

        var none = NovelMarkupParser.splitChapters("没有任何章节标记");
        assertThat(none).hasSize(1);
        assertThat(none.get(0).title()).isNull();

        var leadBlank = NovelMarkupParser.splitChapters("\n\n[chapter:开篇]\n内容");
        assertThat(leadBlank).hasSize(1);
        assertThat(leadBlank.get(0).title()).isEqualTo("开篇");
    }

    @Test
    @DisplayName("TXT 模式：剥离 Pixiv 标记，仅保留 ruby 基词与跳转 URL")
    void txtStripsMarkup() {
        String raw = "本文 [[rb:漢字 > かんじ]] 与 [[jumpuri:站点 > https://example.com]] 与 [jump:5]\n"
                + "[uploadedimage:42] 和 [pixivimage:1234-2]";
        String txt = NovelMarkupParser.render(raw, NovelMarkupParser.Format.TXT);
        assertThat(txt).contains("漢字");
        assertThat(txt).doesNotContain("[[rb");
        assertThat(txt).contains("站点 (https://example.com)");
        assertThat(txt).doesNotContain("[jump:5]");
        assertThat(txt).contains("[uploadedimage:42]");
        assertThat(txt).contains("[pixivimage:1234-2]");
    }

    @Test
    @DisplayName("TXT 模式：[chapter:..] 转为带框标题，[newpage] 产生空行")
    void txtChapterAndNewpage() {
        String raw = "前文\n[chapter:第一章 序]\n章节内容\n[newpage]\n下一页";
        String txt = NovelMarkupParser.render(raw, NovelMarkupParser.Format.TXT);
        assertThat(txt).contains("【第一章 序】");
        assertThat(txt.indexOf("章节内容")).isGreaterThan(txt.indexOf("【第一章 序】"));
        assertThat(txt).contains("下一页");
    }

    @Test
    @DisplayName("HTML 模式：ruby 渲染为 <ruby> 标签；jumpuri 渲染为 <a>；换页拆分 <section>")
    void htmlSemanticRendering() {
        String raw = "[[rb:漢字 > かんじ]]\n"
                + "[[jumpuri:链接 > https://example.com/?a=1&b=2]]\n"
                + "[chapter:章一]\n"
                + "[newpage]\n"
                + "下一页";
        String html = NovelMarkupParser.render(raw, NovelMarkupParser.Format.HTML);
        assertThat(html).contains("<ruby>漢字<rt>かんじ</rt></ruby>");
        assertThat(html).contains("<a href=\"https://example.com/?a=1&amp;b=2\"");
        assertThat(html).contains("<h2 class=\"novel-chapter\">章一</h2>");
        assertThat(html).contains("<section class=\"novel-page\">");
        assertThat(html).contains("</section>");
        // newpage 至少切出两个 section
        long sectionCount = html.split("<section ", -1).length - 1;
        assertThat(sectionCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("XHTML 模式：epub:type 命名空间前缀存在；img placeholder 存在")
    void xhtmlEpubTypeAndImage() {
        String raw = "[uploadedimage:99]\n[pixivimage:777]";
        String xhtml = NovelMarkupParser.render(raw, NovelMarkupParser.Format.XHTML);
        assertThat(xhtml).contains("epub:type=\"chapter\"");
        assertThat(xhtml).contains("data-uploaded-image=\"99\"");
        assertThat(xhtml).contains("data-pixiv-image=\"777\"");
    }

    @Test
    @DisplayName("图片占位符支持调用方传入本地化文案")
    void imageLabelsAreProvidedByCaller() {
        NovelMarkupParser.ImageLabels labels = new NovelMarkupParser.ImageLabels() {
            @Override public String uploadedImage(String id) {
                return "上传图 #" + id;
            }

            @Override public String pixivImage(String id) {
                return "Pixiv image #" + id;
            }
        };

        String txt = NovelMarkupParser.render("[uploadedimage:42] [pixivimage:1234-2]",
                NovelMarkupParser.Format.TXT, labels);
        String html = NovelMarkupParser.render("[uploadedimage:42]",
                NovelMarkupParser.Format.HTML, NovelMarkupParser.ImageResolver.NONE, labels);

        assertThat(txt).contains("上传图 #42");
        assertThat(txt).contains("Pixiv image #1234-2");
        assertThat(html).contains("上传图 #42");
    }

    @Test
    @DisplayName("HTML 转义：< > & 字符全部转义；不会破坏标签")
    void htmlEscapesUnsafeChars() {
        String raw = "<script>alert(\"x\")</script> & 普通文本";
        String html = NovelMarkupParser.render(raw, NovelMarkupParser.Format.HTML);
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&amp;");
    }

    @Test
    @DisplayName("空输入安全处理")
    void handlesEmptyAndNullInput() {
        assertThat(NovelMarkupParser.render(null, NovelMarkupParser.Format.TXT)).isEqualTo("");
        assertThat(NovelMarkupParser.render("", NovelMarkupParser.Format.HTML)).contains("<section");
    }

    @Test
    @DisplayName("单个换行在 HTML 中转 <br />，双换行分段")
    void singleNewlineBecomesBr() {
        String raw = "第一行\n第二行\n\n第二段";
        String html = NovelMarkupParser.render(raw, NovelMarkupParser.Format.HTML);
        assertThat(html).contains("第一行<br />第二行");
        assertThat(html.split("<p>", -1).length - 1).isEqualTo(2);
    }
}
