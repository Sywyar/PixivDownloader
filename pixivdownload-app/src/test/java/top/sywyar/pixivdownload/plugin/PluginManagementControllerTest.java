package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.PluginManagementService.LifecycleAction;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PluginManagementService.class);
        AppMessages messages = mock(AppMessages.class);
        AppLocaleResolver localeResolver = mock(AppLocaleResolver.class);
        when(localeResolver.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        // 合成「本地化」串：与 i18n key、与稳定 code 都不同，确保 $.code 断言只能命中专门的稳定码字段。
        when(messages.getOrDefault(any(Locale.class), anyString(), anyString()))
                .thenAnswer(inv -> "localized:" + inv.getArgument(1));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PluginManagementController(service, messages, localeResolver))
                .build();
    }

    @Test
    @DisplayName("GET /api/plugins/status 返回管理视图 JSON（recoveryMode + plugins + apiRequirement/dependencies 投影）")
    void statusReturnsReport() throws Exception {
        when(service.list()).thenReturn(new PluginManagementService.PluginManagementReport(false, List.of(
                new PluginManagementService.PluginManagementEntry(
                        "demo-ext", "demo-ext:nav.label", "1.0.0", PluginKind.FEATURE,
                        new PluginManagementService.PluginApiRequirementView(true, true, "1.0"),
                        List.of(new PluginManagementService.PluginDependencyView("download-workbench", "1.0", false)),
                        "external", PluginStatus.STARTED, PluginRuntimePhase.STARTED, true, false, true,
                        List.of("stop"), List.of()))));

        mockMvc.perform(get("/api/plugins/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryMode").value(false))
                .andExpect(jsonPath("$.plugins[0].id").value("demo-ext"))
                .andExpect(jsonPath("$.plugins[0].source").value("external"))
                .andExpect(jsonPath("$.plugins[0].runtimePhase").value("STARTED"))
                .andExpect(jsonPath("$.plugins[0].managed").value(true))
                .andExpect(jsonPath("$.plugins[0].apiRequirement.specified").value(true))
                .andExpect(jsonPath("$.plugins[0].apiRequirement.satisfied").value(true))
                .andExpect(jsonPath("$.plugins[0].apiRequirement.required").value("1.0"))
                .andExpect(jsonPath("$.plugins[0].dependencies[0].pluginId").value("download-workbench"))
                .andExpect(jsonPath("$.plugins[0].dependencies[0].versionSupport").value("1.0"))
                .andExpect(jsonPath("$.plugins[0].dependencies[0].optional").value(false));
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
}
