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

    @BeforeEach
    void initialize() throws Exception {
        var plugin = mock(PixivFeaturePlugin.class);
        when(plugin.id()).thenReturn("example");
        when(plugin.displayNamespace()).thenReturn("example");
        when(plugin.routes()).thenReturn(List.of(route("read")));
        var plugins = new PluginRegistry(List.of(plugin));
        routes = new RouteAccessRegistry(plugins);
        keys = new ProgramKeyService(directory.resolve("keys.json"), routes, plugins);
        var created = keys.create(new ProgramKeyStore.Policy("worker", Set.of("example:read"), false, List.of("192.0.2.0/24")));
        secret = created.secret(); keyId = created.key().id();
        var messages = mock(AppMessages.class);
        when(messages.getOrDefault(nullable(Locale.class), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(2));
        setup = mock(SetupService.class);
        when(setup.isAdminLoggedIn(any())).thenReturn(true);
        filter = new AuthFilter(setup, mock(StaticResourceRateLimitService.class), mock(RateLimitService.class),
                mock(AppLocaleResolver.class), messages, mock(ObjectProvider.class), mock(GuestInviteService.class),
                mock(GuiTokenProvider.class), routes, new StartupRouteRegistry(plugins), new LandingRegistry(plugins),
                new NavigationRegistry(plugins), keys);
    }

    private WebRouteContribution route(String permission) {
        return WebRouteContribution.admin("/api/example/items").withProgramPermissions(Map.of(HttpMethod.GET,
                new ProgramPermission(permission, "permissions.read")));
    }
    private MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/api/example/items");
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
        assertResponse(request("POST"), 403, false);
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
        routes.unregister("example"); assertResponse(request("GET"), 403, false);
        routes.register("other-owner", List.of(route("read")));
        assertResponse(request("GET"), 403, false);
        routes.unregister("other-owner");
        routes.register("example", List.of(route("new-read"))); assertResponse(request("GET"), 403, false);
        routes.unregister("example"); routes.register("example", List.of(route("read")));
        assertResponse(request("GET"), 200, true);
        keys.revoke(keyId, 1); assertResponse(request("GET"), 401, false);
    }
}
