package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 守护 {@code AuthFilter} 的路由访问判定读自 {@link RouteAccessRegistry} 的<b>当前</b>不可变快照，
 * 而非构造期固化的静态副本：同一 filter 实例下，对 registry 执行 register / unregister 替换快照后，
 * monitor 保护与访客邀请白名单都应随新快照变化。与金标准 {@code AuthFilterTest} 互补——后者用 8 参便利
 * 构造器固定内置插件快照、守护过滤行为本身；本测试用 9 参构造器注入可变 registry、守护读侧随快照更新。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthFilter 路由访问判定随 RouteAccessRegistry 快照更新")
class AuthFilterRegistrySnapshotTest {

    private static final String DEMO_PATTERN = "/api/demo/**";
    private static final String DEMO_PATH = "/api/demo/thing";

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

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        resetExchange();
        lenient().when(appMessages.getOrDefault(nullable(java.util.Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(staticResourceRateLimitService.isAllowed(any())).thenReturn(true);
        lenient().when(maintenanceProvider.getIfAvailable()).thenReturn(null);
    }

    @Test
    @DisplayName("注销访客可读路由后 AuthFilter 不再放行该邀请访客请求，重新注册后恢复放行")
    void guestWhitelistFollowsRegistrySnapshot() throws Exception {
        when(setupService.isSetupComplete()).thenReturn(true);
        when(setupService.getMode()).thenReturn("solo");
        when(guestInviteService.resolveByCode("demo-code")).thenReturn(Optional.of(guestSession()));
        when(rateLimitService.isAllowedForInvite("invite:demo-code")).thenReturn(true);

        RouteAccessRegistry registry = new RouteAccessRegistry(new PluginRegistry(List.of()));
        registry.register("demo", List.of(invitedGuestRoute()));
        AuthFilter filter = filterFor(registry);

        // 已注册为 INVITED_GUEST：受邀访客可只读 → 放行并记一次命中
        invokeGuest(filter);
        verify(filterChain).doFilter(request, response);
        verify(guestInviteService).recordHit(1L);

        // 注销后：同一访客请求不再命中旧快照的白名单，越界 → 403
        registry.unregister("demo");
        resetExchange();
        invokeGuest(filter);
        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(request, response);

        // 重新注册：读侧随新快照恢复放行
        registry.register("demo", List.of(invitedGuestRoute()));
        resetExchange();
        invokeGuest(filter);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("注销 monitor 受保护路由后该路径成为未声明路由（404），重新注册后恢复 monitor 保护")
    void monitorProtectionFollowsRegistrySnapshot() throws Exception {
        when(setupService.isSetupComplete()).thenReturn(true);
        when(setupService.getMode()).thenReturn("multi");
        lenient().when(rateLimitService.isAllowed(any())).thenReturn(true);

        RouteAccessRegistry registry = new RouteAccessRegistry(new PluginRegistry(List.of()));
        registry.register("demo", List.of(invitedGuestRoute()));
        AuthFilter filter = filterFor(registry);

        // 已注册（INVITED_GUEST → 进入 monitor 受保护清单）：multi 匿名访客无 session → 401
        invokeMultiAnonymous(filter);
        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);

        // 注销后：该路径离开快照、不再被任何已声明路由命中 → 全 URL 声明守卫统一 404（不再回落访客放行）
        registry.unregister("demo");
        resetExchange();
        invokeMultiAnonymous(filter);
        assertThat(response.getStatus()).isEqualTo(404);
        verify(filterChain, never()).doFilter(request, response);

        // 重新注册：读侧随新快照恢复 monitor 保护 → 再次 401
        registry.register("demo", List.of(invitedGuestRoute()));
        resetExchange();
        invokeMultiAnonymous(filter);
        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("method-aware 未声明守卫：仅声明 POST 的 URL，GET 视为未声明 → 404；POST 命中声明按策略放行")
    void methodAwareUndeclaredGuard() throws Exception {
        when(setupService.isSetupComplete()).thenReturn(true);
        when(setupService.getMode()).thenReturn("multi");
        lenient().when(rateLimitService.isAllowed(any())).thenReturn(true);

        RouteAccessRegistry registry = new RouteAccessRegistry(new PluginRegistry(List.of()));
        registry.register("demo", List.of(new WebRouteContribution(
                "/api/demo/act", AccessPolicy.VISITOR, Set.of(HttpMethod.POST), false)));
        AuthFilter filter = filterFor(registry);

        // GET：该 URL 只声明了 POST、无全方法声明覆盖 → method-aware 守卫视为未声明 → 404
        request.setMethod("GET");
        request.setRequestURI("/api/demo/act");
        request.setRemoteAddr("192.168.1.100");
        filter.doFilterInternal(request, response, filterChain);
        assertThat(response.getStatus()).isEqualTo(404);
        verify(filterChain, never()).doFilter(request, response);

        // POST：命中 VISITOR 声明 → multi 普通访客放行（method-aware 守卫认其已声明）
        resetExchange();
        request.setMethod("POST");
        request.setRequestURI("/api/demo/act");
        request.setRemoteAddr("192.168.1.100");
        filter.doFilterInternal(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
    }

    private AuthFilter filterFor(RouteAccessRegistry registry) {
        return new AuthFilter(setupService, staticResourceRateLimitService, rateLimitService,
                localeResolver, appMessages, maintenanceProvider, guestInviteService, guiTokenProvider, registry);
    }

    private void invokeGuest(AuthFilter filter) throws Exception {
        request.setMethod("GET");
        request.setRequestURI(DEMO_PATH);
        request.setRemoteAddr("192.168.1.100");
        request.setCookies(new Cookie(AuthFilter.INVITE_COOKIE, "demo-code"));
        filter.doFilterInternal(request, response, filterChain);
    }

    private void invokeMultiAnonymous(AuthFilter filter) throws Exception {
        request.setMethod("GET");
        request.setRequestURI(DEMO_PATH);
        request.setRemoteAddr("192.168.1.100");
        filter.doFilterInternal(request, response, filterChain);
    }

    private void resetExchange() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    private static WebRouteContribution invitedGuestRoute() {
        return new WebRouteContribution(DEMO_PATTERN, AccessPolicy.INVITED_GUEST, Set.of(), false);
    }

    private static GuestInviteSession guestSession() {
        return new GuestInviteSession(
                1L, "demo-code", true, false, false,
                true, Set.of(), true, Set.of(),
                true, Set.of(), true, Set.of());
    }
}
