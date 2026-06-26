package top.sywyar.pixivdownload.plugin.catalog.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogLink} 单测：展示外链净化——放行 http/https（trim、保留查询串），挡 javascript / data / file / 无 scheme /
 * 无主机 / 空。
 */
@DisplayName("CatalogLink 受控展示外链净化")
class CatalogLinkTest {

    @Test
    @DisplayName("http / https 放行（trim、保留查询串）")
    void allowsHttpAndHttps() {
        assertThat(CatalogLink.sanitizeHttpUrl("https://example.com/p?a=1")).isEqualTo("https://example.com/p?a=1");
        assertThat(CatalogLink.sanitizeHttpUrl("  http://example.com  ")).isEqualTo("http://example.com");
        assertThat(CatalogLink.isHttpUrl("https://github.com/Sywyar/PixivDownloader")).isTrue();
    }

    @Test
    @DisplayName("注入 scheme（javascript / data / file）→ null")
    void blocksDangerousSchemes() {
        assertThat(CatalogLink.sanitizeHttpUrl("javascript:alert(1)")).isNull();
        assertThat(CatalogLink.sanitizeHttpUrl("JavaScript:alert(1)")).isNull();
        assertThat(CatalogLink.sanitizeHttpUrl("data:text/html,<script>1</script>")).isNull();
        assertThat(CatalogLink.sanitizeHttpUrl("file:///etc/passwd")).isNull();
        assertThat(CatalogLink.sanitizeHttpUrl("ftp://example.com")).isNull();
    }

    @Test
    @DisplayName("无 scheme / 无主机 / 空 / null → null")
    void blocksMalformed() {
        assertThat(CatalogLink.sanitizeHttpUrl("/relative/path")).isNull();
        assertThat(CatalogLink.sanitizeHttpUrl("https://")).isNull();
        assertThat(CatalogLink.sanitizeHttpUrl("not a url")).isNull();
        assertThat(CatalogLink.sanitizeHttpUrl("   ")).isNull();
        assertThat(CatalogLink.sanitizeHttpUrl(null)).isNull();
    }
}
