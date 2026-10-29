package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pixiv 描述 HTML 归一化")
class PixivDescriptionHtmlTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "https://example.com/?q=hello world&x=1|https://example.com/?q=hello%20world&amp;x=1",
            "https://example.com/?q=&quot;hello&quot;|https://example.com/?q=%22hello%22",
            "/jump.php?https%3A%2F%2Fexample.com%2F%3Fq%3Dhello%20world|https://example.com/?q=hello%20world",
            "/jump.php?https%3A%2F%2Fexample.com%2F%3Fq%3D%22hello%22|https://example.com/?q=%22hello%22",
            "https://example.com/?q=hello%20world&amp;x=%22ok%22#part|https://example.com/?q=hello%20world&amp;x=%22ok%22#part",
            "https://example.com/a b?x=1&y=2|https://example.com/a%20b?x=1&amp;y=2",
            "https://example.com/?x=1&y=2#section one|https://example.com/?x=1&amp;y=2#section%20one",
            "https://example.com/bad path?q=hello world|https://example.com/bad%20path?q=hello%20world",
            "/jump.php?https%3A%2F%2Fexample.com%2Fa%20b|https://example.com/a%20b",
            "/jump.php?https%3A%2F%2Fexample.com%2Fa%20b%3Fq%3Dhello%20world%23section%20one|https://example.com/a%20b?q=hello%20world#section%20one",
            "/a b?q=hello world#section one|https://www.pixiv.net/a%20b?q=hello%20world#section%20one",
            "a b?q=hello world#section one|https://www.pixiv.net/a%20b?q=hello%20world#section%20one",
            "//example.com/a b?q=hello world#section one|https://example.com/a%20b?q=hello%20world#section%20one",
            "https://example.com/a&quot;b?x=&quot;ok&quot;#section&quot;one|https://example.com/a%22b?x=%22ok%22#section%22one",
            "https://example.com/a%20b?q=hello%20world#section%20one|https://example.com/a%20b?q=hello%20world#section%20one",
            "#section one|#section%20one",
            "#section?one two|#section?one%20two",
            "/a&#32;b?x=1&amp;y=2#section&#32;one|https://www.pixiv.net/a%20b?x=1&amp;y=2#section%20one",
            "https://example.com/a&lt;b&gt;`{c}^?q=&apos;quoted&apos;#section&lt;one&gt;`|https://example.com/a%3Cb%3E%60%7Bc%7D%5E?q=%27quoted%27#section%3Cone%3E%60",
            "https://[::1]/a b?x=1#section one|https://[::1]/a%20b?x=1#section%20one",
            "https://user:pass@example.com/a b?x=1#section one|https://user:pass@example.com/a%20b?x=1#section%20one",
            "/作者 作品?标签=猫 狗#一 二|https://www.pixiv.net/%E4%BD%9C%E8%80%85%20%E4%BD%9C%E5%93%81?%E6%A0%87%E7%AD%BE=%E7%8C%AB%20%E7%8B%97#%E4%B8%80%20%E4%BA%8C",
            "/a\u00a0b?q=x\u2028y#e😀f|https://www.pixiv.net/a%C2%A0b?q=x%E2%80%A8y#e%F0%9F%98%80f",
            "https://example.com/a[b]?q={x}`^#part#two|https://example.com/a%5Bb%5D?q=%7Bx%7D%60%5E#part#two",
            "https://example.com/?q=a\\b#c\\d|https://example.com/?q=a%5Cb#c%5Cd"
    })
    @DisplayName("路径、查询和片段中的可表示字符只编码一次，直接及跳转链接保留目标")
    void preservesUsableUrlComponents(String href, String target) {
        String expected = "<a href=\"" + target + "\" target=\"_blank\" rel=\"noopener noreferrer\">link</a>";
        assertThat(PixivDescriptionHtml.normalizeLinks("<a href=\"" + href + "\">link</a>"))
                .isEqualTo(expected);
        assertThat(PixivDescriptionHtml.normalizeLinks(expected)).isEqualTo(expected);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "https://example.com/#/works/1#notes|https://example.com/#/works/1#notes",
            "https://example.com/#/works/1%23notes|https://example.com/#/works/1%23notes",
            "//example.com/#/works/1#notes|https://example.com/#/works/1#notes",
            "/#/works/1#notes|https://www.pixiv.net/#/works/1#notes",
            "/#/works/1%23notes|https://www.pixiv.net/#/works/1%23notes",
            "pages/../#/works/1#notes|https://www.pixiv.net/#/works/1#notes",
            "#/works/1#notes|#/works/1#notes",
            "#/works/1%23notes|#/works/1%23notes",
            "/jump.php?https%3A%2F%2Fexample.com%2F%23%2Fworks%2F1%23notes|https://example.com/#/works/1#notes",
            "/jump.php?https%3A%2F%2Fexample.com%2F%23%2Fworks%2F1%2523notes|https://example.com/#/works/1%23notes",
            "https://example.com/a b?x=1&y=2#/works/1#notes space|https://example.com/a%20b?x=1&y=2#/works/1#notes%20space",
            "#/works/1#notes%23literal##tail|#/works/1#notes%23literal##tail"
    })
    @DisplayName("片段内字面井号与已有百分号转义保持区别，不合并不同路由目标")
    void preservesFragmentHashSyntax(String href, String target) {
        assertThat(PixivDescriptionHtml.resolvePixivHref(href)).isEqualTo(target);
        assertThat(PixivDescriptionHtml.resolvePixivHref(target)).isEqualTo(target);
        String input = "<a href=\"" + href.replace("&", "&amp;") + "\">link</a>";
        String expected = "<a href=\"" + target.replace("&", "&amp;")
                + "\" target=\"_blank\" rel=\"noopener noreferrer\">link</a>";
        assertThat(PixivDescriptionHtml.normalizeLinks(input)).isEqualTo(expected);
        assertThat(PixivDescriptionHtml.normalizeLinks(expected)).isEqualTo(expected);
    }

    @Test
    @DisplayName("属性实体中的井号保留路由分隔含义，百分号转义不解码")
    void preservesFragmentHashEntities() {
        assertThat(PixivDescriptionHtml.normalizeLinks("<a href='https://example.com/&#35;/works/1&#35;notes%23literal'>link</a>"))
                .isEqualTo("<a href=\"https://example.com/#/works/1#notes%23literal\" target=\"_blank\" rel=\"noopener noreferrer\">link</a>");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "https://example.com/?q=hello\u0001world", "https://exa mple.com/?q=hello world",
            "https://example.com/?q=%zz hello world", "https://example.com/a\u0001b?q=hello world",
            "https://example.com/#section\u0001one", "https://example.com/#section\u007fone", "#section\u0001one",
            "ht tp://example.com/a b", "//exa mple.com/a b", "https://exa\"mple.com/a b",
            "https://example.com/a\\b", "https://exa\\mple.com/a b", "https:exa mple.com/a b",
            "https://example.com/a\u0085b", "https://example.com/a\ud800b", "https://example.com/#a\udc00b",
            "https://[::1/a b", "https://example.com/%zz#section one", "https://example.com/#section%zz",
            "/jump.php?javascript%3Aalert%28%22hello%20world%22%29",
            "/jump.php?https%3A%2F%2Fexa%20mple.com%2Fa%20b",
            "https://example.com/#/works/1#notes%zz", "#/works/1#notes\u0001bad",
            "https://exa mple.com/#/works/1#notes", "javascript:alert(1)#/works/1#notes",
            "/jump.php?javascript%3Aalert%281%29%23%2Fworks%2F1%23notes"
    })
    @DisplayName("组件编码不放行控制字符、损坏的协议主机地址或危险跳转")
    void stillRejectsInvalidUrlTargets(String href) {
        assertThat(PixivDescriptionHtml.resolvePixivHref(href)).isEqualTo("#");
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    @DisplayName("长路径查询片段的编码保持线性处理和幂等性")
    void handlesLongUrlComponents() {
        String raw = "https://example.com/" + "a b/".repeat(16000)
                + "?q=" + "c d".repeat(16000) + "#" + "e f".repeat(16000);
        String expected = "https://example.com/" + "a%20b/".repeat(16000)
                + "?q=" + "c%20d".repeat(16000) + "#" + "e%20f".repeat(16000);
        assertThat(PixivDescriptionHtml.resolvePixivHref(raw)).isEqualTo(expected);
        assertThat(PixivDescriptionHtml.resolvePixivHref(expected)).isEqualTo(expected);
        String repeatedHashes = expected + "#notes%23literal".repeat(16000);
        assertThat(PixivDescriptionHtml.resolvePixivHref(repeatedHashes)).isEqualTo(repeatedHashes);
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    @DisplayName("大量未闭合标签与引号线性处理且仍识别后续完整标签")
    void handlesUnclosedTagsWithoutRepeatedSuffixScanning() {
        String raw = "&amp;" + "<a ".repeat(16000);
        String escaped = "&amp;" + "&lt;a ".repeat(16000);
        assertThat(PixivDescriptionHtml.normalizeLinks(raw)).isEqualTo(escaped);
        assertThat(PixivDescriptionHtml.normalizeLinks(escaped)).isEqualTo(escaped);
        String brokenQuote = "<a title='" + "<a ".repeat(16000) + ">tail";
        assertThat(PixivDescriptionHtml.normalizeLinks(brokenQuote)).isEqualTo(
                "&lt;a title='<a href=\"\" target=\"_blank\" rel=\"noopener noreferrer\">tail");
    }

    @Test
    @DisplayName("实体只解码一层，重复清理不改变正文或作者有意展示的实体文本")
    void preservesEntitySemanticsAcrossRepeatedNormalization() {
        String input = "A&#39;s note&#44; &quot;quoted&quot; &copy; &#x1F600; &NotEqualTilde;"
                + " &amp;#39; &amp;quot; &unknown; & bare < 2";
        String expected = "A's note, \"quoted\" © 😀 ≂̸ &amp;#39; &amp;quot; &amp;unknown; &amp; bare &lt; 2";
        assertThat(PixivDescriptionHtml.normalizeLinks(input)).isEqualTo(expected);
        assertThat(PixivDescriptionHtml.normalizeLinks(expected)).isEqualTo(expected);
    }

    @Test
    @DisplayName("编码的标签只作为文字显示，不能变成可执行标签")
    void keepsEncodedMarkupAsText() {
        String html = "&lt;img src=x onerror=alert(1)&gt; &#60;script&#62;bad&#60;/script&#62;";
        String safe = "&lt;img src=x onerror=alert(1)&gt; &lt;script&gt;bad&lt;/script&gt;";
        assertThat(PixivDescriptionHtml.normalizeLinks(html)).isEqualTo(safe);
        assertThat(PixivDescriptionHtml.normalizeLinks(safe)).isEqualTo(safe);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "https://example.com/?a=1&b=2", "https://example.com/?a=1&amp;b=2",
            "https&colon;//example.com/?a=1&#38;b=2",
            "/jump.php?https%3A%2F%2Fexample.com%2F%3Fa%3D1%26b%3D2"
    })
    @DisplayName("多参数链接只转义一次，跳转解包后保持相同目标")
    void escapesHrefOnce(String href) {
        String expected = "<a href=\"https://example.com/?a=1&amp;b=2\" target=\"_blank\" rel=\"noopener noreferrer\">link</a>";
        assertThat(PixivDescriptionHtml.normalizeLinks("<a href=\"" + href + "\">link</a>")).isEqualTo(expected);
        assertThat(PixivDescriptionHtml.normalizeLinks(expected)).isEqualTo(expected);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "javascript&colon;alert(1)", "&#106;avascript:alert(1)", "java&#x09;script:alert(1)",
            "java&NewLine;script:alert(1)", "data&colon;text/html,bad", "vbscript:bad",
            "/jump.php?javascript%3Aalert(1)"
    })
    @DisplayName("实体与控制字符不能绕过链接协议校验")
    void rejectsEncodedUnsafeLinks(String href) {
        assertThat(PixivDescriptionHtml.normalizeLinks("<a href=\"" + href + "\">bad</a>"))
                .contains("href=\"#\"");
    }

    @Test
    @DisplayName("属性中的引号和尖括号不产生新属性，伪 href 不覆盖真实 href")
    void respectsAttributeBoundaries() {
        String normalized = PixivDescriptionHtml.normalizeLinks(
                "<a title=' href=\"https://wrong.example\" >' href='/users/1'>ok</a>");
        assertThat(normalized).contains("href=\"https://www.pixiv.net/users/1\"").doesNotContain("wrong.example");
    }

    @Test
    @DisplayName("缺分号与非法数字实体遵循 HTML 文本和属性各自规则")
    void handlesLegacyAndInvalidReferences() {
        assertThat(PixivDescriptionHtml.normalizeLinks("&copy test &#39 &#x80; &#0; &#xD800; &#999999999999999;"))
                .isEqualTo("© test ' € � � �");
        assertThat(PixivDescriptionHtml.normalizeLinks("<a href='https://example.com/?x=1&copy=2'>x</a>"))
                .contains("?x=1&amp;copy=2");
    }

    @Test
    @DisplayName("异常深度的跳转和长属性不会耗尽调用栈")
    void boundsJumpUnwrappingAndHandlesLongAttributes() {
        String link = "/jump.php?".repeat(40) + "https://example.com/";
        assertThat(PixivDescriptionHtml.resolvePixivHref(link)).isEqualTo("#");
        String input = "<a title='" + "x".repeat(262144) + "' href='/users/1'>ok</a>";
        assertThat(PixivDescriptionHtml.normalizeLinks(input)).contains("href=\"https://www.pixiv.net/users/1\"");
        assertThat(PixivDescriptionHtml.normalizeLinks("<a href='https://example.com/%zz'>bad</a>"))
                .contains("href=\"#\"");
    }

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
