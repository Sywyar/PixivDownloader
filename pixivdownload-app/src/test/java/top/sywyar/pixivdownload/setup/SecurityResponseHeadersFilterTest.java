package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("统一浏览器安全响应头")
class SecurityResponseHeadersFilterTest {

    private final SecurityResponseHeadersFilter filter = new SecurityResponseHeadersFilter();
    private final FilterChain chain = (request, response) -> response.setContentType("text/html");

    @Test
    @DisplayName("普通页面禁止嵌入并限制脚本和浏览器能力")
    void normalNavigationUsesStrictHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'", "object-src 'none'", "base-uri 'self'",
                        "script-src 'self'", "script-src-attr 'none'", "frame-ancestors 'none'",
                        "https://layout-survey.sywyar.top", "https://us.i.posthog.com",
                        "https://eu.i.posthog.com");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Permissions-Policy"))
                .contains("camera=()", "geolocation=()", "microphone=()", "payment=()", "usb=()");
        assertThat(response.getHeaderValues("Vary")).contains("Sec-Fetch-Dest");
    }

    @Test
    @DisplayName("同源 iframe 导航保留站内信嵌入能力")
    void iframeNavigationAllowsOnlySameOriginParent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/embedded.html");
        request.addHeader("Sec-Fetch-Dest", "iframe");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("frame-ancestors 'self'")
                .doesNotContain("frame-ancestors 'none'");
    }
}
