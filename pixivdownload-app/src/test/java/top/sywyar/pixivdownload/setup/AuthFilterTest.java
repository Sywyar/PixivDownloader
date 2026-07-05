package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.TestGalleryPlugin;
import top.sywyar.pixivdownload.plugin.TestNovelGalleryPlugin;
import top.sywyar.pixivdownload.plugin.registry.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StartupRouteRegistry;

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
    private GuiTokenProvider guiTokenProvider;

    private AuthFilter authFilter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        authFilter = new AuthFilter(setupService, staticResourceRateLimitService, rateLimitService,
                localeResolver, appMessages, maintenanceProvider, guestInviteService, guiTokenProvider);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        lenient().when(staticResourceRateLimitService.isAllowed(any())).thenReturn(true);
        lenient().when(appMessages.getOrDefault(nullable(java.util.Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(appMessages.getForLog(anyString(), any(), any())).thenReturn("rate limited");
        lenient().when(maintenanceProvider.getIfAvailable()).thenReturn(null);
        lenient().when(guestInviteService.resolveByCode(any())).thenReturn(Optional.empty());
    }

    private AuthFilter authFilterWithGallery() {
        var plugins = new java.util.ArrayList<>(BuiltInPlugins.createAll());
        plugins.add(new TestGalleryPlugin());
        PluginRegistry registry = new PluginRegistry(plugins);
        return new AuthFilter(setupService, staticResourceRateLimitService, rateLimitService,
                localeResolver, appMessages, maintenanceProvider, guestInviteService, guiTokenProvider,
                new RouteAccessRegistry(registry), new StartupRouteRegistry(registry), new LandingRegistry(registry));
    }

    private AuthFilter authFilterWithNovel() {
        var plugins = new java.util.ArrayList<>(BuiltInPlugins.createAll());
        plugins.add(new TestNovelGalleryPlugin());
        PluginRegistry registry = new PluginRegistry(plugins);
        return new AuthFilter(setupService, staticResourceRateLimitService, rateLimitService,
                localeResolver, appMessages, maintenanceProvider, guestInviteService, guiTokenProvider,
                new RouteAccessRegistry(registry), new StartupRouteRegistry(registry), new LandingRegistry(registry));
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
                "/pixiv-gallery.html",
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
            authFilter = authFilterWithGallery();
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));
            when(staticResourceRateLimitService.isAllowedForInvite("invite:invite-code")).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/pixiv-gallery/gallery-core.js");
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
            request.setRequestURI("/api/app/info");
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
        request.setRequestURI("/api/app/info");

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
                "/api/app/info"
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
        @DisplayName("novel 插件已安装时 GET /api/novel/{id}/downloaded 本地 IP 应直接放行")
        void shouldAllowLocalNovelDownloadedCheck() throws Exception {
            authFilter = authFilterWithNovel();
            request.setMethod("GET");
            request.setRequestURI("/api/novel/12345/downloaded");
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("小说下载判重豁免不应波及其它 /api/novel 端点（本地无 session 仍按声明路由判定）")
        void shouldStillProtectOtherNovelGalleryApis() throws Exception {
            authFilter = authFilterWithNovel();
            request.setMethod("GET");
            request.setRequestURI("/api/novel/12345");
            request.setRemoteAddr("127.0.0.1");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(filterChain, never()).doFilter(request, response);
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
            authFilter = authFilterWithGallery();
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
                "/pixiv-artwork/artwork-core.js",
                "/monitor/monitor-core.js",
                "/pixiv-invite-manage/pixiv-invite-manage.css"
        })
        @DisplayName("画廊/作品详情页应按 monitor 权限保护，未登录时重定向到 /login.html")
        void shouldRedirectGalleryPagesToLoginWhenNotLoggedIn(String path) throws Exception {
            authFilter = authFilterWithGallery();
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
            authFilter = authFilterWithGallery();
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));

            request.setMethod("GET");
            request.setRequestURI("/pixiv-gallery/gallery-core.js");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verify(guestInviteService).recordHit(1L);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/css/admin-visibility.css",
                "/css/pixiv-side-modules.css",
                "/js/pixiv-side-modules.js",
                "/js/pixiv-navigation.js",
                "/js/pixiv-page-sections.js",
                "/js/pixiv-drilldowns.js"
        })
        @DisplayName("访客邀请会话应能加载画廊/小说页共享静态依赖（含导航、页面区块与下钻渲染器）")
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
        @ValueSource(strings = {
                "/pixiv-duplicates.html",
                "/pixiv-duplicates/pixiv-duplicates.js",
                "/api/duplicates/groups"
        })
        @DisplayName("duplicate 外置插件缺席时疑似重复页面与 API 未声明即 404")
        void duplicateResourcesReturn404WhenPluginMissing(String path) throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");

            request.setMethod("GET");
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(filterChain, never()).doFilter(request, response);
            verify(guestInviteService, never()).recordHit(anyLong());
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
            request.setRequestURI("/api/app/info");
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
            request.setRequestURI("/api/app/info");
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
            request.setRequestURI("/api/app/info");
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
            request.setRequestURI("/api/app/info");
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
            request.setRequestURI("/api/app/info");
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
        @DisplayName("TTS 插件未安装时，普通游客访问在线 TTS 合成 API 返回 404")
        void shouldRejectAnonymousTtsSynthesizeApi() throws Exception {
            request.setMethod("POST");
            request.setRequestURI("/api/tts/edge/synthesize");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(rateLimitService, never()).isAllowed(any());
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("TTS 插件未安装时，邀请访客访问在线 TTS 合成 API 不放行")
        void shouldAllowGuestInviteTtsSynthesizeApi() throws Exception {
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));

            request.setMethod("POST");
            request.setRequestURI("/api/tts/edge/synthesize");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(request, response);
            verify(guestInviteService, never()).recordHit(1L);
        }

        @Test
        @DisplayName("TTS 插件未安装时，管理员访问在线 TTS 合成 API 返回 404")
        void shouldAllowAdminTtsSynthesizeApi() throws Exception {
            request.setMethod("POST");
            request.setRequestURI("/api/tts/edge/synthesize");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie("pixiv_session", "valid-token"));

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("TTS 插件未安装时，/api/tts/** 其它路径同样返回 404")
        void shouldRejectAnonymousOnOtherTtsAdminPath() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/tts/voice-list");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("novel 插件已安装时多人模式非管理员可调用小说下载判重端点")
        void shouldAllowNovelDownloadedCheckForAnonymousMultiUser() throws Exception {
            authFilter = authFilterWithNovel();
            request.setMethod("GET");
            request.setRequestURI("/api/novel/12345/downloaded");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
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
            lenient().when(setupService.isSetupComplete()).thenReturn(true);
            lenient().when(setupService.getMode()).thenReturn("multi");
        }

        @Test
        @DisplayName("超出速率限制时 API 请求应返回 429")
        void shouldReturn429WhenRateLimitExceeded() throws Exception {
            when(rateLimitService.isAllowed(any())).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/api/app/info");
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
            request.setRequestURI("/api/app/info");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("公开 API 路径不进行多人模式 API 速率限制检查")
        void shouldNotCheckRateLimitForPublicApi() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/api/auth/check");
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
            request.setRequestURI("/api/app/info");
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
            request.setRequestURI("/api/app/info");
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
            request.setRequestURI("/api/app/info");
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
            request.setRequestURI("/api/app/info");
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
            when(guiTokenProvider.getToken()).thenReturn("gui-token");

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
            when(guiTokenProvider.getToken()).thenReturn("gui-token");

            request.setMethod("GET");
            request.setRequestURI(path);

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            verifyNoInteractions(filterChain);
        }
    }

    // ========== /proxy.pac 本地访问控制 ==========

    @Nested
    @DisplayName("/proxy.pac 本地访问控制")
    class ProxyPacTests {

        @ParameterizedTest
        @ValueSource(strings = {"127.0.0.1", "::1", "::ffff:127.0.0.1"})
        @DisplayName("本地请求应直接放行且不触碰鉴权与限流")
        void shouldAllowProxyPacFromLocalAddress(String addr) throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/proxy.pac");
            request.setRemoteAddr(addr);

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(setupService, rateLimitService, staticResourceRateLimitService);
        }

        @Test
        @DisplayName("非本地请求应返回 403")
        void shouldRejectProxyPacFromRemoteAddress() throws Exception {
            request.setMethod("GET");
            request.setRequestURI("/proxy.pac");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(request, response);
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
        @DisplayName("core-only multi 模式下无启动落点时回退到 login.html")
        void shouldRedirectToGalleryWhenNotInIntroModeAndDownloadWorkbenchAbsent() throws Exception {
            when(setupService.isIntroMode()).thenReturn(false);
            when(setupService.getMode()).thenReturn("multi");

            request.setMethod("GET");
            request.setRequestURI("/redirect");
            request.addParameter("canvas", "true");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/login.html");
        }

        @Test
        @DisplayName("core-only multi 模式无 canvas 参数也回退到 login.html")
        void shouldRedirectToGalleryWithoutCanvasParamAndDownloadWorkbenchAbsent() throws Exception {
            when(setupService.isIntroMode()).thenReturn(false);
            when(setupService.getMode()).thenReturn("multi");

            request.setMethod("GET");
            request.setRequestURI("/redirect");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getRedirectedUrl()).isEqualTo("/login.html");
        }

        @Test
        @DisplayName("gallery 插件启用的 solo 模式下应重定向到 pixiv-gallery.html")
        void shouldRedirectToGalleryInSoloMode() throws Exception {
            authFilter = authFilterWithGallery();
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

    // ========== 小说下载端点（归小说插件、VISITOR：复刻插画下载 /api/download/pixiv 现状） ==========

    @Nested
    @DisplayName("小说下载端点访问级别（新址 /api/novel/** + 旧址兼容垫片 /api/download/**）")
    class NovelDownloadEndpointTests {

        // 新址下载 / 状态 + 旧址兼容垫片：访问行为应与插画下载 /api/download/pixiv 完全对称
        //（VISITOR 为纯归属声明、AuthFilter 不派生任何清单、命中后落默认会话/访客分支）。

        @BeforeEach
        void useNovelRoutes() {
            authFilter = authFilterWithNovel();
        }

        @ParameterizedTest
        @CsvSource({
                "POST,/api/novel/download",
                "GET,/api/novel/status/12345",
                "GET,/api/novel/translate-status/12345",
                "GET,/api/novel/12345/downloaded",
                "POST,/api/novel/series/67890/merge",
                "POST,/api/download/pixiv/novel",
                "GET,/api/download/novel/status/12345",
                "GET,/api/download/novel/translate-status/12345"
        })
        @DisplayName("多人模式普通访客可访问（与插画下载对称：multi 访客走配额下载）")
        void multiVisitorAllowed(String method, String path) throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(rateLimitService.isAllowed(any())).thenReturn(true);

            request.setMethod(method);
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @ParameterizedTest
        @CsvSource({
                "POST,/api/novel/download",
                "GET,/api/novel/status/12345",
                "GET,/api/novel/translate-status/12345",
                "GET,/api/novel/12345/downloaded",
                "POST,/api/novel/series/67890/merge",
                "POST,/api/download/pixiv/novel",
                "GET,/api/download/novel/status/12345",
                "GET,/api/download/novel/translate-status/12345"
        })
        @DisplayName("solo 模式未登录访问应 401（仅会话用户 / 管理员可下载小说）")
        void soloUnauthorizedRejected(String method, String path) throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(setupService.isValidSession(any())).thenReturn(false);

            request.setMethod(method);
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(filterChain, never()).doFilter(request, response);
        }

        @ParameterizedTest
        @CsvSource({
                "POST,/api/novel/download",
                "GET,/api/novel/status/12345",
                "GET,/api/novel/translate-status/12345",
                "GET,/api/novel/12345/downloaded",
                "POST,/api/novel/series/67890/merge",
                "POST,/api/download/pixiv/novel",
                "GET,/api/download/novel/status/12345",
                "GET,/api/download/novel/translate-status/12345"
        })
        @DisplayName("邀请访客越界访问应 403（小说下载不在访客白名单，与插画下载一致）")
        void invitedGuestForbidden(String method, String path) throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));

            request.setMethod(method);
            request.setRequestURI(path);
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(request, response);
        }
    }

    // ========== 下载页扩展点装配端点（download-workbench 缺席时 core-only 不开放） ==========

    @Nested
    @DisplayName("download-workbench 缺席时 /api/download/extensions 不开放")
    class DownloadExtensionsEndpointTests {

        @Test
        @DisplayName("多人模式普通访客访问返回 404（不落到 core 宽前缀放行）")
        void multiVisitorReturns404() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");

            request.setMethod("GET");
            request.setRequestURI("/api/download/extensions");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(rateLimitService, never()).isAllowed(any());
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("solo 模式未登录访问返回 404（未声明优先于 401）")
        void soloUnauthorizedReturns404() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");

            request.setMethod("GET");
            request.setRequestURI("/api/download/extensions");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("邀请访客越界访问应 403（扩展点不在访客白名单，不扩大邀请访客权限）")
        void invitedGuestForbidden() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));

            request.setMethod("GET");
            request.setRequestURI("/api/download/extensions");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(request, response);
        }
    }

    // ========== 核心导航装配端点（归 core、VISITOR：声明路由但不改变旧访问行为） ==========

    @Nested
    @DisplayName("核心导航端点 /api/navigation 访问级别（归 core、VISITOR_AND_INVITED_GUEST）")
    class NavigationEndpointTests {

        // /api/navigation 由 CorePlugin.routes() 以 VISITOR_AND_INVITED_GUEST 声明（供前端动态导航使用）：
        // 不入 monitor，访客与受邀访客均可只读放行（各自得到对应身份可见导航），令受邀访客的画廊 / 小说页
        // 也能拉取动态导航。三态：multi 访客可读 / solo 未登录 401 / 受邀访客可读（历史 VISITOR 曾 403）。

        @Test
        @DisplayName("多人模式普通访客可读取（controller 返回访客可见导航，访问行为不变）")
        void multiVisitorAllowed() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(rateLimitService.isAllowed(any())).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/api/navigation");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("solo 模式未登录访问应 401（声明路由不放宽未登录可达面）")
        void soloUnauthorizedRejected() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(setupService.isValidSession(any())).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/api/navigation");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("邀请访客可读取（VISITOR_AND_INVITED_GUEST 放行受邀访客只读，使其页面能拉取动态导航）")
        void invitedGuestAllowed() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(rateLimitService.isAllowedForInvite(any())).thenReturn(true);
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));

            request.setMethod("GET");
            request.setRequestURI("/api/navigation");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    // ========== 核心下钻装配端点（归 core、VISITOR_AND_INVITED_GUEST：声明路由但不改变旧访问行为） ==========

    @Nested
    @DisplayName("核心下钻端点 /api/drilldowns 访问级别（归 core、VISITOR_AND_INVITED_GUEST）")
    class DrilldownEndpointTests {

        // /api/drilldowns 由 CorePlugin.routes() 以 VISITOR_AND_INVITED_GUEST 声明（同 /api/navigation /
        // /api/page-sections）：不入 monitor，访客与受邀访客均可只读放行（各自得到对应身份可见下钻贡献）。
        // 三态：multi 访客可读 / solo 未登录 401 / 受邀访客可读。

        @Test
        @DisplayName("多人模式普通访客可读取（controller 返回访客可见下钻，访问行为不变）")
        void multiVisitorAllowed() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(rateLimitService.isAllowed(any())).thenReturn(true);

            request.setMethod("GET");
            request.setRequestURI("/api/drilldowns");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("solo 模式未登录访问应 401（声明路由不放宽未登录可达面）")
        void soloUnauthorizedRejected() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(setupService.isValidSession(any())).thenReturn(false);

            request.setMethod("GET");
            request.setRequestURI("/api/drilldowns");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("邀请访客可读取（VISITOR_AND_INVITED_GUEST 放行受邀访客只读，使其页面能解析下钻）")
        void invitedGuestAllowed() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");
            when(rateLimitService.isAllowedForInvite(any())).thenReturn(true);
            when(guestInviteService.resolveByCode("invite-code")).thenReturn(Optional.of(new GuestInviteSession(
                    1L, "invite-code", true, false, false,
                    true, Set.of(), true, Set.of(),
                    true, Set.of(), true, Set.of()
            )));

            request.setMethod("GET");
            request.setRequestURI("/api/drilldowns");
            request.setRemoteAddr("192.168.1.100");
            request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "invite-code"));

            authFilter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    // ========== 全 URL 声明守卫：未命中任何已声明路由 → 统一 404（不再回落访客默认放行） ==========

    @Nested
    @DisplayName("未声明路由统一 404")
    class UndeclaredRouteTests {

        // 命中不了任何已声明路由的请求（非内联流程分支）统一 404；真实 controller / 静态资源由
        // RouteDeclarationCoverageTest 守卫均已声明、不会误伤，这里用确不存在的伪路径验证守卫本身。

        @Test
        @DisplayName("多人模式未声明 API 返回 404（不再回落访客放行 → 不再消耗配额限流）")
        void multiUndeclaredApiReturns404() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");

            request.setMethod("GET");
            request.setRequestURI("/api/no-such-endpoint");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(filterChain, never()).doFilter(request, response);
            verify(rateLimitService, never()).isAllowed(any());
        }

        @Test
        @DisplayName("多人模式未声明顶层页面返回 404")
        void multiUndeclaredPageReturns404() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("multi");

            request.setMethod("GET");
            request.setRequestURI("/totally-unknown.html");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("Solo 模式未声明 API 返回 404（在 session 校验之前，未声明优先于 401）")
        void soloUndeclaredApiReturns404() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");

            request.setMethod("GET");
            request.setRequestURI("/api/no-such-endpoint");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(404);
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("已声明 VISITOR 路由命中后仍按旧可观察行为判定（solo 无 session → 401，而非 404）")
        void declaredVisitorRouteStillReaches401InSolo() throws Exception {
            when(setupService.isSetupComplete()).thenReturn(true);
            when(setupService.getMode()).thenReturn("solo");
            when(setupService.isValidSession(any())).thenReturn(false);

            request.setMethod("POST");
            request.setRequestURI("/api/app/info");
            request.setRemoteAddr("192.168.1.100");

            authFilter.doFilterInternal(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(filterChain, never()).doFilter(request, response);
        }
    }
}
