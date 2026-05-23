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

import org.springframework.beans.factory.ObjectProvider;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import top.sywyar.pixivdownload.gui.GuiTokenService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthFilter 单元测试")
class AuthFilterTest {

    @Mock
    private SetupService setupService;
    @Mock
    private StaticResourceRateLimitService staticResourceRateLimitService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private AppLocaleResolver localeResolver;
    @Mock
    private AppMessages appMessages;
    @Mock
    private FilterChain filterChain;
    @Mock
    private ObjectProvider<MaintenanceCoordinator> maintenanceProvider;
    @Mock
    private GuestInviteService guestInviteService;
    @Mock
    private GuiTokenService guiTokenService;

    private AuthFilter authFilter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        authFilter = new AuthFilter(setupService, staticResourceRateLimitService, rateLimitService,
                localeResolver, appMessages, maintenanceProvider, guestInviteService, guiTokenService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        lenient().when(staticResourceRateLimitService.isAllowed(any())).thenReturn(true);
        lenient().when(appMessages.getOrDefault(nullable(java.util.Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(appMessages.getForLog(anyString(), any(), any())).thenReturn("rate limited");
        lenient().when(maintenanceProvider.getIfAvailable()).thenReturn(null);
        lenient().when(guestInviteService.resolveByCode(any())).thenReturn(Optional.empty());
    }

    // ========== 静态资源 IP 限流 ==========

    @Nested
    @DisplayName("静态资源 IP 限流")
    class StaticResourceRateLimitTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "/",
                "/index",
                "/login.html",
                "/pixiv-batch.html",
                "/js/pixiv-lang-switcher.js",
                "/vendor/fonts/fonts.css",
                "/vendor/fontawesome/webfonts/fa-solid-900.woff2"
        })
        @DisplayName("静态资源请求应先经过独立的 IP 限流")
        void shouldCheckStaticResourceRateLimit(String path) throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");

            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(staticResourceRateLimitService).isAllowed("192.168.1.100");
        }

        @Test
        @DisplayName("静态资源超出 IP 限流时应返回 429 且不消耗多人模式 API 限额")
        void shouldReturn429WhenStaticResourceRateLimitExceeded() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(staticResourceRateLimitService.isAllowed("203.0.113.10")).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/vendor/fonts/fonts.css");
            request.setRemoteAddr("203.0.113.10");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            assertThat(response.getContentAsString()).isEqualTo("Too Many Requests");
            verify(filterChain, never()).doFilter(request, response);
            verify(rateLimitService, never()).isAllowed(any());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/login.html",
                "/login/login.js",
                "/intro.html",
                "/intro/intro.css",
                "/vendor/fonts/fonts.css",
                "/invite"
        })
        @DisplayName("solo 模式未登录公开资源也应启用静态资源 IP 限流")
        void shouldCheckStaticResourceRateLimitForSoloPublicResources(String path) throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");

            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(staticResourceRateLimitService).isAllowed("192.168.1.100");
        }

        @Test
        @DisplayName("邀请访客页面静态资源应按邀请码限流（而非客户端 IP）")
        void shouldCheckStaticResourceRateLimitForInviteGuestStaticResource() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));
            when(staticResourceRateLimitService.isAllowedForInvite("invite:invite-code")).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/pixiv-gallery/pixiv-gallery.js");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(staticResourceRateLimitService).isAllowedForInvite("invite:invite-code");
            verify(staticResourceRateLimitService, never()).isAllowed("192.168.1.100");
            verify(filterChain).doFilter(request, response);
            verify(guestInviteService).recordHit(1L);
        }

        @Test
        @DisplayName("solo 模式非公开静态资源不应消耗公开资源 IP 限流")
        void shouldSkipStaticResourceRateLimitForSoloProtectedStaticResource() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(setupService.isValidSession(any())).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/vendor/fontawesome/css/all.min.css");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(staticResourceRateLimitService, never()).isAllowed(any());
            assertThat(response.getRedirectedUrl()).startsWith("/login.html?redirect=");
        }

        @Test
        @DisplayName("multi 模式已登录管理员不应启用静态资源 IP 限流")
        void shouldSkipStaticResourceRateLimitForAdminInMultiMode() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(setupService.isAdminLoggedIn(any())).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/vendor/fonts/fonts.css");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie("pixiv_session", "valid-token"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(staticResourceRateLimitService, never()).isAllowed(any());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("solo 模式已登录管理员不应启用静态资源 IP 限流")
        void shouldSkipStaticResourceRateLimitForAdminInSoloMode() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(setupService.isAdminLoggedIn(any())).thenReturn(true);
            when(setupService.isValidSession("valid-token")).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/vendor/fontawesome/css/all.min.css");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie("pixiv_session", "valid-token"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(staticResourceRateLimitService, never()).isAllowed(any());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("setup 未完成时不应启用静态资源 IP 限流")
        void shouldSkipStaticResourceRateLimitBeforeSetupComplete() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/vendor/fonts/fonts.css");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(staticResourceRateLimitService, never()).isAllowed(any());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("API 请求不应经过静态资源 IP 限流")
        void shouldNotCheckStaticResourceRateLimitForApiRequest() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(rateLimitService.isAllowed(any())).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(staticResourceRateLimitService, never()).isAllowed(any());
            verify(rateLimitService).isAllowed(any());
            verify(filterChain).doFilter(request, response);
        }
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
                "/js/pixiv-i18n.js",
                "/index/index.js",
                "/intro/intro.css",
                "/intro-canary/intro-canary.js",
                "/login/login.css",
                "/login/login.js",
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

        @ParameterizedTest
        @ValueSource(strings = {
                "/css/admin-visibility.css",
                "/vendor/fontawesome/css/all.min.css",
                "/vendor/chartjs/chart.umd.js"
        })
        @DisplayName("solo 模式下非 intro/login/访客相关静态资源不应无条件公开")
        void shouldNotExposeSoloSharedStaticResourcesWithoutLogin(String path) throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(setupService.isValidSession(any())).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).startsWith("/login.html?redirect=");
            verify(filterChain, never()).doFilter(request, response);
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
                "/setup/setup.css",
                "/setup/setup.js"
        })
        @DisplayName("setup 鎷嗗垎璧勬簮闈炴湰鍦?IP 搴旇繑鍥?403")
        void shouldRejectSetupStaticResourcesFromRemoteAddress(String path) throws Exception {
            request.setMethod("GET");
            request.setRequestURI(path);
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

        @BeforeEach
        void setupMocks() {
            when(setupService.isSetupComplete()).thenReturn(true);
        }

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

        @Test
        @DisplayName("monitor 专用 API 即使来自本地 IP 也应要求登录")
        void shouldRequireLoginForMonitorApiFromLocalAddress() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/downloaded/statistics");
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("作者列表 API 也应按 monitor 权限保护")
        void shouldRequireLoginForAuthorsApiFromLocalAddress() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/authors");
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(filterChain, never()).doFilter(request, response);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/gallery/artworks",
                "/api/gallery/tags",
                "/api/gallery/tags/lookup",
                "/api/gallery/artwork/12345",
                "/api/collections",
                "/api/collections/7/artworks/12345"
        })
        @DisplayName("画廊/收藏夹 API 应按 monitor 权限保护，本地无 session 也应 401")
        void shouldRequireLoginForGalleryAndCollectionApi(String path) throws Exception {
            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(filterChain, never()).doFilter(request, response);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/pixiv-gallery.html",
                "/pixiv-artwork.html",
                "/pixiv-gallery/pixiv-gallery.css",
                "/pixiv-artwork/pixiv-artwork.js",
                "/monitor/monitor.js",
                "/pixiv-invite-manage/pixiv-invite-manage.css"
        })
        @DisplayName("画廊/作品详情页应按 monitor 权限保护，未登录时重定向到 /login.html")
        void shouldRedirectGalleryPagesToLoginWhenNotLoggedIn(String path) throws Exception {
            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).startsWith("/login.html?redirect=");
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("璁垮閭€璇蜂細璇濆簲鑳藉姞杞界敾寤婇〉鎷嗗垎璧勬簮")
        void shouldAllowGuestInviteToLoadGallerySplitResource() throws Exception {
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));

            request.setMethod("GET");
            request.setRequestURI("/pixiv-gallery/pixiv-gallery.js");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verify(guestInviteService).recordHit(1L);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/css/admin-visibility.css",
                "/js/pixiv-novel-render.js"
        })
        @DisplayName("访客邀请会话应能加载画廊/小说页共享静态依赖")
        void shouldAllowGuestInviteToLoadSharedStaticResource(String path) throws Exception {
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));

            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verify(guestInviteService).recordHit(1L);
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

        @ParameterizedTest
        @ValueSource(strings = {
                "/js/pixiv-lang-switcher.js",
                "/js/pixiv-theme.js",
                "/setup/setup.css",
                "/setup/setup.js"
        })
        @DisplayName("setup 未完成时本地 IP 应能加载 setup 页面依赖的脚本")
        void shouldAllowSetupPageScriptsFromLocalAddress(String path) throws Exception {
            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/js/pixiv-lang-switcher.js",
                "/js/pixiv-theme.js",
                "/setup/setup.css",
                "/setup/setup.js"
        })
        @DisplayName("setup 未完成时非本地 IP 不应加载 setup 页面专用脚本")
        void shouldRejectSetupPageScriptsFromRemoteAddress(String path) throws Exception {
            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(request, response);
        }
    }

    // ========== 多人模式 ==========

    @Nested
    @DisplayName("多人模式")
    class MultiModeTests {

        @BeforeEach
        void setupMocks() {
            when(setupService.isSetupComplete()).thenReturn(true);
            lenient().when(setupService.getMode()).thenReturn("multi");
            lenient().when(rateLimitService.isAllowed(any())).thenReturn(true);
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

        @Test
        @DisplayName("monitor 页面在多人模式下也应要求登录")
        void shouldRedirectMonitorPageToLoginWhenNotLoggedIn() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/monitor.html");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/login.html?redirect=%2Fmonitor.html");
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("未登录普通游客不应直接调用在线 TTS 合成 API")
        void shouldRejectAnonymousTtsSynthesizeApi() throws Exception {
            request.setMethod("POST");
            request.setRequestURI("/api/tts/edge/synthesize");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(rateLimitService, never()).isAllowed(any());
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("邀请访客仍可调用在线 TTS 合成 API")
        void shouldAllowGuestInviteTtsSynthesizeApi() throws Exception {
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));
            when(rateLimitService.isAllowedForInvite("invite:invite-code")).thenReturn(true);

            request.setMethod("POST");
            request.setRequestURI("/api/tts/edge/synthesize");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verify(guestInviteService).recordHit(1L);
        }

        @Test
        @DisplayName("已登录管理员访问 monitor 页面应放行并补发 UUID Cookie")
        void shouldAllowMonitorPageForLoggedInAdmin() throws Exception {
            when(setupService.isValidSession("valid-token")).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/monitor.html");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie("pixiv_session", "valid-token"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getHeader("Set-Cookie")).contains("pixiv_user_id=");
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
        @DisplayName("普通页面路径不进行速率限制检查")
        void shouldNotCheckRateLimitForNormalPage() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/pixiv-batch.html");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(rateLimitService, never()).isAllowed(any());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("已登录管理员在多人模式下应跳过速率限制")
        void shouldSkipRateLimitForAdminInMultiMode() throws Exception {
            when(setupService.isAdminLoggedIn(any())).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/api/download/pixiv");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie("pixiv_session", "valid-token"));

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
            lenient().when(setupService.getMode()).thenReturn("solo");
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
        @DisplayName("/api/gui/** 在本地请求 + 有效 GUI 令牌时应直接放行")
        void shouldPassThroughGuiApiPaths(String path) throws Exception {
            when(guiTokenService.getToken()).thenReturn("gui-token");

            request.setMethod("GET");
            request.setRequestURI(path);
            request.addHeader("X-GUI-Token", "gui-token");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(setupService, rateLimitService);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/gui/status",
                "/api/gui/restart"
        })
        @DisplayName("/api/gui/** 缺少有效 GUI 令牌时应返回 403")
        void shouldRejectGuiApiPathsWithoutToken(String path) throws Exception {
            when(guiTokenService.getToken()).thenReturn("gui-token");

            request.setMethod("GET");
            request.setRequestURI(path);

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            verifyNoInteractions(filterChain);
        }
    }

    // ========== /redirect 重定向 ==========

    @Nested
    @DisplayName("/redirect 重定向逻辑")
    class RedirectPathTests {

        @Test
        @DisplayName("intro 模式下应重定向到 intro.html")
        void shouldRedirectToIntroInIntroMode() throws Exception {
            when(setupService.isIntroMode()).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/redirect");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/intro.html");
        }

        @Test
        @DisplayName("multi 模式下应重定向到 pixiv-batch.html")
        void shouldRedirectToPixivBatchWhenNotInIntroMode() throws Exception {
            when(setupService.isIntroMode()).thenReturn(false);
            when(setupService.getMode()).thenReturn("multi");

            request.setMethod("GET");
            request.setRequestURI("/redirect");
            request.addParameter("canvas", "true");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/pixiv-batch.html");
        }

        @Test
        @DisplayName("multi 模式无 canvas 参数也应重定向到 pixiv-batch.html")
        void shouldRedirectToPixivBatchWithoutCanvasParam() throws Exception {
            when(setupService.isIntroMode()).thenReturn(false);
            when(setupService.getMode()).thenReturn("multi");

            request.setMethod("GET");
            request.setRequestURI("/redirect");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/pixiv-batch.html");
        }

        @Test
        @DisplayName("solo 模式下应重定向到 pixiv-gallery.html")
        void shouldRedirectToGalleryInSoloMode() throws Exception {
            when(setupService.isIntroMode()).thenReturn(false);
            when(setupService.getMode()).thenReturn("solo");

            request.setMethod("GET");
            request.setRequestURI("/redirect");
            request.addParameter("canvas", "true");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/pixiv-gallery.html");
        }

        @Test
        @DisplayName("/redirect 是公开路径，setup 未完成时应放行")
        void shouldBePublicEvenWhenSetupNotComplete() throws Exception {
            // 注意：/redirect 在 setup 检查之前处理，所以 isSetupComplete 的 stubbing 是不必要的
            when(setupService.isIntroMode()).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/redirect");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/intro.html");
            verifyNoInteractions(filterChain);
        }
    }

    @Nested
    @DisplayName("Actuator 探针端点")
    class ActuatorEndpointTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/info"
        })
        @DisplayName("仅 health、liveness、readiness 与 info 应公开放行")
        void shouldPassThroughPublicActuatorEndpoints(String path) throws Exception {
            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(setupService, rateLimitService);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/actuator",
                "/actuator/env",
                "/actuator/configprops",
                "/actuator/logfile",
                "/actuator/health/",
                "/actuator/health/db",
                "/actuator/health/diskSpace",
                "/actuator/health/readiness/details"
        })
        @DisplayName("其它 actuator 路径应在鉴权兜底前直接拦截")
        void shouldBlockOtherActuatorEndpoints(String path) throws Exception {
            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verifyNoInteractions(filterChain, setupService, rateLimitService);
        }
    }
}
