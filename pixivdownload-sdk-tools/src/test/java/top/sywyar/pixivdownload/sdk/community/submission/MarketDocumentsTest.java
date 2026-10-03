package top.sywyar.pixivdownload.sdk.community.submission;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.sdk.community.content.MarketDocuments;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketDocumentsTest {
    @TempDir Path root;

    @Test @DisplayName("Markdown 保留表格与引用图片并只渲染已验证资源")
    void markdown() {
        var input = bytes("# Test\n\n| A | B |\n|---|---|\n| One | Two |\n\n![alt][pic]\n\n[pic]: ../images/test.png\n");
        assertThat(MarketDocuments.images(input, "markdown")).containsExactly("../images/test.png");
        var page = Jsoup.parse(MarketDocuments.render(input, "markdown", Map.of("../images/test.png", "data:image/png;base64,AA==")));
        assertThat(page.select("h1").text()).isEqualTo("Test");
        assertThat(page.select("table tbody td")).hasSize(2);
        assertThat(page.selectFirst("img").attr("src")).isEqualTo("data:image/png;base64,AA==");
        assertThat(page.selectFirst("img").attr("alt")).isEqualTo("alt");
    }

    @Test @DisplayName("HTML 主动内容和未登记网络资源不能进入安全文档")
    void html() {
        var input = bytes("<base href='https://evil.test'><script>alert(document.cookie)</script>"
                + "<style>@import 'https://evil.test/a';</style><iframe src='https://evil.test'></iframe>"
                + "<form><input><img src='hidden.png'></form><svg><a href='javascript:alert(1)'>x</a></svg>"
                + "<h2 onclick='alert(1)' style='background:url(https://evil.test)'>Title</h2>"
                + "<img src='https://evil.test/a' srcset='https://evil.test/b 2x' onerror='alert(1)' alt='Missing'>"
                + "<a href='javascript:alert(1)'>bad</a><a href='https://example.test/help'>help</a>");
        assertThat(MarketDocuments.images(input, "html")).containsExactly("https://evil.test/a");
        var page = Jsoup.parse(MarketDocuments.render(input, "html", Map.of()));
        assertThat(page.select("script,style,iframe,form,input,svg,base,[onclick],[onerror],[style],[srcset],[src]")).isEmpty();
        assertThat(page.select("a[href]")).hasSize(1);
        assertThat(page.selectFirst("a[href]").attr("rel")).contains("noopener", "noreferrer");
        assertThat(page.select("h2").text()).isEqualTo("Title");
    }

    @Test @DisplayName("文档读取绑定指定源码根并拒绝越界与非法编码")
    void files() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/README.md"), "# 冻结内容", StandardCharsets.UTF_8);
        assertThat(MarketDocumentFiles.inspect(MarketDocumentFiles.read(root, "docs/README.md", 1024), "markdown").text())
                .isEqualTo("# 冻结内容");
        assertThatThrownBy(() -> MarketDocumentFiles.read(root, "../README.md", 1024)).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> MarketDocumentFiles.read(root, "docs/README.md", 1)).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> MarketDocuments.images(new byte[] {(byte) 0xff}, "html")).isInstanceOf(ContractException.class);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test @DisplayName("重复引用图片不能无界放大净化后的文档")
    void renderedBudget() {
        assertThatThrownBy(() -> MarketDocuments.render(bytes("<img src='image.png'>".repeat(10)), "html",
                Map.of("image.png", "data:image/png;base64," + "A".repeat(2 * 1024 * 1024))))
                .isInstanceOf(ContractException.class);
    }
}
