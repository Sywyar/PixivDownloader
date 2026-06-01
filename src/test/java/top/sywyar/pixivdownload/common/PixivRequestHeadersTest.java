package top.sywyar.pixivdownload.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PixivRequestHeaders 单元测试")
class PixivRequestHeadersTest {

    @Test
    @DisplayName("ajax 请求头应使用完整浏览器 UA 并携带 Cookie")
    void ajaxHeadersShouldUseFullBrowserUserAgent() {
        HttpHeaders headers = PixivRequestHeaders.ajax("PHPSESSID=secret");

        assertThat(headers.getFirst(HttpHeaders.USER_AGENT))
                .contains("Mozilla/5.0")
                .contains("Chrome/")
                .contains("Safari/");
        assertThat(headers.getFirst(HttpHeaders.ACCEPT)).contains("application/json");
        assertThat(headers.getFirst(HttpHeaders.COOKIE)).isEqualTo("PHPSESSID=secret");
        assertThat(headers.getFirst("Sec-Fetch-Mode")).isEqualTo("cors");
    }

    @Test
    @DisplayName("兜底逻辑应覆盖 Pixiv 请求中的旧 UA")
    void defaultsShouldOverrideOldUserAgentForPixivHosts() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        PixivRequestHeaders.applyBrowserDefaults(
                headers,
                URI.create("https://www.pixiv.net/ajax/illust/123"),
                HttpMethod.GET);

        assertThat(headers.getFirst(HttpHeaders.USER_AGENT)).isEqualTo(PixivRequestHeaders.USER_AGENT);
        assertThat(headers.getFirst(HttpHeaders.ACCEPT)).contains("application/json");
        assertThat(headers.getFirst(HttpHeaders.REFERER)).isEqualTo(PixivRequestHeaders.PIXIV_HOME);
    }

    @Test
    @DisplayName("兜底逻辑不应修改非 Pixiv 请求")
    void defaultsShouldLeaveNonPixivHostsUntouched() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "PixivDownload/test");

        PixivRequestHeaders.applyBrowserDefaults(
                headers,
                URI.create("https://api.example.com/v1/test"),
                HttpMethod.GET);

        assertThat(headers.getFirst(HttpHeaders.USER_AGENT)).isEqualTo("PixivDownload/test");
        assertThat(headers.getFirst(HttpHeaders.ACCEPT)).isNull();
    }
}
