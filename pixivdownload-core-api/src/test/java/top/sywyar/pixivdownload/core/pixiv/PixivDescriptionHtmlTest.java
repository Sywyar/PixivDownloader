package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pixiv 描述 HTML 归一化")
class PixivDescriptionHtmlTest {

    @Test
    @DisplayName("Pixiv 跳转链接应解包为外部目标")
    void unwrapsPixivJumpLinks() {
        String html = """
                Amazon <a href="/jump.php?https%3A%2F%2Fwww.amazon.co.jp%2Fdp%2F4866998911" target="_blank">link</a>
                """;

        String normalized = PixivDescriptionHtml.normalizeLinks(html);

        assertThat(normalized).contains("href=\"https://www.amazon.co.jp/dp/4866998911\"");
        assertThat(normalized).doesNotContain("href=\"/jump.php");
        assertThat(normalized).doesNotContain("pixiv.net/jump.php");
    }

    @Test
    @DisplayName("普通相对链接应按 Pixiv 站点解析")
    void normalizesRelativePixivLinks() {
        String html = "<a href=\"/novel/show.php?id=123\">novel</a>";

        String normalized = PixivDescriptionHtml.normalizeLinks(html);

        assertThat(normalized).contains("href=\"https://www.pixiv.net/novel/show.php?id=123\"");
    }

    @Test
    @DisplayName("协议相对的 Pixiv 跳转链接也应解包")
    void unwrapsProtocolRelativePixivJumpLinks() {
        String html = "<a href=\"//www.pixiv.net/jump.php?https%3A%2F%2Fexample.com%2Fbook\">book</a>";

        String normalized = PixivDescriptionHtml.normalizeLinks(html);

        assertThat(normalized).contains("href=\"https://example.com/book\"");
        assertThat(normalized).doesNotContain("pixiv.net/jump.php");
    }

    @Test
    @DisplayName("绝对链接与片段链接应保留")
    void preservesAbsoluteAndFragmentLinks() {
        String html = """
                <a href="https://example.com/path?q=1">abs</a>
                <a href="#section">fragment</a>
                """;

        String normalized = PixivDescriptionHtml.normalizeLinks(html);

        assertThat(normalized).contains("href=\"https://example.com/path?q=1\"");
        assertThat(normalized).contains("href=\"#section\"");
    }

    @Test
    @DisplayName("脚本类 href 协议应被中和")
    void neutralizesUnsafeProtocols() {
        String html = "<a href=\"javascript:alert(1)\">bad</a>";

        assertThat(PixivDescriptionHtml.normalizeLinks(html)).contains("href=\"#\"");
    }

    @Test
    @DisplayName("不安全标签与事件属性应被移除")
    void removesUnsafeTagsAndAttributes() {
        String html = """
                <img src=x onerror=alert(1)><a href="/users/1" onclick="alert(1)">safe</a><script>alert(2)</script>
                """;

        String normalized = PixivDescriptionHtml.normalizeLinks(html);

        assertThat(normalized).doesNotContain("<img");
        assertThat(normalized).doesNotContain("<script");
        assertThat(normalized).doesNotContain("onclick");
        assertThat(normalized).contains("href=\"https://www.pixiv.net/users/1\"");
        assertThat(normalized).contains("target=\"_blank\"");
    }

    @Test
    @DisplayName("纯文本尖括号应被转义")
    void escapesPlainTextAngleBrackets() {
        String html = "1 < 2 & <b>not bold</b>";

        String normalized = PixivDescriptionHtml.normalizeLinks(html);

        assertThat(normalized).contains("1 &lt; 2 &amp; not bold");
        assertThat(normalized).doesNotContain("<b>");
    }

    @Test
    @DisplayName("data-href 等自定义属性不得被误判为 href")
    void doesNotMatchHrefSubstringsInOtherAttributes() {
        String html = "<a data-href=\"https://evil.com\" href=\"/users/1\">ok</a>";

        String normalized = PixivDescriptionHtml.normalizeLinks(html);

        assertThat(normalized).contains("href=\"https://www.pixiv.net/users/1\"");
        assertThat(normalized).doesNotContain("evil.com");
    }
}
