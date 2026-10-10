package top.sywyar.pixivdownload.plugin.recovery;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.gui.bootstrap.ApplicationRestartService;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.plugin.CorePlugin;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeDecision;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.AuthFilter;
import top.sywyar.pixivdownload.setup.CsrfProtectionFilter;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.StaticResourceRateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("恢复操作的状态与真实鉴权过滤链")
class RecoveryActionControllerTest {
    private final RecoveryModeService recovery = mock(RecoveryModeService.class);
    private final PluginStatusService plugins = mock(PluginStatusService.class);
    private final ApplicationRestartService application = mock(ApplicationRestartService.class);
    private final SetupService setup = mock(SetupService.class);
    private final AtomicBoolean noGui = new AtomicBoolean();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = createMvc(recovery);
    }

    private MockMvc createMvc(RecoveryModeService mode) {
        var locales = mock(AppLocaleResolver.class);
        when(locales.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        var messages = TestI18nBeans.appMessages();
        var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("core", new CorePlugin().routes());
        when(setup.isSetupComplete()).thenReturn(true);
        when(setup.isValidSession("admin")).thenReturn(true);
        when(setup.getMode()).thenReturn("solo");
        var limits = mock(RateLimitService.class);
        when(limits.isAllowed(anyString())).thenReturn(true);
        when(limits.isAllowedForInvite(anyString())).thenReturn(true);
        var staticLimits = mock(StaticResourceRateLimitService.class);
        when(staticLimits.isAllowed(anyString())).thenReturn(true);
        var invites = mock(GuestInviteService.class);
        when(invites.resolveByCode("guest")).thenReturn(Optional.of(new GuestInviteSession(
                1L, "guest", true, false, false, true, Set.of(), true, Set.of(), true, Set.of(), true, Set.of())));
        var auth = new AuthFilter(setup, staticLimits, limits, locales, messages,
                new StaticListableBeanFactory().getBeanProvider(MaintenanceCoordinator.class),
                invites, mock(GuiTokenProvider.class), routes);
        var controller = new RecoveryActionController(mode, plugins, application, messages, locales,
                () -> noGui.get() ? new top.sywyar.pixivdownload.gui.DesktopUiFailure(
                        top.sywyar.pixivdownload.gui.DesktopUiFailure.Reason.NO_PROVIDER, null) : null);
        when(recovery.decision()).thenReturn(new RecoveryModeDecision(false, List.of()));
        when(plugins.recoveryGateSnapshot()).thenReturn(PluginRecoveryGateSnapshot.safe(PluginTransactionRecoveryReport.success()));
        when(plugins.failureSnapshot()).thenReturn(Map.of());
        when(plugins.report()).thenReturn(PluginStatusReport.empty());
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new RecoveryModeGate(mode, locales, messages),
                        auth, new CsrfProtectionFilter(locales, messages, routes)).build();
    }

    @Test
    @DisplayName("正常模式禁用按钮并拒绝管理员直接调用两个动作")
    void normalModeRejectsActions() throws Exception {
        mvc.perform(get("/api/plugins/recovery").cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actionsAllowed").value(false));
        for (String action : List.of("restart", "exit")) {
            mvc.perform(post("/api/plugins/recovery/" + action).header("Origin", "http://localhost")
                            .cookie(new Cookie("pixiv_session", "admin")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("recovery.action.unavailable"));
        }
        verifyNoInteractions(application);
        verify(plugins, never()).report();
    }

    @Test
    @DisplayName("同时返回禁用与版本不兼容建议，诊断读取失败也保留恢复操作")
    void multipleCausesAndDiagnosticFailure() throws Exception {
        noGui.set(true);
        when(plugins.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic("disabled", PluginStatus.DISABLED, null, true, List.of()),
                new PluginDiagnostic("incompatible", PluginStatus.INCOMPATIBLE_REQUIRED, null, true, List.of()))));
        var messages = TestI18nBeans.appMessages();
        mvc.perform(get("/api/plugins/recovery").cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advice", org.hamcrest.Matchers.containsString(
                        messages.get(Locale.ENGLISH, "recovery.advice.disabled"))))
                .andExpect(jsonPath("$.advice", org.hamcrest.Matchers.containsString(
                        messages.get(Locale.ENGLISH, "recovery.advice.incompatible"))))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.focus.plugins.disabled").value("required"))
                .andExpect(jsonPath("$.focus.plugins.incompatible").value("incompatible"))
                .andExpect(jsonPath("$.focus.categories[0]").value("ui"));
        when(plugins.report()).thenThrow(new IllegalStateException("diagnostic unavailable"));
        mvc.perform(get("/api/plugins/recovery").cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actionsAllowed").value(true))
                .andExpect(jsonPath("$.errors[1]", org.hamcrest.Matchers.containsString("diagnostic unavailable")));
    }

    @Test
    @DisplayName("目录恢复阻断时不再次扫描插件，不生成缺失插件安装建议")
    void blockedDirectoryDoesNotScan() throws Exception {
        when(recovery.isActive()).thenReturn(true);
        when(plugins.recoveryGateSnapshot()).thenReturn(PluginRecoveryGateSnapshot.unchecked());
        mvc.perform(get("/api/plugins/recovery").cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.installationBlocked").value(true))
                .andExpect(jsonPath("$.actionsAllowed").value(true))
                .andExpect(jsonPath("$.focus.plugins").isEmpty())
                .andExpect(jsonPath("$.focus.categories").isEmpty());
        verify(plugins, never()).report();
    }

    @Test
    @DisplayName("真实恢复判定异常不能阻断无 GUI 操作，也不能授权状态未知的普通进程")
    void failingRecoveryEvaluationKeepsGuiFallbackAndFailsClosedOtherwise() throws Exception {
        mvc = createMvc(new RecoveryModeService(plugins, RequiredPluginPolicy.empty()));
        when(plugins.report()).thenThrow(new IllegalStateException("diagnostic unavailable"));
        when(application.requestRestart()).thenReturn(true);
        noGui.set(true);
        mvc.perform(get("/api/plugins/recovery").cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actionsAllowed").value(true))
                .andExpect(jsonPath("$.recoveryMode").value(false));
        mvc.perform(post("/api/plugins/recovery/restart").header("Origin", "http://localhost")
                        .cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isOk());
        noGui.set(false);
        mvc.perform(get("/api/plugins/recovery").cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isServiceUnavailable());
        for (String action : List.of("restart", "exit")) {
            mvc.perform(post("/api/plugins/recovery/" + action).header("Origin", "http://localhost")
                            .cookie(new Cookie("pixiv_session", "admin")))
                    .andExpect(status().isServiceUnavailable());
        }
        verify(application, times(1)).requestRestart();
        verify(application, never()).requestExit();
    }

    @Test
    @DisplayName("恢复与无 GUI 状态都允许本机管理员，状态恢复后立即拒绝")
    void bothRecoveryStatesAllowAndRecheck() throws Exception {
        when(application.requestRestart()).thenReturn(true);
        when(application.requestExit()).thenReturn(true);
        noGui.set(true);
        mvc.perform(get("/api/plugins/recovery").cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actionsAllowed").value(true))
                .andExpect(jsonPath("$.recoveryMode").value(false));
        mvc.perform(post("/api/plugins/recovery/restart").header("Origin", "http://localhost")
                        .cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        noGui.set(false);
        mvc.perform(post("/api/plugins/recovery/exit").header("Origin", "http://localhost")
                        .cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isConflict());
        when(recovery.isActive()).thenReturn(true);
        mvc.perform(post("/api/plugins/recovery/exit").header("Origin", "http://localhost")
                        .cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isOk());
        verify(application).requestRestart();
        verify(application).requestExit();
    }

    @Test
    @DisplayName("恢复模式仍拒绝匿名、邀请访客、远端、伪造本机 Host 与跨站或无来源请求")
    void authorizationAndOriginCannotBeBypassed() throws Exception {
        when(recovery.isActive()).thenReturn(true);
        for (String mode : List.of("solo", "multi")) {
            when(setup.getMode()).thenReturn(mode);
            for (String action : List.of("restart", "exit")) {
                String path = "/api/plugins/recovery/" + action;
                mvc.perform(post(path).header("Origin", "http://localhost")).andExpect(status().isUnauthorized());
                mvc.perform(post(path).header("Origin", "http://localhost")
                                .cookie(new Cookie("pixiv_invite_token", "guest"), new Cookie("pixiv_session", "admin")))
                        .andExpect(status().isForbidden());
                mvc.perform(post(path).header("Origin", "http://localhost")
                                .cookie(new Cookie("pixiv_session", "admin"))
                                .with(request -> { request.setRemoteAddr("203.0.113.7"); return request; }))
                        .andExpect(status().isForbidden());
                mvc.perform(post(path).header("Origin", "http://evil.example").header("Host", "evil.example")
                                .cookie(new Cookie("pixiv_session", "admin")))
                        .andExpect(status().isForbidden());
                mvc.perform(post(path).header("Origin", "http://evil.example")
                                .cookie(new Cookie("pixiv_session", "admin")))
                        .andExpect(status().isForbidden());
                mvc.perform(post(path).cookie(new Cookie("pixiv_session", "admin")))
                        .andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(application);
    }

    @Test
    @DisplayName("启动接替进程失败返回失败，不能向页面报告已接受")
    void processFailureIsReported() throws Exception {
        noGui.set(true);
        mvc.perform(post("/api/plugins/recovery/restart").header("Origin", "http://localhost")
                        .cookie(new Cookie("pixiv_session", "admin")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("recovery.action.failed"));
    }
}
