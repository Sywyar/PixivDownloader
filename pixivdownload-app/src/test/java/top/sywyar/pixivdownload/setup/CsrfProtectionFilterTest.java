package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("敏感写操作 CSRF 同源校验")
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
            "PUT,/api/plugins/demo/enabled",
            "POST,/api/plugin-market/official/demo/1.0.0/install",
            "POST,/api/plugin-market/repositories/import/preview",
            "POST,/api/plugin-market/repositories/import/trust",
            "POST,/api/collections/7/icon",
            "DELETE,/api/collections/7/icon",
            "POST,/api/narration/cast/voice/reference",
            "DELETE,/api/narration/cast/voice/reference",
            "POST,/api/narration/cast/voice/reference/generate",
            "POST,/api/schedule/tasks",
            "PUT,/api/schedule/tasks/9",
            "DELETE,/api/schedule/tasks/9"
    })
    @DisplayName("受保护写入口缺少来源信号时返回 403")
    void protectedWriteRejectsMissingOriginAndReferer(String method, String path) throws Exception {
        MockHttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"auth.csrf.invalid\"")
                .contains("\"error\":\"Request origin verification failed\"");
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
    @DisplayName("TRACE 在过滤器层直接返回 405")
    void traceIsAlwaysRejected() throws Exception {
        MockHttpServletRequest request = request("TRACE", "/api/collections/7/icon");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"http.status.405\"")
                .contains("\"error\":\"Method Not Allowed\"");
        assertThat(response.getHeader(HttpHeaders.ALLOW)).doesNotContain("TRACE");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("页面 TRACE 请求交给容器渲染 4xx 错误页")
    void htmlTraceUsesContainerErrorPage() throws Exception {
        MockHttpServletRequest request = request("TRACE", "/index.html");
        request.addHeader(HttpHeaders.ACCEPT, "text/html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getHeader(HttpHeaders.ALLOW)).doesNotContain("TRACE");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("浏览器页面写请求来源校验失败时交给容器渲染 403 错误页")
    void htmlPageWriteRejectionUsesContainerErrorPage() throws Exception {
        MockHttpServletRequest request = request("POST", "/index.html");
        request.addHeader(HttpHeaders.ACCEPT, "text/html");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getContentAsString()).isEmpty();
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("API 写请求即使接受 HTML 仍保留 JSON 错误契约")
    void apiWriteWithHtmlAcceptKeepsJsonError() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/plugins/install");
        request.addHeader(HttpHeaders.ACCEPT, "text/html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("Request origin verification failed");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("可信代理规范化后的外部 Origin 可通过同源校验")
    void normalizedProxyOriginAllowsProtectedWrite() throws Exception {
        TrustedForwardedRequestFilter forwardedFilter =
                new TrustedForwardedRequestFilter("172.16.0.0/12");
        MockHttpServletRequest request = request("POST", "/api/collections/7/icon");
        request.setRemoteAddr("172.18.0.3");
        request.addHeader("X-Forwarded-For", "198.51.100.20");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "gallery.example");
        request.addHeader(HttpHeaders.ORIGIN, "https://gallery.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        forwardedFilter.doFilterInternal(request, response,
                (normalized, normalizedResponse) -> filter.doFilterInternal(
                        (HttpServletRequest) normalized,
                        (HttpServletResponse) normalizedResponse,
                        filterChain));

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
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
    @DisplayName("插件启用态 PUT 携带同源 Origin 时放行")
    void sameOriginPluginToggleAllowsPut() throws Exception {
        MockHttpServletRequest request = request("PUT", "/api/plugins/demo/enabled");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("插件启用态 PUT 携带跨站 Origin 时拒绝")
    void crossOriginPluginToggleRejectsPut() throws Exception {
        MockHttpServletRequest request = request("PUT", "/api/plugins/demo/enabled");
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
    @DisplayName("真实 MVC 路由会忽略的矩阵参数仍按规范路径执行 CSRF 校验")
    void matrixParametersCannotBypassProtectionOnRealMvcRoute() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CollectionIconProbeController())
                .addFilters(filter)
                .build();

        mockMvc.perform(post("/api/collections/7/icon;trace=1"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/collections/7/icon;trace=1")
                        .header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("无浏览器信号和环境凭证的后端客户端写请求继续放行")
    void nonBrowserPostWithoutAmbientCredentialPassesThrough() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/download/pixiv");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("任意携带管理员会话的写请求缺少同源信号时拒绝")
    void arbitrarySessionAuthenticatedWriteRequiresSameOrigin() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/download/pixiv");
        request.setCookies(new Cookie("pixiv_session", "session-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("任意跨站来源的写请求即使未携带会话也拒绝")
    void arbitraryCrossOriginWriteIsRejected() throws Exception {
        MockHttpServletRequest request = request("PATCH", "/api/future/write");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Pixiv 油猴来源不携带后端环境凭证时可提交下载")
    void pixivUserscriptSourceWithoutAmbientCredentialPassesThrough() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/download/pixiv");
        request.addHeader(HttpHeaders.ORIGIN, "https://www.pixiv.net");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Pixiv 油猴来源可把小说响应导入本机一次性票据端点")
    void pixivUserscriptCanImportNovelResponseWithoutAmbientCredential() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/novel/browser-import/42");
        request.addHeader(HttpHeaders.ORIGIN, "https://www.pixiv.net");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("小说响应导入例外只接受十进制作品 ID 路径")
    void pixivUserscriptCannotImportNovelResponseToNonNumericPath() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/novel/browser-import/not-a-number");
        request.addHeader(HttpHeaders.ORIGIN, "https://www.pixiv.net");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Pixiv 来源不能借 userscript 例外访问非脚本写端点")
    void pixivSourceCannotInitializeSetup() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/setup/init");
        request.addHeader(HttpHeaders.ORIGIN, "https://www.pixiv.net");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(request, response);
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

    @RestController
    private static final class CollectionIconProbeController {

        @PostMapping("/api/collections/{collectionId}/icon")
        String upload(@PathVariable long collectionId) {
            return Long.toString(collectionId);
        }
    }
}
