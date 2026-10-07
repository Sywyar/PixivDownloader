package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.plugin.CorePlugin;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.web.controller.NavigationController;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;
import top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeFactory;
import top.sywyar.pixivdownload.setup.guest.controller.AdminInviteController;
import top.sywyar.pixivdownload.setup.guest.dto.InviteDetail;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("请求身份在认证、管理、可见范围与身份切换间保持一致")
class GuestRequestIdentityTest {
    @TempDir Path tempDir;
    private SetupService setup;
    private GuestInviteService invites;
    private MockMvc mvc;
    private String adminToken;
    private String otherBrowserToken;
    private final GuestInviteSession guest = new GuestInviteSession(7L, "fixture-invite",
            true, false, false, false, Set.of(), false, Set.of(123L),
            false, Set.of(), false, Set.of(123L));

    @BeforeEach
    void configurePaths() {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("config").toString());
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, tempDir.resolve("state").toString());
    }

    @AfterEach
    void clearPaths() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
    }

    private void start(String mode) throws Exception {
        DownloadConfig config = new DownloadConfig();
        config.setRootFolder(tempDir.resolve("works").toString());
        setup = new SetupService(config, new ObjectMapper(), new DefaultApplicationArguments(),
                TestI18nBeans.appMessages());
        setup.init("fixture-admin", "fixture-password", mode);
        adminToken = setup.createSession(false);
        otherBrowserToken = setup.createSession(true);
        invites = mock(GuestInviteService.class);
        when(invites.resolveByCode(guest.code())).thenReturn(Optional.of(guest));
        var staticLimits = mock(StaticResourceRateLimitService.class);
        when(staticLimits.isAllowed(anyString())).thenReturn(true);
        when(staticLimits.isAllowedForInvite(anyString())).thenReturn(true);
        var limits = mock(RateLimitService.class);
        when(limits.isAllowedForInvite(anyString())).thenReturn(true);
        var locale = mock(AppLocaleResolver.class);
        when(locale.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        var auth = new AuthFilter(setup, staticLimits, limits, locale, TestI18nBeans.appMessages(),
                new StaticListableBeanFactory().getBeanProvider(MaintenanceCoordinator.class),
                invites, mock(GuiTokenProvider.class));
        var loginLimits = mock(LoginRateLimitService.class);
        when(loginLimits.isAllowed(anyString())).thenReturn(true);
        var controller = new SetupController(setup, loginLimits, mock(MultiModeConfig.class),
                mock(ProxySetupService.class), TestI18nBeans.appMessages());
        mvc = MockMvcBuilders.standaloneSetup(controller, new AdminInviteController(invites),
                        new NavigationController(new NavigationRegistry(new PluginRegistry(List.of(new CorePlugin()))), setup))
                .addFilters(auth)
                .defaultRequest(get("/").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .build();
    }

    private Cookie admin() { return new Cookie("pixiv_session", adminToken); }
    private Cookie invite(String code) { return new Cookie(GuestInviteSession.COOKIE_NAME, code); }

    @ParameterizedTest
    @ValueSource(strings = {"solo", "multi"})
    @DisplayName("纯管理员可检查身份及创建邀请，纯访客和双凭证均拒绝管理写入")
    void oneIdentityForEveryRoute(String mode) throws Exception {
        start(mode);
        when(invites.createInvite(any())).thenReturn(8L);
        when(invites.detail(8L)).thenReturn(mock(InviteDetail.class));
        mvc.perform(get("/api/auth/check").cookie(admin())).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(get("/api/admin/invites").cookie(admin())).andExpect(status().isOk());
        mvc.perform(post("/api/admin/invites").cookie(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(invites).createInvite(any());
        clearInvocations(invites);

        for (Cookie[] cookies : List.of(new Cookie[]{invite(guest.code())}, new Cookie[]{admin(), invite(guest.code())})) {
            mvc.perform(get("/api/auth/check").cookie(cookies)).andExpect(jsonPath("$.valid").value(false));
            mvc.perform(get("/api/navigation").cookie(cookies)).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("pixiv-invite-manage"))));
            for (MockHttpServletRequestBuilder request : List.of(get("/api/admin/invites"),
                    post("/api/admin/invites").contentType(MediaType.APPLICATION_JSON).content("{}"),
                    put("/api/admin/invites/7").contentType(MediaType.APPLICATION_JSON).content("{}"),
                    delete("/api/admin/invites/7"), get("/pixiv-invite-manage.html"),
                    get("/pixiv-batch.html"), post("/api/gui/logout-all"), get("/setup.html"))) {
                mvc.perform(request.cookie(cookies)).andExpect(status().isForbidden());
            }
            var request = new MockHttpServletRequest();
            request.setCookies(cookies);
            request.setAttribute(GuestInviteSession.REQUEST_ATTR, guest);
            assertThat(setup.isAdminLoggedIn(request)).isFalse();
            assertThat(setup.hasAdminScope(request)).isFalse();
            var identity = new HostRequestOwnerIdentityResolver(setup);
            assertThat(identity.isAdminAuthenticated(request)).isFalse();
            assertThat(identity.resolveInvitedGuestRateLimitSubject(request)).contains("invite:7");
            assertThat(new GuestWorkVisibilityScopeFactory().fromRequest(request).enforceVisibility()).isTrue();
        }
        verify(invites, never()).createInvite(any());
        verify(invites, never()).updateInvite(anyLong(), any());
        verify(invites, never()).delete(anyLong());
        mvc.perform(get("/api/auth/check").header("X-Session-Token", adminToken).cookie(invite(guest.code())))
                .andExpect(jsonPath("$.valid").value(false));
        mvc.perform(post("/api/admin/invites").header("X-Session-Token", adminToken).cookie(invite(guest.code()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"solo", "multi"})
    @DisplayName("邀请过期暂停撤销或查询失败均不恢复管理员，后续请求继续拒绝")
    void invalidInviteNeverFallsBack(String mode) throws Exception {
        start(mode);
        when(invites.resolveByCode("unavailable")).thenThrow(new IllegalStateException("fixture unavailable"));
        for (String code : List.of("expired", "paused", "revoked", "unavailable")) {
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(get("/api/auth/check").cookie(admin(), invite(code)))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
                mvc.perform(get("/api/admin/invites").cookie(admin(), invite(code)))
                        .andExpect(status().isForbidden());
                mvc.perform(get("/api/downloaded/thumbnail/123/0").cookie(admin(), invite(code)))
                        .andExpect(status().isForbidden());
            }
        }
        assertThat(setup.isValidSession(adminToken)).isTrue();
        assertThat(setup.isValidSession(otherBrowserToken)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"solo", "multi"})
    @DisplayName("显式退出访客或重新登录清除访客 cookie，保留其他浏览器会话")
    void explicitIdentitySwitch(String mode) throws Exception {
        start(mode);
        for (String code : List.of(guest.code(), "expired")) {
            mvc.perform(post("/api/auth/logout").cookie(admin(), invite(code)))
                    .andExpect(status().isOk()).andExpect(cookie().maxAge(GuestInviteSession.COOKIE_NAME, 0))
                    .andExpect(cookie().doesNotExist("pixiv_session"));
            assertThat(setup.isValidSession(adminToken)).isTrue();
            mvc.perform(get("/api/auth/check").cookie(admin())).andExpect(jsonPath("$.valid").value(true));
        }
        mvc.perform(post("/api/auth/login").cookie(invite(guest.code()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"fixture-admin\",\"password\":\"fixture-password\"}"))
                .andExpect(status().isOk()).andExpect(cookie().exists("pixiv_session"))
                .andExpect(cookie().maxAge(GuestInviteSession.COOKIE_NAME, 0))
                .andExpect(cookie().httpOnly(GuestInviteSession.COOKIE_NAME, true));
        mvc.perform(post("/api/auth/logout").cookie(admin())).andExpect(status().isOk());
        assertThat(setup.isValidSession(adminToken)).isFalse();
        assertThat(setup.isValidSession(otherBrowserToken)).isTrue();
    }
}
