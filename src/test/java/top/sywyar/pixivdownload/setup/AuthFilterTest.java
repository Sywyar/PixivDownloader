package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import top.sywyar.pixivdownload.quota.RateLimitService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthFilter 单元测试")
class AuthFilterTest {

    @Mock
    private SetupService setupService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private FilterChain filterChain;

    private AuthFilter authFilter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        authFilter = new AuthFilter(setupService, rateLimitService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    // ========== OPTIONS 预检请求 ==========

    @Test
    @DisplayName("OPTIONS 预检请求应直接放行")
    void shouldPassThroughOptionsRequest() throws Exception {
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/download/pixiv");

        authFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    // ========== 公开路径 ==========

    @Nested
    @DisplayName("公开路径放行")
    class PublicPathTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "/login.html",
                "/index.html",
                "/intro.html",
                "/intro-canary.html",
                "/favicon.ico",
                "/api/setup/status",
                "/api/auth/login",
                "/api/auth/check"
        })
        @DisplayName("始终公开的路径应直接放行（与模式无关）")
        void shouldPassThroughAlwaysPublicPaths(String path) throws Exception {
            request.setMethod("GET");
            request.setRequestURI(path);

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("/setup.html 本地 IP 应放行（非公开路径，走本地 IP 校验）")
        void shouldAllowSetupHtmlFromLocalAddress() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/setup.html");
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("/setup.html 非本地 IP 应返回 403")
        void shouldRejectSetupHtmlFromRemoteAddress() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/setup.html");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(request, response);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/quota/init",
                "/api/archive/status/some-token",
                "/api/archive/download/some-token"
        })
        @DisplayName("多人模式下配额/归档路径经多人模式分支放行（非公开路径快速通道）")
        void shouldPassThroughMultiModeQuotaPathsViaMultiBranch(String path) throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(rateLimitService.isAllowed(any())).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/quota/init",
                "/api/archive/status/some-token",
                "/api/archive/download/some-token",
                "/api/download/status"
        })
        @DisplayName("Solo 模式下配额/归档路径不公开，无效 Session 应返回 401")
        void shouldNotBePublicInSoloMode(String path) throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(setupService.isValidSession(any())).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(filterChain, never()).doFilter(request, response);
        }
    }

    // ========== /api/downloaded/ 本地访问控制 ==========

    @Nested
    @DisplayName("/api/downloaded/ 本地访问控制")
    class DownloadedPathTests {

        @Test
        @DisplayName("POST /api/downloaded/move/ 本地 IP 应放行")
        void shouldAllowLocalMoveRequest() throws Exception {
            request.setMethod("POST");
            request.setRequestURI("/api/downloaded/move/12345");
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("POST /api/downloaded/move/ 非本地 IP 应返回 403")
        void shouldRejectRemoteMoveRequest() throws Exception {
            request.setMethod("POST");
            request.setRequestURI("/api/downloaded/move/12345");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("GET /api/downloaded/* 本地 IP 应直接放行")
        void shouldAllowLocalGetDownloadedRequest() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/downloaded/12345");
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0:0:0:0:0:0:0:1", "::1", "::ffff:127.0.0.1"})
        @DisplayName("各种本地 IPv6 地址也应被识别为本地")
        void shouldRecognizeIpv6LocalAddresses(String addr) throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/downloaded/12345");
            request.setRemoteAddr(addr);

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    // ========== 未完成配置 ==========

    @Nested
    @DisplayName("未完成配置时的行为")
    class SetupIncompleteTests {

        @BeforeEach
        void setupMocks() {
            when(setupService.isSetupComplete()).thenReturn(false);
        }

        @Test
        @DisplayName("API 请求应返回 503")
        void shouldReturn503ForApiRequest() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(503);
        }

        @Test
        @DisplayName("非 API 请求应重定向到 setup.html")
        void shouldRedirectToSetupPage() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/monitor.html");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/setup.html");
        }
    }

    // ========== 多人模式 ==========

    @Nested
    @DisplayName("多人模式")
    class MultiModeTests {

        @BeforeEach
        void setupMocks() {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(rateLimitService.isAllowed(any())).thenReturn(true);
        }

        @Test
        @DisplayName("无 Cookie 时应分配 UUID Cookie")
        void shouldAssignUuidCookie() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            // 应设置了 Set-Cookie 头
            assertThat(response.getHeader("Set-Cookie"))
                    .contains("pixiv_user_id=");
        }

        @Test
        @DisplayName("已有 UUID Cookie 时不应重新分配")
        void shouldNotReassignExistingUuidCookie() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie("pixiv_user_id", "existing-uuid-value"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            // 不应设置新的 Cookie
            assertThat(response.getHeader("Set-Cookie")).isNull();
        }

        @Test
        @DisplayName("有效的 X-User-UUID 请求头应被使用")
        void shouldUseValidXUserUuidHeader() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");
            request.addHeader("X-User-UUID", "12345678-1234-1234-1234-123456789abc");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getHeader("Set-Cookie"))
                    .contains("12345678-1234-1234-1234-123456789abc");
        }

        @Test
        @DisplayName("格式无效的 X-User-UUID 应被忽略，生成新 UUID")
        void shouldIgnoreInvalidXUserUuidHeader() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");
            request.addHeader("X-User-UUID", "invalid-uuid");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            String cookie = response.getHeader("Set-Cookie");
            assertThat(cookie).contains("pixiv_user_id=");
            assertThat(cookie).doesNotContain("invalid-uuid");
        }
    }

    // ========== 速率限制 ==========

    @Nested
    @DisplayName("多人模式速率限制")
    class RateLimitTests {

        @BeforeEach
        void setupMocks() {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
        }

        @Test
        @DisplayName("超出速率限制时 API 请求应返回 429")
        void shouldReturn429WhenRateLimitExceeded() throws Exception {
            when(rateLimitService.isAllowed(any())).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("未超出速率限制时 API 请求应正常放行")
        void shouldPassWhenRateLimitNotExceeded() throws Exception {
            when(rateLimitService.isAllowed(any())).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("非 API 路径不进行速率限制检查")
        void shouldNotCheckRateLimitForNonApiPaths() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/monitor.html");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(rateLimitService, never()).isAllowed(any());
            verify(filterChain).doFilter(request, response);
        }
    }

    // ========== Solo 模式 ==========

    @Nested
    @DisplayName("Solo 模式 - Session 校验")
    class SoloModeTests {

        @BeforeEach
        void setupMocks() {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
        }

        @Test
        @DisplayName("有效 Session Cookie 应放行")
        void shouldPassWithValidSessionCookie() throws Exception {
            when(setupService.isValidSession("valid-token")).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie("pixiv_session", "valid-token"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("无效 Session 的 API 请求应返回 401")
        void shouldReturn401ForInvalidSessionApi() throws Exception {
            when(setupService.isValidSession(any())).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("无效 Session 的非 API 请求应重定向到 login.html")
        void shouldRedirectToLoginForInvalidSessionPage() throws Exception {
            when(setupService.isValidSession(any())).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/monitor.html");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).contains("/login.html");
        }

        @Test
        @DisplayName("X-Session-Token 请求头也应能认证")
        void shouldAuthenticateViaXSessionTokenHeader() throws Exception {
            when(setupService.isValidSession("header-token")).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");
            request.addHeader("X-Session-Token", "header-token");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    // ========== GUI 接口 ==========

    @Nested
    @DisplayName("GUI 接口公开路径")
    class GuiApiTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/gui/status",
                "/api/gui/restart",
                "/api/gui/anything"
        })
        @DisplayName("/api/gui/** 应直接放行（GUI 内部调用，solo 模式下无 session token）")
        void shouldPassThroughGuiApiPaths(String path) throws Exception {
            request.setMethod("GET");
            request.setRequestURI(path);

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(setupService, rateLimitService);
        }
    }

    // ========== /redirect 重定向 ==========

    @Nested
    @DisplayName("/redirect 重定向逻辑")
    class RedirectPathTests {

        @Test
        @DisplayName("intro 模式下 canvas=true 应重定向到 intro-canary.html")
        void shouldRedirectToIntroCanaryWhenCanvasSupportedInIntroMode() throws Exception {
            when(setupService.isIntroMode()).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/redirect");
            request.addParameter("canvas", "true");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/intro-canary.html");
        }

        @Test
        @DisplayName("intro 模式下 canvas=false 应重定向到 intro.html")
        void shouldRedirectToIntroWhenCanvasNotSupportedInIntroMode() throws Exception {
            when(setupService.isIntroMode()).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/redirect");
            request.addParameter("canvas", "false");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/intro.html");
        }

        @Test
        @DisplayName("非 intro 模式下应重定向到 pixiv-batch.html")
        void shouldRedirectToPixivBatchWhenNotInIntroMode() throws Exception {
            when(setupService.isIntroMode()).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/redirect");
            request.addParameter("canvas", "true");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/pixiv-batch.html");
        }

        @Test
        @DisplayName("无 canvas 参数时非 intro 模式也应重定向到 pixiv-batch.html")
        void shouldRedirectToPixivBatchWithoutCanvasParam() throws Exception {
            when(setupService.isIntroMode()).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/redirect");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/pixiv-batch.html");
        }

        @Test
        @DisplayName("/redirect 是公开路径，setup 未完成时应放行")
        void shouldBePublicEvenWhenSetupNotComplete() throws Exception {
            // 注意：/redirect 在 setup 检查之前处理，所以 isSetupComplete 的 stubbing 是不必要的
            when(setupService.isIntroMode()).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/redirect");
            request.addParameter("canvas", "true");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/intro-canary.html");
            verifyNoInteractions(filterChain);
        }
    }
}
