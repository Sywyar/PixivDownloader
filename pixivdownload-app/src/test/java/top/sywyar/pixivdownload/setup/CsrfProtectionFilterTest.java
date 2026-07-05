package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("上传写操作 CSRF 同源校验")
class CsrfProtectionFilterTest {

    @Mock
    private AppLocaleResolver localeResolver;
    @Mock
    private AppMessages messages;
    @Mock
    private FilterChain filterChain;

    private CsrfProtectionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CsrfProtectionFilter(localeResolver, messages);
        lenient().when(localeResolver.resolveLocale(any())).thenReturn(Locale.CHINA);
        lenient().when(messages.getOrDefault(nullable(Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    @ParameterizedTest
    @CsvSource({
            "POST,/api/plugins/install",
            "POST,/api/plugins/demo/start",
            "POST,/api/plugin-market/official/demo/1.0.0/install",
            "POST,/api/collections/7/icon",
            "DELETE,/api/collections/7/icon",
            "POST,/api/narration/cast/voice/reference",
            "DELETE,/api/narration/cast/voice/reference",
            "POST,/api/narration/cast/voice/reference/generate"
    })
    @DisplayName("受保护上传和安装写入口缺少来源信号时返回 403")
    void protectedWriteRejectsMissingOriginAndReferer(String method, String path) throws Exception {
        MockHttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Request origin verification failed");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("同源 Origin 放行上传写请求")
    void sameOriginHeaderAllowsProtectedWrite() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/collections/7/icon");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("缺少 Origin 时同源 Referer 放行上传写请求")
    void sameOriginRefererAllowsProtectedWrite() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/plugins/install");
        request.addHeader(HttpHeaders.REFERER, "http://localhost:8080/plugin-manage.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("跨站 Origin 拒绝上传写请求")
    void crossOriginHeaderRejectsProtectedWrite() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/plugins/install");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("畸形 Origin 拒绝上传写请求")
    void malformedOriginRejectsProtectedWrite() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/plugins/install");
        request.addHeader(HttpHeaders.ORIGIN, "://bad-origin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("非上传写请求不受该过滤器影响")
    void unprotectedPostPassesThrough() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/download/pixiv");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("GET 读取请求不受该过滤器影响")
    void readRequestPassesThrough() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/collections/7/icon");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setRemoteAddr("192.168.1.100");
        return request;
    }
}
