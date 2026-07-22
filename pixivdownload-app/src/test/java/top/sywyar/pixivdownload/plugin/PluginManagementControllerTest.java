package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.LifecycleAction;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationProjector;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import top.sywyar.pixivdownload.plugin.install.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponseMapper;
import top.sywyar.pixivdownload.plugin.install.PluginInstallService;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.management.PluginManagementController;
import top.sywyar.pixivdownload.plugin.management.BackendContextRestartService;
import top.sywyar.pixivdownload.plugin.management.PluginEnabledConfigurationService;
import top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode;
import top.sywyar.pixivdownload.plugin.management.PluginManagementException;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService;

/**
 * {@link PluginManagementController} 单测（MockMvc standalone）：路由 / 路径变量绑定 / JSON 投影、六个运行期动词逐个
 * 委托 {@link PluginManagementService}、以及 {@link PluginManagementException} 经 {@code @ExceptionHandler} 映射为
 * 「稳定机器码 {@code code} + 本地化 {@code message} + 诊断上下文」的错误响应。
 *
 * <p>AppMessages 桩刻意把 message 解析成与 i18n key、与稳定 code 都<b>不同</b>的合成串（{@code "localized:" + key}），
 * 这样断言 {@code $.code} 取到稳定枚举名（如 {@code UNKNOWN_PLUGIN}）就只能来自专门的稳定码字段、不会被「message 恰好
 * 等于 key」掩盖——即避免桩把「code 缺失 / 退化成本地化文案」蒙混过关。
 */
@DisplayName("PluginManagementController 插件管理后端 API")
class PluginManagementControllerTest {

    private PluginManagementService service;
    private PluginInstallService installService;
    private PluginEnabledConfigurationService enabledConfigurationService;
    private BackendContextRestartService backendRestartService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PluginManagementService.class);
        installService = mock(PluginInstallService.class);
        enabledConfigurationService = mock(PluginEnabledConfigurationService.class);
        backendRestartService = mock(BackendContextRestartService.class);
        AppMessages messages = mock(AppMessages.class);
        AppLocaleResolver localeResolver = mock(AppLocaleResolver.class);
        when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        // 合成「本地化」串：与 i18n key、与稳定 code 都不同，确保 $.code 断言只能命中专门的稳定码字段。
        when(messages.getOrDefault(any(Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> "localized:" + inv.getArgument(1));
        PluginInstallResponseMapper installResponseMapper =
                new PluginInstallResponseMapper(messages, localeResolver);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PluginManagementController(
                        service, installService, installResponseMapper,
                        enabledConfigurationService, backendRestartService, messages, localeResolver))
                .build();
    }

    @Test
    @DisplayName("GET /api/plugins/status 返回管理视图 JSON（recoveryMode + plugins + apiRequirement/dependencies 投影）")
    void statusReturnsReport() throws Exception {
        when(service.list()).thenReturn(new PluginManagementService.PluginManagementReport(false, List.of(
                new PluginManagementService.PluginManagementEntry(
                        "demo-ext", "demo-ext", "nav.label", "nav.summary", "book", "amber", "1.0.0", PluginKind.FEATURE,
                        new PluginManagementService.PluginApiRequirementView(true, true, "1.0"),
                        List.of(new PluginManagementService.PluginDependencyView("download-workbench", "1.0", false)),
                        "external", PluginStatus.STARTED, PluginRuntimePhase.STARTED, true, false, true,
                        List.of("stop"), List.of(), PluginLifecyclePolicy.HOT_RELOAD, true, true))));

        mockMvc.perform(get("/api/plugins/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryMode").value(false))
                .andExpect(jsonPath("$.plugins[0].id").value("demo-ext"))
                .andExpect(jsonPath("$.plugins[0].descriptionKey").value("nav.summary"))
                .andExpect(jsonPath("$.plugins[0].iconKey").value("book"))
                .andExpect(jsonPath("$.plugins[0].colorToken").value("amber"))
                .andExpect(jsonPath("$.plugins[0].source").value("external"))
                .andExpect(jsonPath("$.plugins[0].runtimePhase").value("STARTED"))
                .andExpect(jsonPath("$.plugins[0].managed").value(true))
                .andExpect(jsonPath("$.plugins[0].lifecyclePolicy").value("HOT_RELOAD"))
                .andExpect(jsonPath("$.plugins[0].configuredEnabled").value(true))
                .andExpect(jsonPath("$.plugins[0].toggleable").value(true))
                .andExpect(jsonPath("$.plugins[0].apiRequirement.specified").value(true))
                .andExpect(jsonPath("$.plugins[0].apiRequirement.satisfied").value(true))
                .andExpect(jsonPath("$.plugins[0].apiRequirement.required").value("1.0"))
                .andExpect(jsonPath("$.plugins[0].dependencies[0].pluginId").value("download-workbench"))
                .andExpect(jsonPath("$.plugins[0].dependencies[0].versionSupport").value("1.0"))
                .andExpect(jsonPath("$.plugins[0].dependencies[0].optional").value(false))
                .andExpect(jsonPath("$.plugins[0].verification.status")
                        .value(PluginVerificationProjector.UNVERIFIED_LOCAL))
                .andExpect(jsonPath("$.plugins[0].verification.diagnosticCode")
                        .value("PROVENANCE_MISSING"));
    }

    /** 六个运行期动词 URL → 对应 {@link LifecycleAction}（含期望回显阶段）。 */
    static Stream<Arguments> lifecycleVerbs() {
        return Stream.of(
                arguments("load", LifecycleAction.LOAD, PluginRuntimePhase.LOADED),
                arguments("start", LifecycleAction.START, PluginRuntimePhase.STARTED),
                arguments("quiesce", LifecycleAction.QUIESCE, PluginRuntimePhase.QUIESCED),
                arguments("stop", LifecycleAction.STOP, PluginRuntimePhase.STOPPED),
                arguments("unload", LifecycleAction.UNLOAD, PluginRuntimePhase.UNLOADED),
                arguments("reload", LifecycleAction.RELOAD, PluginRuntimePhase.STARTED));
    }

    @ParameterizedTest(name = "POST /api/plugins/'{'id'}'/{0} 委托 perform({1}) 并回显阶段 {2}")
    @MethodSource("lifecycleVerbs")
    @DisplayName("POST /api/plugins/{id}/{verb} 六个动词逐个委托 perform(对应 LifecycleAction) 并回显执行后阶段")
    void verbDelegatesToService(String verb, LifecycleAction action, PluginRuntimePhase phase) throws Exception {
        when(service.perform("demo-ext", action))
                .thenReturn(new PluginManagementService.PluginActionResult("demo-ext", verb, phase));

        mockMvc.perform(post("/api/plugins/demo-ext/" + verb))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("demo-ext"))
                .andExpect(jsonPath("$.action").value(verb))
                .andExpect(jsonPath("$.phase").value(phase.name()));

        verify(service).perform("demo-ext", action);
    }

    @Test
    @DisplayName("PUT /api/plugins/{id}/enabled 持久化期望启用态并返回生命周期策略")
    void enabledToggleDelegatesToConfigurationService() throws Exception {
        when(enabledConfigurationService.update("demo-ext", false)).thenReturn(
                new PluginEnabledConfigurationService.PluginEnabledState(
                        "demo-ext", false, PluginLifecyclePolicy.BACKEND_RESTART));

        mockMvc.perform(put("/api/plugins/demo-ext/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("demo-ext"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.lifecyclePolicy").value("BACKEND_RESTART"));

        verify(enabledConfigurationService).update("demo-ext", false);
    }

    @Test
    @DisplayName("PUT 插件启用态请求缺少 enabled 时返回结构化 400 且不写配置")
    void enabledToggleRejectsMissingEnabled() throws Exception {
        mockMvc.perform(put("/api/plugins/demo-ext/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOGGLE_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("localized:plugin.manage.error.invalid-toggle-request"))
                .andExpect(jsonPath("$.pluginId").value("demo-ext"))
                .andExpect(jsonPath("$.action").value("update-enabled"));

        verify(enabledConfigurationService, never()).update(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("POST /api/plugins/backend-restart 接受后端上下文重启请求")
    void backendRestartDelegatesToRestartService() throws Exception {
        when(backendRestartService.requestRestart())
                .thenReturn(new BackendContextRestartService.BackendRestartResult(true));

        mockMvc.perform(post("/api/plugins/backend-restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        verify(backendRestartService).requestRestart();
    }

    @Test
    @DisplayName("未知插件 → 404 + 稳定 code（UNKNOWN_PLUGIN）+ 本地化 message + 诊断上下文")
    void unknownPluginMapsTo404() throws Exception {
        when(service.perform(any(), any())).thenThrow(new PluginManagementException(
                PluginManagementErrorCode.UNKNOWN_PLUGIN, "x", "start", null, "Unknown plugin: x"));

        mockMvc.perform(post("/api/plugins/x/start"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PLUGIN"))
                .andExpect(jsonPath("$.message").value("localized:plugin.manage.error.unknown"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.pluginId").value("x"))
                .andExpect(jsonPath("$.action").value("start"));
    }

    @Test
    @DisplayName("必选插件停用 → 409 + 稳定 code（REQUIRED_PLUGIN）+ 本地化 message + 当前阶段诊断")
    void requiredDisableMapsTo409() throws Exception {
        when(service.perform(any(), any())).thenThrow(new PluginManagementException(
                PluginManagementErrorCode.REQUIRED_PLUGIN, "download-workbench", "stop",
                PluginRuntimePhase.STARTED, "Required plugin cannot be disabled"));

        mockMvc.perform(post("/api/plugins/download-workbench/stop"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUIRED_PLUGIN"))
                .andExpect(jsonPath("$.message").value("localized:plugin.manage.error.required"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.pluginId").value("download-workbench"))
                .andExpect(jsonPath("$.action").value("stop"))
                .andExpect(jsonPath("$.runtimePhase").value("STARTED"));
    }

    @Test
    @DisplayName("POST /api/plugins/install 即时激活后返回事务、包身份与运行阶段")
    void installAcceptedReturns200() throws Exception {
        when(installService.install(any(), anyBoolean())).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.INSTALLED, true, false, "ext-demo", "1.0.0", null,
                List.of(), List.of(), List.of("INSTALLED ext-demo 1.0.0"),
                "tx-install", true, false, null,
                ExternalPluginOperation.INSTALLING, PluginRuntimePhase.STARTED, false));

        mockMvc.perform(multipart("/api/plugins/install")
                        .file(new MockMultipartFile("file", "ext-demo.zip", "application/zip", new byte[]{1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("INSTALLED"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.effectiveAfterRestart").value(false))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.pluginId").value("ext-demo"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.packageId").value("ext-demo"))
                .andExpect(jsonPath("$.targetVersion").value("1.0.0"))
                .andExpect(jsonPath("$.operation").value("INSTALLING"))
                .andExpect(jsonPath("$.runtimePhase").value("STARTED"))
                .andExpect(jsonPath("$.transactionId").value("tx-install"))
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.updated").value(false))
                .andExpect(jsonPath("$.message").value("localized:plugin.install.outcome.installed"));

        verify(installService).install(any(), anyBoolean());
    }

    @Test
    @DisplayName("POST /api/plugins/install 留下恢复事务时强制返回 503 阻断机器态")
    void installRecoveryBlockedReturns503() throws Exception {
        when(installService.install(any(), anyBoolean())).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.INSTALLED, true, false, "ext-demo", "1.0.0", null,
                List.of(), List.of(), List.of(), List.of("transaction recovery required"),
                "tx-blocked", true, false, null,
                ExternalPluginOperation.FAILED, PluginRuntimePhase.STARTED, true, false));

        mockMvc.perform(multipart("/api/plugins/install")
                        .file(new MockMultipartFile(
                                "file", "ext-demo.zip", "application/zip", new byte[]{1, 2, 3})))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.outcome").value("INSTALLED"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.recoveryBlocked").value(true))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.transactionId").value("tx-blocked"))
                .andExpect(jsonPath("$.message").value("localized:plugin.install.recovery-blocked"));
    }

    @Test
    @DisplayName("POST /api/plugins/install 不兼容 → 409 + 稳定 outcome（REJECTED_INCOMPATIBLE）+ 本地化 message，not accepted")
    void installIncompatibleReturns409() throws Exception {
        when(installService.install(any(), anyBoolean())).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.REJECTED_INCOMPATIBLE, false, false, "ext-demo", "1.0.0", null,
                List.of(), List.of(), List.of("requires core API 2.0")));

        mockMvc.perform(multipart("/api/plugins/install")
                        .file(new MockMultipartFile("file", "ext-demo.zip", "application/zip", new byte[]{1, 2, 3})))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("REJECTED_INCOMPATIBLE"))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.effectiveAfterRestart").value(false))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("localized:plugin.install.outcome.rejected-incompatible"));
    }

    @Test
    @DisplayName("POST /api/plugins/install 资源超限 → 413 + 稳定 outcome（REJECTED_TOO_LARGE）+ 本地化 message，not accepted")
    void installTooLargeReturns413() throws Exception {
        when(installService.install(any(), anyBoolean())).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.REJECTED_TOO_LARGE, false, false, null, null, null,
                List.of(), List.of(), List.of("too many zip entries")));

        mockMvc.perform(multipart("/api/plugins/install")
                        .file(new MockMultipartFile("file", "bomb.zip", "application/zip", new byte[]{1, 2, 3})))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.outcome").value("REJECTED_TOO_LARGE"))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.message").value("localized:plugin.install.outcome.rejected-too-large"));
    }

    @Test
    @DisplayName("POST /api/plugins/install 缺失 file 部分 → 委托 install(null, false) → 400 + 稳定 outcome（REJECTED_EMPTY），not accepted")
    void installMissingFileReturns400() throws Exception {
        when(installService.install(any(), anyBoolean())).thenReturn(new PluginInstallReport(
                PluginInstallOutcome.REJECTED_EMPTY, false, false, null, null, null,
                List.of(), List.of(), List.of("no plugin package uploaded")));

        mockMvc.perform(multipart("/api/plugins/install"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("REJECTED_EMPTY"))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.effectiveAfterRestart").value(false))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("localized:plugin.install.outcome.rejected-empty"));

        verify(installService).install(isNull(), eq(false));
    }
}
