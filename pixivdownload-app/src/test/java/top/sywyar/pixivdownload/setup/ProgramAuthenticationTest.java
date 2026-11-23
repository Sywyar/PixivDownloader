package top.sywyar.pixivdownload.setup;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.*;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.i18n.*;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.*;
import top.sywyar.pixivdownload.plugin.registry.web.*;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DisplayName("程序身份不会回退或扩大权限")
class ProgramAuthenticationTest {
    @TempDir Path directory;
    private RouteAccessRegistry routes;
    private ProgramKeyService keys;
    private AuthFilter filter;
    private String secret;
    private UUID keyId;
    private SetupService setup;
    private AppMessages messages;

    @BeforeEach
    void initialize() throws Exception {
        var plugin = mock(PixivFeaturePlugin.class);
        when(plugin.id()).thenReturn("example");
        when(plugin.displayNamespace()).thenReturn("example");
        when(plugin.routes()).thenReturn(List.of(route("read"),
                WebRouteContribution.admin("/api/example/catalog").withProgramPermissions(Map.of(HttpMethod.GET,
                        new ProgramPermission("catalog.read", "permissions.catalog"))),
                WebRouteContribution.admin("/example/page")));
        var plugins = new PluginRegistry(List.of(plugin));
        routes = new RouteAccessRegistry(plugins);
        keys = new ProgramKeyService(directory.resolve("keys.json"), routes, plugins);
        var created = keys.create(new ProgramKeyStore.Policy("worker", Set.of("example:read"), false, List.of("192.0.2.0/24")));
        secret = created.secret(); keyId = created.key().id();
        messages = mock(AppMessages.class);
        when(messages.getOrDefault(nullable(Locale.class), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(2));
        when(messages.getOrDefault(nullable(Locale.class), anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.<String>getArgument(2).replace("{0}", inv.getArgument(3)));
        setup = mock(SetupService.class);
        when(setup.isAdminLoggedIn(any())).thenReturn(true);
        filter = new AuthFilter(setup, mock(StaticResourceRateLimitService.class), mock(RateLimitService.class),
                mock(AppLocaleResolver.class), messages, mock(ObjectProvider.class), mock(GuestInviteService.class),
                mock(GuiTokenProvider.class), routes, new StartupRouteRegistry(plugins), new LandingRegistry(plugins),
                new NavigationRegistry(plugins), keys);
    }

    @Test
    void anyAuthenticatedKeyCanReadOnlyItsOwnGrants() throws Exception {
        routes.register("core", List.of(ProgramAccess.SELF_ROUTE, WebRouteContribution.admin("/api/security/program-keys/**")));
        var empty = keys.create(new ProgramKeyStore.Policy("no-grants", Set.of(), false, List.of("192.0.2.0/24")));
        var request = request("GET");
        request.setRequestURI(ProgramAccess.SELF_PATH);
        request.removeHeader("Authorization");
        assertResponse(request, 401, false);
        request.addHeader("Authorization", "Bearer " + empty.secret());
        assertResponse(request, 200, true);
        assertThat(ProgramAccess.identity(request)).contains(new ProgramIdentity(empty.key().id(), Set.of()));

        request.setMethod("POST");
        assertResponse(request, 403, false);
        request.setMethod("GET");
        request.setRequestURI("/api/security/program-keys/permissions");
        assertResponse(request, 403, false);
        request.setRequestURI(ProgramAccess.SELF_PATH);
        request.setRemoteAddr("198.51.100.1");
        assertResponse(request, 401, false);
        request.setRemoteAddr("192.0.2.1");
        routes.unregister("core");
        routes.register("another", List.of(ProgramAccess.SELF_ROUTE));
        assertResponse(request, 403, false);
        keys.revoke(empty.key().id(), empty.key().revision());
        assertResponse(request, 401, false);
    }

    private WebRouteContribution route(String permission) {
        return WebRouteContribution.admin("/api/example/items").withProgramPermissions(Map.of(HttpMethod.GET,
                new ProgramPermission(permission, "permissions.read")));
    }
    private MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/api/example/items");
        request.setSecure(true);
        request.setRemoteAddr("192.0.2.1"); request.addHeader("Authorization", "Bearer " + secret);
        return request;
    }
    private void assertResponse(MockHttpServletRequest request, int status, boolean reached) throws Exception {
        var response = new MockHttpServletResponse();
        var executed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> executed.set(true));
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(executed.get()).isEqualTo(reached);
    }

    @Test
    @DisplayName("只接受已授权的方法与来源，身份不受浏览器 UUID 控制")
    void enforcesMethodAndIp() throws Exception {
        var valid = request("GET");
        valid.setCookies(new Cookie("pixiv_user_id", "another-user"));
        assertResponse(valid, 200, true);
        assertThat(ProgramAccess.identity(valid).orElseThrow().id()).isEqualTo(keyId);
        assertThat(new HostRequestOwnerIdentityResolver(setup).resolve(valid).ownerUuid()).isEqualTo(keyId.toString());
        var unsupported = new MockHttpServletResponse();
        filter.doFilter(request("POST"), unsupported, (req, res) -> { throw new AssertionError("unexpected dispatch"); });
        assertThat(unsupported.getStatus()).isEqualTo(403);
        assertThat(unsupported.getContentAsString()).contains("auth.program.unsupported").doesNotContain("programPermission");
        var wrongIp = request("GET"); wrongIp.setRemoteAddr("198.51.100.1");
        assertResponse(wrongIp, 401, false);
    }

    @Test
    @DisplayName("双凭据、无效密钥和重复授权头均不回退管理员")
    void rejectsMixedCredentials() throws Exception {
        var session = request("GET"); session.setCookies(new Cookie("pixiv_session", "admin-session"));
        assertResponse(session, 401, false);
        var invite = request("GET"); invite.setCookies(new Cookie("pixiv_invite_token", "expired"));
        assertResponse(invite, 401, false);
        var gui = request("GET"); gui.addHeader(GuiTokenProvider.HEADER_NAME, "gui-token");
        assertResponse(gui, 401, false);
        var duplicate = request("GET"); duplicate.addHeader("Authorization", "Bearer other");
        assertResponse(duplicate, 401, false);
        var invalid = request("GET"); invalid.removeHeader("Authorization"); invalid.addHeader("Authorization", "Bearer invalid");
        assertResponse(invalid, 401, false);
    }

    @Test
    @DisplayName("撤回路由、增加权限与撤销密钥都立即阻止后续调用")
    void followsCurrentDeclarationsAndRevocation() throws Exception {
        assertThatThrownBy(() -> routes.register("other-owner", List.of(route("read"))))
                .isInstanceOf(IllegalStateException.class);
        assertResponse(request("GET"), 200, true);
        routes.unregister("example"); assertResponse(request("GET"), 404, false);
        routes.register("other-owner", List.of(route("read")));
        assertResponse(request("GET"), 403, false);
        routes.unregister("other-owner");
        routes.register("example", List.of(route("new-read"))); assertResponse(request("GET"), 403, false);
        routes.unregister("example"); routes.register("example", List.of(route("read")));
        assertResponse(request("GET"), 200, true);
        keys.revoke(keyId, 1); assertResponse(request("GET"), 401, false);
    }

    @Test
    void reportsOnlyConfirmedTargetPermissions() throws Exception {
        var request = request("GET");
        assertResponse(request, 200, true);
        assertThatThrownBy(() -> ProgramAccess.require(request, null, "settings.write"))
                .isInstanceOfSatisfying(ProgramAccess.MissingPermission.class,
                        error -> assertThat(error.permission()).isEqualTo("core:settings.write"));
        assertThatThrownBy(() -> ProgramAccess.require(request, "missing", "settings.write", () -> false))
                .isExactlyInstanceOf(top.sywyar.pixivdownload.i18n.LocalizedException.class);
        assertThatThrownBy(() -> ProgramAccess.require(request, "invalid:owner", "settings.write",
                () -> { throw new AssertionError("invalid names must not trigger a lookup"); }))
                .isExactlyInstanceOf(top.sywyar.pixivdownload.i18n.LocalizedException.class);
        assertThatThrownBy(() -> ProgramAccess.require(request, "changing", "settings.write",
                () -> { throw new IllegalStateException(); }))
                .isExactlyInstanceOf(top.sywyar.pixivdownload.i18n.LocalizedException.class);
    }

    private MockHttpServletResponse anonymous(String method, String path, String accept) throws Exception {
        when(setup.isSetupComplete()).thenReturn(true);
        var request = new MockHttpServletRequest(method, path);
        if (accept != null) request.addHeader("Accept", accept);
        var response = new MockHttpServletResponse();
        var executed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> executed.set(true));
        assertThat(executed).isFalse();
        return response;
    }

    @Test
    @DisplayName("未登录访问接受程序密钥的方法时回 401 与 Bearer 质询，说明所需权限而不跳转登录页")
    void explainsProgramKeysToAnonymousCallers() throws Exception {
        var api = anonymous("GET", "/api/example/items", null);
        assertThat(api.getStatus()).isEqualTo(401);
        assertThat(api.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"PixivDownloader\"");
        assertThat(api.getContentAsString()).contains("\"code\":\"auth.unauthorized\"",
                "\"programPermission\":\"example:read\"", "Bearer <key>");

        // API 错误通道不因浏览器 Accept 切换成 HTML，调用方仍能读到质询与所需权限。
        var browser = anonymous("GET", "/api/example/catalog", "text/html");
        assertThat(browser.getStatus()).isEqualTo(401);
        assertThat(browser.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"PixivDownloader\"");
        assertThat(browser.getContentAsString()).contains("\"programPermission\":\"example:catalog.read\"");

        // 未声明程序权限的方法保持原有会话语义：API 401 无质询，页面导航跳转登录。
        var write = anonymous("POST", "/api/example/items", null);
        assertThat(write.getStatus()).isEqualTo(401);
        assertThat(write.getHeader("WWW-Authenticate")).isNull();
        assertThat(write.getContentAsString()).doesNotContain("programPermission");
        assertThat(anonymous("GET", "/example/page", null).getRedirectedUrl()).startsWith("/login.html?redirect=");
    }

    @Test
    @DisplayName("无效密钥带 invalid_token 质询，缺少权限时说明路由要求的完整权限")
    void namesTheMissingPermission() throws Exception {
        var invalid = request("GET"); invalid.removeHeader("Authorization"); invalid.addHeader("Authorization", "Bearer invalid");
        var rejected = new MockHttpServletResponse();
        filter.doFilter(invalid, rejected, (req, res) -> { throw new AssertionError("must not reach the chain"); });
        assertThat(rejected.getStatus()).isEqualTo(401);
        assertThat(rejected.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"PixivDownloader\", error=\"invalid_token\"");

        var catalog = request("GET"); catalog.setRequestURI("/api/example/catalog"); catalog.setSecure(true);
        var forbidden = new MockHttpServletResponse();
        filter.doFilter(catalog, forbidden, (req, res) -> { throw new AssertionError("must not reach the chain"); });
        assertThat(forbidden.getStatus()).isEqualTo(403);
        assertThat(forbidden.getContentAsString()).contains("\"code\":\"auth.program.forbidden\"",
                "\"programPermission\":\"example:catalog.read\"");
    }
}
