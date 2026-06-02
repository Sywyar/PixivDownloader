package top.sywyar.pixivdownload.common;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.net.http.HttpRequest;

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
    @DisplayName("JDK HttpRequest 直连请求也应复用标准 Pixiv document 请求头")
    void documentHeadersShouldApplyToJdkHttpRequestBuilder() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(PixivRequestHeaders.PIXIV_HOME));

        PixivRequestHeaders.applyDocument(builder, null);
        HttpRequest request = builder.build();

        assertThat(request.headers().firstValue(HttpHeaders.USER_AGENT)).hasValue(PixivRequestHeaders.USER_AGENT);
        assertThat(request.headers().firstValue(HttpHeaders.REFERER)).hasValue(PixivRequestHeaders.PIXIV_HOME);
        assertThat(request.headers().firstValue(HttpHeaders.ACCEPT).orElse("")).contains("text/html");
        assertThat(request.headers().firstValue("Sec-Fetch-Mode")).hasValue("navigate");
        assertThat(request.headers().firstValue(HttpHeaders.COOKIE)).isEmpty();
    }

    @Test
    @DisplayName("Apache HttpClient 直连请求也应复用标准 Pixiv ajax 请求头")
    void ajaxHeadersShouldApplyToApacheHttpRequest() {
        HttpGet request = new HttpGet("https://www.pixiv.net/ajax/illust/123");

        PixivRequestHeaders.applyAjax(request, "PHPSESSID=secret");

        assertThat(request.getFirstHeader(HttpHeaders.USER_AGENT).getValue()).isEqualTo(PixivRequestHeaders.USER_AGENT);
        assertThat(request.getFirstHeader(HttpHeaders.REFERER).getValue()).isEqualTo(PixivRequestHeaders.PIXIV_HOME);
        assertThat(request.getFirstHeader(HttpHeaders.ACCEPT).getValue()).contains("application/json");
        assertThat(request.getFirstHeader("X-Requested-With").getValue()).isEqualTo("XMLHttpRequest");
        assertThat(request.getFirstHeader(HttpHeaders.COOKIE).getValue()).isEqualTo("PHPSESSID=secret");
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
